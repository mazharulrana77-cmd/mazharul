package com.example.viewmodel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.CallSession
import com.example.model.FirebaseConfig
import com.example.model.FriendRequest
import com.example.model.Message
import com.example.model.User
import com.example.network.AgoraManager
import com.example.network.FirebaseHelper
import com.example.network.ImgbbResponse
import com.example.network.ImgbbService
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class ChatViewModel(private val context: Context) : ViewModel() {

    val firebaseHelper = FirebaseHelper(context)
    val agoraManager = AgoraManager(context)
    private val imgbbService = ImgbbService.create()

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    private val _firebaseReady = MutableStateFlow(false)
    val firebaseReady: StateFlow<Boolean> = _firebaseReady.asStateFlow()

    private val _searchResults = MutableStateFlow<List<User>>(emptyList())
    val searchResults: StateFlow<List<User>> = _searchResults.asStateFlow()

    private val _friends = MutableStateFlow<List<User>>(emptyList())
    val friends: StateFlow<List<User>> = _friends.asStateFlow()

    private val _friendRequests = MutableStateFlow<List<FriendRequest>>(emptyList())
    val friendRequests: StateFlow<List<FriendRequest>> = _friendRequests.asStateFlow()

    private val _activeMessages = MutableStateFlow<List<Message>>(emptyList())
    val activeMessages: StateFlow<List<Message>> = _activeMessages.asStateFlow()

    private val _currentCall = MutableStateFlow<CallSession?>(null)
    val currentCall: StateFlow<CallSession?> = _currentCall.asStateFlow()

    private val _remoteUserUid = MutableStateFlow<Int?>(null)
    val remoteUserUid: StateFlow<Int?> = _remoteUserUid.asStateFlow()

    private val _isUploadingImage = MutableStateFlow(false)
    val isUploadingImage: StateFlow<Boolean> = _isUploadingImage.asStateFlow()

    private val _uploadError = MutableStateFlow<String?>(null)
    val uploadError: StateFlow<String?> = _uploadError.asStateFlow()

    // Active Database Listeners for Cleanup
    private var friendsListener: ValueEventListener? = null
    private var requestsListener: ValueEventListener? = null
    private var messagesListener: ValueEventListener? = null
    private var callListener: ValueEventListener? = null
    private var activeCallSessionListener: ValueEventListener? = null

    private var activeChatUserUid: String? = null

    // Call Sound / Ringtone Playback Support
    private var ringtonePlayer: android.media.Ringtone? = null
    private var isPlayingRingtone = false

    // Fallback Simulated Data for Out-Of-The-Box Testing (when Firebase is not fully configured)
    private var isSimulatedMode = false
    private val simulatedUsers = mutableListOf<User>()
    private val simulatedRequests = MutableStateFlow<List<FriendRequest>>(emptyList())
    private val simulatedFriends = MutableStateFlow<List<User>>(emptyList())
    private val simulatedChats = mutableMapOf<String, MutableList<Message>>()

    private var activeCallStartTime: Long = 0L
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        checkFirebaseStatus()
        setupSimulatedData()
        startNetworkMonitoring()

        // Listen to active/incoming calls to control the ringtone automatically and log durations
        var previousCall: CallSession? = null
        viewModelScope.launch {
            currentCall.collect { call ->
                if (call != null && call.status == "ringing") {
                    startRingtone()
                } else {
                    stopRingtone()
                }

                if (call != null && call.status == "active") {
                    if (activeCallStartTime == 0L) {
                        activeCallStartTime = System.currentTimeMillis()
                    }
                }

                if (call == null || call.status == "ended") {
                    previousCall?.let { prev ->
                        if (prev.status == "active" || prev.status == "ringing") {
                            recordCallLog(prev)
                        }
                    }
                }

                previousCall = call
            }
        }

        // Set up Agora listeners to capture when the remote user joins the stream
        agoraManager.onRemoteUserJoined = { uid ->
            _remoteUserUid.value = uid
            Log.d("ChatViewModel", "Remote user joined Agora channel with UID: $uid")
        }
        agoraManager.onRemoteUserLeft = { uid ->
            _remoteUserUid.value = null
            Log.d("ChatViewModel", "Remote user left Agora channel with UID: $uid")
        }
        agoraManager.onJoinChannelSuccess = { channel, uid ->
            Log.d("ChatViewModel", "Joined channel successfully: $channel, my uid: $uid")
        }
    }

    fun startRingtone() {
        if (isPlayingRingtone) return
        try {
            val notificationUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
                ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            if (notificationUri != null) {
                ringtonePlayer = android.media.RingtoneManager.getRingtone(context, notificationUri)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    ringtonePlayer?.audioAttributes = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }
                ringtonePlayer?.play()
                isPlayingRingtone = true
            }
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Failed to play ringtone", e)
        }
    }

    fun stopRingtone() {
        try {
            ringtonePlayer?.stop()
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Failed to stop ringtone", e)
        } finally {
            ringtonePlayer = null
            isPlayingRingtone = false
        }
    }

    fun playNotificationSound() {
        try {
            val notificationUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            val r = android.media.RingtoneManager.getRingtone(context, notificationUri)
            r?.play()
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Failed to play notification sound", e)
        }
    }

    private fun checkFirebaseStatus() {
        val ready = firebaseHelper.isFirebaseReady()
        _firebaseReady.value = ready
        isSimulatedMode = !ready
        if (ready) {
            val loggedInUid = firebaseHelper.getCurrentUserUid()
            if (loggedInUid != null) {
                firebaseHelper.getUserDetails(loggedInUid) { user ->
                    _currentUser.value = user ?: User(uid = loggedInUid, name = "Firebase User")
                    startFirebaseListeners()
                }
            }
        }
    }

    fun isUsingSimulatedMode(): Boolean {
        return isSimulatedMode
    }

    private fun setupSimulatedData() {
        // Mock data to let them see and test a "beautiful functional" screen instantly before entering credentials
        simulatedUsers.add(User("rana_uid", "Rana Ahmed", "rana@chatapp.com", "https://i.ibb.co.com/gJZVWhG/avatar1.png", "online"))
        simulatedUsers.add(User("mazhar_uid", "Mazharul Islam", "mazhar@chatapp.com", "https://i.ibb.co.com/S7W7f8c/avatar2.png", "online"))
        simulatedUsers.add(User("tonmoy_uid", "Tonmoy Roy", "tonmoy@chatapp.com", "https://i.ibb.co.com/k9b6N8t/avatar3.png", "offline"))

        simulatedRequests.value = listOf(
            FriendRequest(
                id = "req_1",
                senderUid = "tonmoy_uid",
                senderName = "Tonmoy Roy",
                senderAvatar = "https://i.ibb.co.com/k9b6N8t/avatar3.png",
                receiverUid = "me",
                status = "pending"
            )
        )

        simulatedFriends.value = listOf(
            User("rana_uid", "Rana Ahmed", "rana@chatapp.com", "https://i.ibb.co.com/gJZVWhG/avatar1.png", "online"),
            User("mazhar_uid", "Mazharul Islam", "mazhar@chatapp.com", "https://i.ibb.co.com/S7W7f8c/avatar2.png", "online")
        )

        simulatedChats["rana_uid"] = mutableListOf(
            Message("m1", "rana_uid", "me", "Hello, how are you?", "", System.currentTimeMillis() - 60000),
            Message("m2", "me", "rana_uid", "I am doing great! How about you?", "", System.currentTimeMillis() - 30000)
        )
    }

    fun updateFirebaseConfig(config: FirebaseConfig) {
        firebaseHelper.saveConfig(config)
        checkFirebaseStatus()
    }

    fun clearFirebaseConfig() {
        firebaseHelper.clearSavedConfig()
        checkFirebaseStatus()
    }

    // --- Authentication Actions ---

    fun login(email: String, password: String, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        if (isSimulatedMode) {
            // Local fallback simulation login
            if (email.contains("@") && password.length >= 6) {
                val user = User(
                    uid = "me_simulated",
                    name = email.substringBefore("@").replaceFirstChar { it.uppercase() },
                    email = email,
                    avatarUrl = "https://i.ibb.co.com/gJZVWhG/avatar1.png",
                    status = "online"
                )
                _currentUser.value = user
                _friends.value = simulatedFriends.value
                _friendRequests.value = simulatedRequests.value
                onSuccess()
            } else {
                onFailure("Please enter a valid email and at least 6 characters for the password.")
            }
        } else {
            firebaseHelper.loginUser(email, password, { user ->
                _currentUser.value = user
                startFirebaseListeners()
                onSuccess()
            }, onFailure)
        }
    }

    fun register(name: String, email: String, password: String, avatarUrl: String, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        if (isSimulatedMode) {
            val user = User(
                uid = "me_simulated",
                name = name,
                email = email,
                avatarUrl = avatarUrl.ifEmpty { "https://i.ibb.co.com/gJZVWhG/avatar1.png" },
                status = "online"
            )
            _currentUser.value = user
            _friends.value = simulatedFriends.value
            _friendRequests.value = simulatedRequests.value
            onSuccess()
        } else {
            firebaseHelper.registerUser(name, email, password, avatarUrl, { user ->
                _currentUser.value = user
                startFirebaseListeners()
                onSuccess()
            }, onFailure)
        }
    }

    fun forgotPassword(email: String, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        if (isSimulatedMode) {
            onSuccess()
        } else {
            firebaseHelper.resetPassword(email, onSuccess, onFailure)
        }
    }

    fun logout() {
        if (!isSimulatedMode) {
            firebaseHelper.logoutUser()
        }
        stopFirebaseListeners()
        _currentUser.value = null
        _friends.value = emptyList()
        _friendRequests.value = emptyList()
        _activeMessages.value = emptyList()
        _currentCall.value = null
    }

    fun updateProfile(name: String, avatarUrl: String, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        val user = _currentUser.value ?: return
        if (isSimulatedMode) {
            _currentUser.value = user.copy(name = name, avatarUrl = avatarUrl)
            onSuccess()
        } else {
            firebaseHelper.updateProfile(name, avatarUrl, {
                _currentUser.value = user.copy(name = name, avatarUrl = avatarUrl)
                onSuccess()
            }, onFailure)
        }
    }

    // --- Search Users ---

    fun searchUsers(query: String) {
        if (query.trim().isEmpty()) {
            _searchResults.value = emptyList()
            return
        }
        if (isSimulatedMode) {
            _searchResults.value = simulatedUsers.filter {
                it.uid.contains(query, true) || it.name.contains(query, true) || it.email.contains(query, true)
            }
        } else {
            firebaseHelper.searchUsers(query) { results ->
                _searchResults.value = results
            }
        }
    }

    // --- Friend Requests ---

    fun sendFriendRequest(receiver: User, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        if (isSimulatedMode) {
            onSuccess()
        } else {
            firebaseHelper.sendFriendRequest(receiver.uid, receiver.name, receiver.avatarUrl, onSuccess, onFailure)
        }
    }

    fun acceptFriendRequest(request: FriendRequest) {
        if (isSimulatedMode) {
            // Add to simulated friends
            val newFriend = User(
                uid = request.senderUid,
                name = request.senderName,
                avatarUrl = request.senderAvatar,
                status = "online"
            )
            simulatedFriends.value = simulatedFriends.value + newFriend
            _friends.value = simulatedFriends.value
            simulatedRequests.value = simulatedRequests.value.filter { it.id != request.id }
            _friendRequests.value = simulatedRequests.value
        } else {
            firebaseHelper.respondToFriendRequest(request.senderUid, true, {}, {
                Log.e("ChatViewModel", "Accept request failed: $it")
            })
        }
    }

    fun rejectFriendRequest(request: FriendRequest) {
        if (isSimulatedMode) {
            simulatedRequests.value = simulatedRequests.value.filter { it.id != request.id }
            _friendRequests.value = simulatedRequests.value
        } else {
            firebaseHelper.respondToFriendRequest(request.senderUid, false, {}, {
                Log.e("ChatViewModel", "Reject request failed: $it")
            })
        }
    }

    // --- Messaging ---

    fun selectChatUser(friendUid: String) {
        stopChatListeners()
        activeChatUserUid = friendUid
        if (isSimulatedMode) {
            _activeMessages.value = simulatedChats[friendUid] ?: emptyList()
        } else {
            firebaseHelper.markMessagesAsRead(friendUid)
            messagesListener = firebaseHelper.listenToMessages(friendUid) { messages ->
                val previousCount = _activeMessages.value.size
                _activeMessages.value = messages
                // Play notification if a new message is received from the other user
                if (messages.size > previousCount) {
                    val lastMessage = messages.lastOrNull()
                    if (lastMessage != null && lastMessage.senderId != _currentUser.value?.uid) {
                        playNotificationSound()
                        firebaseHelper.markMessagesAsRead(friendUid)
                    }
                }
            }
        }
    }

    fun sendMessage(receiverId: String, text: String, imageUrl: String = "") {
        if (isSimulatedMode) {
            val list = simulatedChats[receiverId] ?: mutableListOf()
            val msg = Message(
                messageId = "sim_m_${System.currentTimeMillis()}",
                senderId = "me",
                receiverId = receiverId,
                text = text,
                imageUrl = imageUrl,
                timestamp = System.currentTimeMillis()
            )
            list.add(msg)
            simulatedChats[receiverId] = list
            _activeMessages.value = list.toList()

            // Trigger simulated bot reply
            viewModelScope.launch {
                kotlinx.coroutines.delay(1500)
                val botMsg = Message(
                    messageId = "sim_bot_${System.currentTimeMillis()}",
                    senderId = receiverId,
                    receiverId = "me",
                    text = "Thank you for the message! Configure your own Firebase credentials in Settings to start real-time messaging and calls.",
                    imageUrl = "",
                    timestamp = System.currentTimeMillis()
                )
                list.add(botMsg)
                simulatedChats[receiverId] = list
                _activeMessages.value = list.toList()
            }
        } else {
            firebaseHelper.sendMessage(receiverId, text, imageUrl, {}, {
                Log.e("ChatViewModel", "Failed to send message: $it")
            })
        }
    }

    // --- Image Upload to Imgbb ---

    fun uploadImageToImgbb(uri: Uri, apiKey: String, onComplete: (String) -> Unit) {
        _isUploadingImage.value = true
        _uploadError.value = null

        viewModelScope.launch {
            val base64 = uriToBase64(context, uri)
            if (base64 == null) {
                _isUploadingImage.value = false
                _uploadError.value = "Image encoding failed."
                return@launch
            }

            imgbbService.uploadImage(apiKey.trim(), base64).enqueue(object : Callback<ImgbbResponse> {
                override fun onResponse(call: Call<ImgbbResponse>, response: Response<ImgbbResponse>) {
                    _isUploadingImage.value = false
                    val body = response.body()
                    if (response.isSuccessful && body != null && body.success && body.data != null) {
                        val url = body.data.url
                        onComplete(url)
                    } else {
                        _uploadError.value = "Imgbb Error: ${response.message()}"
                    }
                }

                override fun onFailure(call: Call<ImgbbResponse>, t: Throwable) {
                    _isUploadingImage.value = false
                    _uploadError.value = t.localizedMessage ?: "Network failed."
                }
            })
        }
    }

    private fun uriToBase64(context: Context, uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes()
            inputStream?.close()
            if (bytes != null) {
                Base64.encodeToString(bytes, Base64.DEFAULT)
            } else null
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Base64 conversion failed", e)
            null
        }
    }

    // --- Calling (Agora) ---

    fun startCall(receiver: User, type: String) {
        if (isSimulatedMode) {
            // Simulated Active Call
            val mockSession = CallSession(
                callId = "mock_call",
                callerId = "me",
                callerName = _currentUser.value?.name ?: "Me",
                callerAvatar = _currentUser.value?.avatarUrl ?: "",
                receiverId = receiver.uid,
                type = type,
                status = "ringing",
                timestamp = System.currentTimeMillis()
            )
            _currentCall.value = mockSession

            // Join Agora local dummy preview
            agoraManager.init("62afca34293945038c62aeb044ae1529")
            agoraManager.joinChannel("mock_call")

            viewModelScope.launch {
                kotlinx.coroutines.delay(3000) // Ringing transition to active
                _currentCall.value = mockSession.copy(status = "active")
            }
        } else {
            firebaseHelper.initiateCall(receiver.uid, type, { session ->
                _currentCall.value = session
                agoraManager.init("62afca34293945038c62aeb044ae1529")
                agoraManager.joinChannel(session.callId)
                listenToActiveCallStatus(session.callId)
            }, {
                Log.e("ChatViewModel", "Call creation failed: $it")
            })
        }
    }

    fun acceptIncomingCall(call: CallSession) {
        if (isSimulatedMode) {
            _currentCall.value = call.copy(status = "active")
        } else {
            firebaseHelper.acceptCall(call.callId)
            agoraManager.init("62afca34293945038c62aeb044ae1529")
            agoraManager.joinChannel(call.callId)
            _currentCall.value = call.copy(status = "active")
            listenToActiveCallStatus(call.callId)
        }
    }

    fun endCall() {
        val activeCall = _currentCall.value ?: return
        if (isSimulatedMode) {
            agoraManager.leaveChannel()
            _currentCall.value = null
            _remoteUserUid.value = null
        } else {
            firebaseHelper.endCall(activeCall.callId, activeCall.callerId, activeCall.receiverId)
            agoraManager.leaveChannel()
            _currentCall.value = null
            _remoteUserUid.value = null
            stopCallStatusListener()
        }
    }

    private fun listenToActiveCallStatus(callId: String) {
        stopCallStatusListener()
        activeCallSessionListener = firebaseHelper.listenToCallSession(callId) { session ->
            if (session == null || session.status == "ended") {
                agoraManager.leaveChannel()
                _currentCall.value = null
                _remoteUserUid.value = null
                stopCallStatusListener()
            } else {
                _currentCall.value = session
            }
        }
    }

    private fun stopCallStatusListener() {
        val activeCall = _currentCall.value
        if (activeCall != null && activeCallSessionListener != null) {
            firebaseHelper.removeCallSessionListener(activeCall.callId, activeCallSessionListener!!)
        }
        activeCallSessionListener = null
    }

    // --- Firebase Observers ---

    private fun startFirebaseListeners() {
        stopFirebaseListeners()
        friendsListener = firebaseHelper.listenToFriends { updatedFriends ->
            _friends.value = updatedFriends
        }
        requestsListener = firebaseHelper.listenToFriendRequests { updatedRequests ->
            val previousCount = _friendRequests.value.size
            _friendRequests.value = updatedRequests
            if (updatedRequests.size > previousCount) {
                playNotificationSound()
            }
        }
        callListener = firebaseHelper.listenToIncomingCalls { incomingCall ->
            if (incomingCall != null) {
                _currentCall.value = incomingCall
            } else {
                // Call was cancelled or ended by the other side before we joined/accepted!
                if (_currentCall.value?.status == "ringing") {
                    _currentCall.value = null
                    _remoteUserUid.value = null
                    agoraManager.leaveChannel()
                }
            }
        }
    }

    private fun stopFirebaseListeners() {
        friendsListener?.let { firebaseHelper.removeFriendsListener(it) }
        friendsListener = null
        requestsListener?.let { firebaseHelper.removeFriendRequestsListener(it) }
        requestsListener = null
        callListener?.let { firebaseHelper.removeIncomingCallsListener(it) }
        callListener = null
        stopChatListeners()
    }

    private fun stopChatListeners() {
        activeChatUserUid?.let { uid ->
            messagesListener?.let { listener ->
                firebaseHelper.removeMessagesListener(uid, listener)
            }
        }
        messagesListener = null
        activeChatUserUid = null
    }

    override fun onCleared() {
        super.onCleared()
        if (!isSimulatedMode && _currentUser.value != null) {
            firebaseHelper.setUserStatus("offline")
        }
        stopNetworkMonitoring()
        stopFirebaseListeners()
        agoraManager.destroy()
    }

    private fun startNetworkMonitoring() {
        try {
            connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d("ChatViewModel", "Internet onAvailable")
                    if (!isSimulatedMode && _currentUser.value != null) {
                        firebaseHelper.setUserStatus("online")
                    }
                }

                override fun onLost(network: Network) {
                    Log.d("ChatViewModel", "Internet onLost")
                    if (!isSimulatedMode && _currentUser.value != null) {
                        firebaseHelper.setUserStatus("offline")
                    }
                }
            }
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)

            // Set initial status
            val isConnected = isNetworkConnected()
            if (!isSimulatedMode && _currentUser.value != null) {
                firebaseHelper.setUserStatus(if (isConnected) "online" else "offline")
            }
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Error registering network callback", e)
        }
    }

    private fun stopNetworkMonitoring() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Failed to unregister network callback", e)
        }
    }

    fun isNetworkConnected(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    private fun recordCallLog(session: CallSession) {
        val durationMs = if (activeCallStartTime > 0L) {
            System.currentTimeMillis() - activeCallStartTime
        } else {
            0L
        }
        activeCallStartTime = 0L // Reset

        val durationStr = if (durationMs > 0L) {
            val minutes = (durationMs / 1000) / 60
            val seconds = (durationMs / 1000) % 60
            if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
        } else {
            null // Missed or unanswered
        }

        val logText = if (durationStr != null) {
            "${session.type.replaceFirstChar { it.uppercase() }} Call ($durationStr)"
        } else {
            if (session.callerId == _currentUser.value?.uid) {
                "Outgoing Call (No Answer)"
            } else {
                "Missed Call"
            }
        }

        val roomId = firebaseHelper.getChatRoomId(session.callerId, session.receiverId)
        val msgId = session.callId

        val message = Message(
            messageId = msgId,
            senderId = session.callerId,
            receiverId = session.receiverId,
            text = logText,
            imageUrl = "",
            timestamp = System.currentTimeMillis(),
            isRead = false,
            isCallLog = true,
            callType = session.type,
            callDuration = durationStr ?: ""
        )

        if (isSimulatedMode) {
            val friendId = if (session.callerId == "me") session.receiverId else session.callerId
            val list = simulatedChats[friendId] ?: mutableListOf()
            if (list.none { it.messageId == msgId }) {
                list.add(message)
                simulatedChats[friendId] = list
                _activeMessages.value = list.toList()
            }
        } else {
            firebaseHelper.saveCallLog(roomId, message)
        }
    }
}
