package com.example.network

import android.content.Context
import android.util.Log
import com.example.model.CallSession
import com.example.model.FirebaseConfig
import com.example.model.FriendRequest
import com.example.model.Message
import com.example.model.User
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class FirebaseHelper(private val context: Context) {

    var auth: FirebaseAuth? = null
    var database: FirebaseDatabase? = null
    private var isInitialized = false

    init {
        setupFirebase()
    }

    fun setupFirebase() {
        try {
            // Direct, absolute sources of truth to avoid any previous SharedPreferences contamination
            val apiKeyToUse = "AIzaSyAWSX8A7Gl1cp8M8J_0lWNoWwFkVPLLjYo"
            val appIdToUse = "1:459032189195:android:8dabf5ab509bc479d7ea0e"
            val projectIdToUse = "chat-app-9220e"
            val databaseUrlToUse = "https://chat-app-9220e-default-rtdb.firebaseio.com/"

            val options = FirebaseOptions.Builder()
                .setApiKey(apiKeyToUse)
                .setApplicationId(appIdToUse)
                .setProjectId(projectIdToUse)
                .setDatabaseUrl(databaseUrlToUse)
                .build()

            try {
                FirebaseApp.getInstance().delete()
            } catch (e: Exception) {}

            FirebaseApp.initializeApp(context.applicationContext, options)
            auth = FirebaseAuth.getInstance()
            database = FirebaseDatabase.getInstance(databaseUrlToUse)
            isInitialized = true
            Log.d("FirebaseHelper", "Firebase initialized programmatically with absolute compile-time credentials.")
        } catch (e: Exception) {
            Log.e("FirebaseHelper", "Firebase initialization failed: ${e.message}")
            // Final fallback
            try {
                val app = FirebaseApp.initializeApp(context.applicationContext)
                if (app != null) {
                    auth = FirebaseAuth.getInstance()
                    database = FirebaseDatabase.getInstance("https://chat-app-9220e-default-rtdb.firebaseio.com/")
                    isInitialized = true
                    Log.d("FirebaseHelper", "Firebase initialized with fallback google-services.json automatically.")
                }
            } catch (ex: Exception) {
                Log.e("FirebaseHelper", "Firebase fallback initialization failed: ${ex.message}")
                isInitialized = false
            }
        }
    }

    fun isFirebaseReady(): Boolean {
        return isInitialized && auth != null && database != null
    }

    fun saveConfig(config: FirebaseConfig) {
        val prefs = context.getSharedPreferences("firebase_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("apiKey", config.apiKey)
            .putString("appId", config.appId)
            .putString("projectId", config.projectId)
            .putString("databaseUrl", config.databaseUrl)
            .apply()
        setupFirebase()
    }

    fun getSavedConfig(): FirebaseConfig {
        val prefs = context.getSharedPreferences("firebase_prefs", Context.MODE_PRIVATE)
        return FirebaseConfig(
            apiKey = prefs.getString("apiKey", "") ?: "",
            appId = prefs.getString("appId", "") ?: "",
            projectId = prefs.getString("projectId", "") ?: "",
            databaseUrl = prefs.getString("databaseUrl", "") ?: ""
        )
    }

    fun clearSavedConfig() {
        val prefs = context.getSharedPreferences("firebase_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        setupFirebase()
    }

    // --- Authentication ---

    fun registerUser(
        name: String,
        email: String,
        password: String,
        avatarUrl: String,
        onSuccess: (User) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val authInstance = auth
        val dbInstance = database
        if (authInstance == null || dbInstance == null) {
            onFailure("Firebase are not configured. Please add Firebase details in Settings.")
            return
        }

        authInstance.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener { result ->
                val uid = result.user?.uid ?: ""
                val newUser = User(
                    uid = uid,
                    name = name,
                    email = email,
                    avatarUrl = avatarUrl,
                    status = "online"
                )
                // Save user details in database
                dbInstance.getReference("users").child(uid).setValue(newUser)
                    .addOnSuccessListener {
                        onSuccess(newUser)
                    }
                    .addOnFailureListener { e ->
                        onFailure("User created but database failed: ${e.message}")
                    }
            }
            .addOnFailureListener { e ->
                onFailure(e.localizedMessage ?: "Registration failed")
            }
    }

    fun loginUser(
        email: String,
        password: String,
        onSuccess: (User) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val authInstance = auth
        val dbInstance = database
        if (authInstance == null || dbInstance == null) {
            onFailure("Firebase is not configured. Please set your credentials in Settings.")
            return
        }

        authInstance.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener { result ->
                val uid = result.user?.uid ?: ""
                getUserDetails(uid) { user ->
                    if (user != null) {
                        setUserStatus("online")
                        onSuccess(user)
                    } else {
                        val fallbackUser = User(uid = uid, name = email.substringBefore("@"), email = email, status = "online")
                        dbInstance.getReference("users").child(uid).setValue(fallbackUser)
                        onSuccess(fallbackUser)
                    }
                }
            }
            .addOnFailureListener { e ->
                onFailure(e.localizedMessage ?: "Login failed")
            }
    }

    fun resetPassword(email: String, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        val authInstance = auth ?: run {
            onFailure("Firebase not initialized")
            return
        }
        authInstance.sendPasswordResetEmail(email)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e -> onFailure(e.localizedMessage ?: "Reset failed") }
    }

    fun logoutUser() {
        setUserStatus("offline")
        auth?.signOut()
    }

    fun getCurrentUserUid(): String? {
        return auth?.currentUser?.uid
    }

    fun getUserDetails(uid: String, onResult: (User?) -> Unit) {
        val dbInstance = database ?: run {
            onResult(null)
            return
        }
        dbInstance.getReference("users").child(uid)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val user = snapshot.getValue(User::class.java)
                    onResult(user)
                }
                override fun onCancelled(error: DatabaseError) {
                    onResult(null)
                }
            })
    }

    fun setUserStatus(status: String) {
        val uid = getCurrentUserUid() ?: return
        val dbInstance = database ?: return
        dbInstance.getReference("users").child(uid).child("status").setValue(status)
    }

    // --- Search Users ---

    fun searchUserByUid(uid: String, onResult: (User?) -> Unit) {
        getUserDetails(uid.trim(), onResult)
    }

    fun searchUsers(query: String, onResult: (List<User>) -> Unit) {
        val dbInstance = database ?: run {
            onResult(emptyList())
            return
        }
        val trimmedQuery = query.trim()
        dbInstance.getReference("users")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val list = mutableListOf<User>()
                    for (child in snapshot.children) {
                        val user = child.getValue(User::class.java)
                        if (user != null && user.uid != getCurrentUserUid()) {
                            if (user.uid.equals(trimmedQuery, ignoreCase = true) ||
                                user.email.contains(trimmedQuery, ignoreCase = true) ||
                                user.name.contains(trimmedQuery, ignoreCase = true)
                            ) {
                                list.add(user)
                            }
                        }
                    }
                    onResult(list)
                }

                override fun onCancelled(error: DatabaseError) {
                    onResult(emptyList())
                }
            })
    }

    // --- Friend Requests ---

    fun sendFriendRequest(receiverUid: String, receiverName: String, receiverAvatar: String, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        val senderUid = getCurrentUserUid() ?: return
        val dbInstance = database ?: return

        getUserDetails(senderUid) { sender ->
            val request = FriendRequest(
                id = "${senderUid}_${receiverUid}",
                senderUid = senderUid,
                senderName = sender?.name ?: "User",
                senderAvatar = sender?.avatarUrl ?: "",
                receiverUid = receiverUid,
                status = "pending"
            )

            // Store in friendRequests nodes
            dbInstance.getReference("friendRequests").child(receiverUid).child(senderUid).setValue(request)
                .addOnSuccessListener { onSuccess() }
                .addOnFailureListener { e -> onFailure(e.localizedMessage ?: "Request failed") }
        }
    }

    fun listenToFriendRequests(onRequestsChange: (List<FriendRequest>) -> Unit): ValueEventListener? {
        val uid = getCurrentUserUid() ?: return null
        val dbInstance = database ?: return null

        val ref = dbInstance.getReference("friendRequests").child(uid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<FriendRequest>()
                for (child in snapshot.children) {
                    val req = child.getValue(FriendRequest::class.java)
                    if (req != null && req.status == "pending") {
                        list.add(req)
                    }
                }
                onRequestsChange(list)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        return listener
    }

    fun respondToFriendRequest(senderUid: String, accept: Boolean, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        val receiverUid = getCurrentUserUid() ?: return
        val dbInstance = database ?: return

        val status = if (accept) "accepted" else "rejected"
        val ref = dbInstance.getReference("friendRequests").child(receiverUid).child(senderUid)

        if (accept) {
            // update status and add to friends list
            ref.child("status").setValue(status)
                .addOnSuccessListener {
                    // Add each other as friends
                    dbInstance.getReference("friends").child(receiverUid).child(senderUid).setValue(true)
                    dbInstance.getReference("friends").child(senderUid).child(receiverUid).setValue(true)
                    // Remove pending request from list
                    ref.removeValue()
                    onSuccess()
                }
                .addOnFailureListener { e -> onFailure(e.localizedMessage ?: "Failed to accept") }
        } else {
            ref.removeValue()
                .addOnSuccessListener { onSuccess() }
                .addOnFailureListener { e -> onFailure(e.localizedMessage ?: "Failed to reject") }
        }
    }

    fun listenToFriends(onFriendsChange: (List<User>) -> Unit): ValueEventListener? {
        val uid = getCurrentUserUid() ?: return null
        val dbInstance = database ?: return null

        val friendsRef = dbInstance.getReference("friends").child(uid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val friendUids = mutableListOf<String>()
                for (child in snapshot.children) {
                    if (child.getValue(Boolean::class.java) == true) {
                        friendUids.add(child.key ?: "")
                    }
                }
                fetchMultipleUserDetails(friendUids, onFriendsChange)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        friendsRef.addValueEventListener(listener)
        return listener
    }

    private fun fetchMultipleUserDetails(uids: List<String>, onResult: (List<User>) -> Unit) {
        val dbInstance = database ?: return
        if (uids.isEmpty()) {
            onResult(emptyList())
            return
        }
        dbInstance.getReference("users")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val list = mutableListOf<User>()
                    for (uid in uids) {
                        val userSnapshot = snapshot.child(uid)
                        val user = userSnapshot.getValue(User::class.java)
                        if (user != null) {
                            list.add(user)
                        }
                    }
                    onResult(list)
                }
                override fun onCancelled(error: DatabaseError) {
                    onResult(emptyList())
                }
            })
    }

    // --- Messaging ---

    fun getChatRoomId(uid1: String, uid2: String): String {
        return if (uid1 < uid2) "${uid1}_${uid2}" else "${uid2}_${uid1}"
    }

    fun sendMessage(receiverId: String, text: String, imageUrl: String = "", onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        val senderId = getCurrentUserUid() ?: return
        val dbInstance = database ?: return

        val roomId = getChatRoomId(senderId, receiverId)
        val ref = dbInstance.getReference("chats").child(roomId).child("messages").push()
        val messageId = ref.key ?: ""

        val message = Message(
            messageId = messageId,
            senderId = senderId,
            receiverId = receiverId,
            text = text,
            imageUrl = imageUrl,
            timestamp = System.currentTimeMillis()
        )

        ref.setValue(message)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e -> onFailure(e.localizedMessage ?: "Failed to send message") }
    }

    fun listenToMessages(receiverId: String, onNewMessages: (List<Message>) -> Unit): ValueEventListener? {
        val senderId = getCurrentUserUid() ?: return null
        val dbInstance = database ?: return null

        val roomId = getChatRoomId(senderId, receiverId)
        val ref = dbInstance.getReference("chats").child(roomId).child("messages")

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<Message>()
                for (child in snapshot.children) {
                    val msg = child.getValue(Message::class.java)
                    if (msg != null) {
                        list.add(msg)
                    }
                }
                onNewMessages(list)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        return listener
    }

    // --- Calling (Agora Signaling) ---

    fun initiateCall(receiverId: String, type: String, onSuccess: (CallSession) -> Unit, onFailure: (String) -> Unit) {
        val callerId = getCurrentUserUid() ?: return
        val dbInstance = database ?: return

        getUserDetails(callerId) { caller ->
            val callId = dbInstance.getReference("calls").push().key ?: ""
            val session = CallSession(
                callId = callId,
                callerId = callerId,
                callerName = caller?.name ?: "Caller",
                callerAvatar = caller?.avatarUrl ?: "",
                receiverId = receiverId,
                type = type,
                status = "ringing",
                timestamp = System.currentTimeMillis()
            )

            // Set call details
            dbInstance.getReference("calls").child(callId).setValue(session)
                .addOnSuccessListener {
                    // Update user nodes to state they have an incoming call
                    dbInstance.getReference("users").child(receiverId).child("currentCallId").setValue(callId)
                    dbInstance.getReference("users").child(callerId).child("currentCallId").setValue(callId)
                    onSuccess(session)
                }
                .addOnFailureListener { e -> onFailure(e.localizedMessage ?: "Failed to initiate call") }
        }
    }

    fun listenToIncomingCalls(onCallReceived: (CallSession?) -> Unit): ValueEventListener? {
        val uid = getCurrentUserUid() ?: return null
        val dbInstance = database ?: return null

        val ref = dbInstance.getReference("users").child(uid).child("currentCallId")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val callId = snapshot.getValue(String::class.java)
                if (!callId.isNullOrEmpty()) {
                    dbInstance.getReference("calls").child(callId)
                        .addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(callSnapshot: DataSnapshot) {
                                val session = callSnapshot.getValue(CallSession::class.java)
                                // Only notify if current user is receiver and call is ringing
                                if (session != null && session.receiverId == uid && session.status == "ringing") {
                                    onCallReceived(session)
                                } else if (session == null || session.status == "ended") {
                                    onCallReceived(null)
                                }
                            }
                            override fun onCancelled(error: DatabaseError) {}
                        })
                } else {
                    onCallReceived(null)
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        return listener
    }

    fun listenToCallSession(callId: String, onSessionChanged: (CallSession?) -> Unit): ValueEventListener? {
        val dbInstance = database ?: return null
        val ref = dbInstance.getReference("calls").child(callId)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val session = snapshot.getValue(CallSession::class.java)
                onSessionChanged(session)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        return listener
    }

    fun acceptCall(callId: String) {
        val dbInstance = database ?: return
        dbInstance.getReference("calls").child(callId).child("status").setValue("active")
    }

    fun endCall(callId: String, callerId: String, receiverId: String) {
        val dbInstance = database ?: return
        dbInstance.getReference("calls").child(callId).child("status").setValue("ended")
            .addOnCompleteListener {
                dbInstance.getReference("users").child(callerId).child("currentCallId").setValue("")
                dbInstance.getReference("users").child(receiverId).child("currentCallId").setValue("")
            }
    }

    fun updateProfile(name: String, avatarUrl: String, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        val uid = getCurrentUserUid() ?: return
        val dbInstance = database ?: return

        val updates = mapOf(
            "name" to name,
            "avatarUrl" to avatarUrl
        )
        dbInstance.getReference("users").child(uid).updateChildren(updates)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e -> onFailure(e.localizedMessage ?: "Profile update failed") }
    }

    fun removeCallSessionListener(callId: String, listener: ValueEventListener) {
        val dbInstance = database ?: return
        dbInstance.getReference("calls").child(callId).removeEventListener(listener)
    }

    fun removeIncomingCallsListener(listener: ValueEventListener) {
        val uid = getCurrentUserUid() ?: return
        val dbInstance = database ?: return
        dbInstance.getReference("users").child(uid).child("currentCallId").removeEventListener(listener)
    }

    fun removeMessagesListener(receiverId: String, listener: ValueEventListener) {
        val senderId = getCurrentUserUid() ?: return
        val dbInstance = database ?: return
        val roomId = getChatRoomId(senderId, receiverId)
        dbInstance.getReference("chats").child(roomId).child("messages").removeEventListener(listener)
    }

    fun removeFriendsListener(listener: ValueEventListener) {
        val uid = getCurrentUserUid() ?: return
        val dbInstance = database ?: return
        dbInstance.getReference("friends").child(uid).removeEventListener(listener)
    }

    fun removeFriendRequestsListener(listener: ValueEventListener) {
        val uid = getCurrentUserUid() ?: return
        val dbInstance = database ?: return
        dbInstance.getReference("friendRequests").child(uid).removeEventListener(listener)
    }

    fun saveCallLog(roomId: String, message: Message) {
        val dbInstance = database ?: return
        dbInstance.getReference("chats").child(roomId).child("messages").child(message.messageId).setValue(message)
    }

    fun markMessagesAsRead(friendUid: String) {
        val senderId = getCurrentUserUid() ?: return
        val dbInstance = database ?: return
        val roomId = getChatRoomId(senderId, friendUid)
        val ref = dbInstance.getReference("chats").child(roomId).child("messages")
        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (child in snapshot.children) {
                    val msg = child.getValue(Message::class.java)
                    if (msg != null && msg.senderId == friendUid && !msg.isRead) {
                        child.ref.child("isRead").setValue(true)
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }
}
