package com.example.network

import android.content.Context
import android.view.SurfaceView
import android.util.Log
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.video.VideoCanvas

class AgoraManager(private val context: Context) {
    private var rtcEngine: RtcEngine? = null
    var onRemoteUserJoined: ((Int) -> Unit)? = null
    var onRemoteUserLeft: ((Int) -> Unit)? = null
    var onJoinChannelSuccess: ((String, Int) -> Unit)? = null

    private val mRtcEventHandler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            Log.d("AgoraManager", "onJoinChannelSuccess: $channel, uid: $uid")
            onJoinChannelSuccess?.invoke(channel ?: "", uid)
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            Log.d("AgoraManager", "onUserJoined: $uid")
            onRemoteUserJoined?.invoke(uid)
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            Log.d("AgoraManager", "onUserOffline: $uid, reason: $reason")
            onRemoteUserLeft?.invoke(uid)
        }
    }

    fun init(appId: String): Boolean {
        if (rtcEngine != null) return true
        if (appId.trim().isEmpty()) return false
        try {
            val config = RtcEngineConfig()
            config.mContext = context.applicationContext
            config.mAppId = appId.trim()
            config.mEventHandler = mRtcEventHandler
            rtcEngine = RtcEngine.create(config)
            
            // Explicitly enable both audio and video
            rtcEngine?.enableAudio()
            rtcEngine?.enableVideo()
            
            // Route sound to speakerphone by default for clear hands-free call audio
            rtcEngine?.setEnableSpeakerphone(true)
            
            // Configure standard real-time communication profile for 1-to-1 low-latency calls
            rtcEngine?.setChannelProfile(io.agora.rtc2.Constants.CHANNEL_PROFILE_COMMUNICATION)
            
            rtcEngine?.startPreview()
            return true
        } catch (e: Exception) {
            Log.e("AgoraManager", "Initialization failed", e)
            return false
        }
    }

    fun joinChannel(channelId: String, token: String? = null, uid: Int = 0): Boolean {
        val engine = rtcEngine ?: return false
        try {
            engine.joinChannel(token, channelId, "", uid)
            return true
        } catch (e: Exception) {
            Log.e("AgoraManager", "Failed to join channel", e)
            return false
        }
    }

    fun setupLocalVideo(container: SurfaceView) {
        val engine = rtcEngine ?: return
        try {
            engine.setupLocalVideo(VideoCanvas(container, VideoCanvas.RENDER_MODE_HIDDEN, 0))
        } catch (e: Exception) {
            Log.e("AgoraManager", "setupLocalVideo failed", e)
        }
    }

    fun setupRemoteVideo(container: SurfaceView, uid: Int) {
        val engine = rtcEngine ?: return
        try {
            engine.setupRemoteVideo(VideoCanvas(container, VideoCanvas.RENDER_MODE_HIDDEN, uid))
        } catch (e: Exception) {
            Log.e("AgoraManager", "setupRemoteVideo failed", e)
        }
    }

    fun muteLocalAudio(muted: Boolean) {
        rtcEngine?.muteLocalAudioStream(muted)
    }

    fun muteLocalVideo(muted: Boolean) {
        rtcEngine?.muteLocalVideoStream(muted)
    }

    fun switchCamera() {
        rtcEngine?.switchCamera()
    }

    fun leaveChannel() {
        try {
            rtcEngine?.leaveChannel()
        } catch (e: Exception) {
            Log.e("AgoraManager", "leaveChannel failed", e)
        }
    }

    fun destroy() {
        leaveChannel()
        try {
            RtcEngine.destroy()
        } catch (e: Exception) {
            Log.e("AgoraManager", "destroy failed", e)
        }
        rtcEngine = null
    }
}
