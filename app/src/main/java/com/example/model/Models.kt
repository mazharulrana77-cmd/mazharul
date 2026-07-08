package com.example.model

data class User(
    val uid: String = "",
    val name: String = "",
    val email: String = "",
    val avatarUrl: String = "",
    val status: String = "offline", // online, offline
    val currentCallId: String = ""  // active call ID if any
)

data class FriendRequest(
    val id: String = "",
    val senderUid: String = "",
    val senderName: String = "",
    val senderAvatar: String = "",
    val receiverUid: String = "",
    val status: String = "pending"  // pending, accepted, rejected
)

data class Message(
    val messageId: String = "",
    val senderId: String = "",
    val receiverId: String = "",
    val text: String = "",
    val imageUrl: String = "",
    val timestamp: Long = 0L,
    val isRead: Boolean = false,
    val isCallLog: Boolean = false,
    val callType: String = "",
    val callDuration: String = ""
)

data class CallSession(
    val callId: String = "",
    val callerId: String = "",
    val callerName: String = "",
    val callerAvatar: String = "",
    val receiverId: String = "",
    val type: String = "video", // audio, video
    val status: String = "ringing", // ringing, active, ended
    val timestamp: Long = 0L
)

data class FirebaseConfig(
    val apiKey: String = "",
    val appId: String = "",
    val projectId: String = "",
    val databaseUrl: String = ""
)
