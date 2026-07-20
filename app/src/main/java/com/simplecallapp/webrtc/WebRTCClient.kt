package com.simplecallapp.webrtc

import android.content.Context
import android.media.AudioManager
import com.simplecallapp.SimpleCallApplication
import org.webrtc.*
import java.util.ArrayList

class WebRTCClient(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onIceCandidateGathered(candidate: IceCandidate)
        fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState)
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private val remoteCandidatesQueue = ArrayList<IceCandidate>()
    var isRemoteDescriptionSet = false
    private var isDestroyed = false

    init {
        try {
            // WebRTC ya fue inicializado en SimpleCallApplication — solo crear factory
            SimpleCallApplication.initWebRTCIfNeeded(context)
            createPeerConnectionFactory()
            createPeerConnection()
        } catch (e: Exception) {
            // Notificar fallo de conexión para que la UI pueda reaccionar
        }
    }

    private fun createPeerConnectionFactory() {
        val audioDeviceModule = org.webrtc.audio.JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
        // NOTE: audioDeviceModule is intentionally NOT released here.
        // Releasing it immediately would tear down the audio pipeline before it is used.
    }

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:openrelay.metered.ca:80").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80?transport=udp")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null && !isDestroyed) {
                    listener.onIceCandidateGathered(candidate)
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                if (newState != null && !isDestroyed) {
                    listener.onConnectionStateChanged(newState)
                }
            }
        })

        setupLocalAudio()
    }

    private fun setupLocalAudio() {
        try {
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            }
            audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
            localAudioTrack = peerConnectionFactory?.createAudioTrack("ARDAMSa0", audioSource)
            localAudioTrack?.setEnabled(true)
            peerConnection?.addTrack(localAudioTrack, listOf("ARDAMS"))
        } catch (e: Exception) {
            // Ignorar si el audio no se pudo configurar
        }
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        if (isDestroyed) return
        if (isRemoteDescriptionSet) {
            try { peerConnection?.addIceCandidate(candidate) } catch (e: Exception) {}
        } else {
            synchronized(remoteCandidatesQueue) {
                remoteCandidatesQueue.add(candidate)
            }
        }
    }

    fun createOffer(callback: (SessionDescription) -> Unit) {
        if (isDestroyed) return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null && !isDestroyed) {
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() { callback(desc) }
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) {}
                    }, desc)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    fun createAnswer(callback: (SessionDescription) -> Unit) {
        if (isDestroyed) return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null && !isDestroyed) {
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() { callback(desc) }
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) {}
                    }, desc)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    fun setRemoteDescription(sdp: String, isOffer: Boolean, onComplete: () -> Unit = {}) {
        if (isDestroyed) return
        val type = if (isOffer) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
        val sessionDesc = SessionDescription(type, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                isRemoteDescriptionSet = true
                drainQueuedCandidates()
                onComplete()
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(error: String?) {}
        }, sessionDesc)
    }

    private fun drainQueuedCandidates() {
        synchronized(remoteCandidatesQueue) {
            for (candidate in remoteCandidatesQueue) {
                try { peerConnection?.addIceCandidate(candidate) } catch (e: Exception) {}
            }
            remoteCandidatesQueue.clear()
        }
    }

    @Suppress("DEPRECATION")
    fun setSpeakerphoneOn(on: Boolean) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = on
        } catch (e: Exception) {}
    }

    fun setMicrophoneMute(mute: Boolean) {
        localAudioTrack?.setEnabled(!mute)
    }

    fun setRemoteAudioEnabled(enabled: Boolean) {
        try {
            peerConnection?.receivers?.forEach { receiver ->
                val track = receiver.track()
                if (track is AudioTrack) {
                    track.setEnabled(enabled)
                }
            }
        } catch (e: Exception) {}
    }

    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        try {
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
                audioManager.mode = AudioManager.MODE_NORMAL
            } catch (e: Exception) {}
            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null
            localAudioTrack?.dispose()
            localAudioTrack = null
            audioSource?.dispose()
            audioSource = null
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null
            synchronized(remoteCandidatesQueue) { remoteCandidatesQueue.clear() }
        } catch (e: Exception) {
            // Ignorar errores al destruir
        }
    }
}
