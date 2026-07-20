package com.simplecallapp.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.simplecallapp.R
import com.simplecallapp.ui.call.CallActivity
import com.simplecallapp.webrtc.WebRTCClient
import com.simplecallapp.webrtc.WebRTCSignaling
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import android.content.pm.ServiceInfo
import com.simplecallapp.data.model.CallHistory
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.auth.FirebaseAuth

class CallForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "CallServiceChannel"
        const val NOTIFICATION_ID = 1001
        
        const val EXTRA_CALLER = "caller"
        const val EXTRA_CALLEE = "callee"
        const val EXTRA_IS_INCOMING = "is_incoming"
        const val EXTRA_IS_VIDEO = "is_video"

        var activeInstance: CallForegroundService? = null
    }

    private val binder = CallBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val firestore = FirebaseFirestore.getInstance()
    
    private var myNumber = ""
    private var primaryCallerNumber = ""
    private var primaryCalleeNumber = ""
    private var isIncoming = false

    // Multi-peer teleconferencia
    data class ActivePeer(
        val phoneNumber: String,
        val webRTCClient: WebRTCClient,
        val isCaller: Boolean,
        var status: String, // "ringing", "connected"
        var hold: Boolean = false,
        var sessionListener: ListenerRegistration? = null,
        var candidatesListener: ListenerRegistration? = null
    )

    val activePeers = HashMap<String, ActivePeer>()
    var conferenceMode = false
    var organizerMode = false
    var participantToParticipantCallId: String? = null
    var secondaryCallListener: ListenerRegistration? = null

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState

    private val _participantsList = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val participantsList: StateFlow<List<Pair<String, String>>> = _participantsList

    private var durationJob: Job? = null
    private val _durationSeconds = MutableStateFlow(0)
    val durationSeconds: StateFlow<Int> = _durationSeconds

    private var audioFocusRequest: AudioFocusRequest? = null
    private var isDestroyed = false
    private var isMuted = false
    private var isSpeakerOn = false

    enum class CallState {
        Idle, RingingIncoming, RingingOutgoing, Connected, Ended
    }

    inner class CallBinder : Binder() {
        fun getService(): CallForegroundService = this@CallForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        createNotificationChannel()
        
        val prefs = getSharedPreferences("SimpleCallAppPrefs", Context.MODE_PRIVATE)
        myNumber = prefs.getString("user_number", "") ?: ""
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            primaryCallerNumber = intent.getStringExtra(EXTRA_CALLER) ?: ""
            primaryCalleeNumber = intent.getStringExtra(EXTRA_CALLEE) ?: ""
            isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, false)

            try {
                startForegroundServiceNotification()
            } catch (e: Exception) {
                stopSelf()
                return START_NOT_STICKY
            }

            if (isIncoming) {
                _callState.value = CallState.RingingIncoming
                acceptOrInitializeCall(primaryCallerNumber, isIncomingCall = true)
            } else {
                _callState.value = CallState.RingingOutgoing
                acceptOrInitializeCall(primaryCalleeNumber, isIncomingCall = false)
            }
        }
        return START_NOT_STICKY
    }

    private fun acceptOrInitializeCall(targetNumber: String, isIncomingCall: Boolean) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val client = WebRTCClient(this@CallForegroundService, object : WebRTCClient.Listener {
                    override fun onIceCandidateGathered(candidate: IceCandidate) {
                        val docId = if (isIncomingCall) myNumber else targetNumber
                        sendIceCandidate(docId, isCaller = !isIncomingCall, candidate = candidate)
                    }

                    override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
                        serviceScope.launch {
                            handleConnectionStateChange(targetNumber, state)
                        }
                    }
                })

                withContext(Dispatchers.Main) {
                    val peer = ActivePeer(
                        phoneNumber = targetNumber,
                        webRTCClient = client,
                        isCaller = !isIncomingCall,
                        status = "ringing",
                        hold = false
                    )
                    activePeers[targetNumber] = peer
                    updateParticipantsListFlow()

                    if (isIncomingCall) {
                        // El callee espera que el caller configure la oferta
                        listenPrimaryCall(myNumber, targetNumber, client, isCaller = false)
                    } else {
                        // El caller inicia la oferta
                        client.createOffer { offerDesc ->
                            serviceScope.launch {
                                val session = WebRTCSignaling.CallSession(
                                    callerNumber = myNumber,
                                    calleeNumber = targetNumber,
                                    status = "ringing",
                                    offerSdp = offerDesc.description,
                                    answerSdp = null
                                )
                                firestore.collection("calls").document(targetNumber).set(session)
                                
                                // Escuchar respuesta
                                listenPrimaryCall(targetNumber, targetNumber, client, isCaller = true)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    stopAllCalls()
                }
            }
        }
    }

    // Iniciar llamada a un segundo participante (A llama a C)
    fun initiateSecondaryCall(targetNumber: String) {
        if (targetNumber == myNumber || activePeers.containsKey(targetNumber)) return
        
        organizerMode = true
        _callState.value = CallState.Connected

        // Retener al primer participante
        val firstPeerNum = activePeers.keys.firstOrNull()
        if (firstPeerNum != null) {
            toggleParticipantHold(firstPeerNum, true)
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                val client = WebRTCClient(this@CallForegroundService, object : WebRTCClient.Listener {
                    override fun onIceCandidateGathered(candidate: IceCandidate) {
                        sendIceCandidate(targetNumber, isCaller = true, candidate = candidate)
                    }

                    override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
                        serviceScope.launch {
                            handleConnectionStateChange(targetNumber, state)
                        }
                    }
                })

                withContext(Dispatchers.Main) {
                    val peer = ActivePeer(
                        phoneNumber = targetNumber,
                        webRTCClient = client,
                        isCaller = true,
                        status = "ringing",
                        hold = false
                    )
                    activePeers[targetNumber] = peer
                    updateParticipantsListFlow()

                    client.createOffer { offerDesc ->
                        serviceScope.launch {
                            val session = WebRTCSignaling.CallSession(
                                callerNumber = myNumber,
                                calleeNumber = targetNumber,
                                status = "ringing",
                                offerSdp = offerDesc.description,
                                answerSdp = null
                            )
                            firestore.collection("calls").document(targetNumber).set(session)
                            listenPrimaryCall(targetNumber, targetNumber, client, isCaller = true)
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    fun answerIncomingCall() {
        if (callState.value != CallState.RingingIncoming) return
        _callState.value = CallState.Connected
        requestCallAudioFocus()
        startTimer()

        val peer = activePeers[primaryCallerNumber] ?: return
        firestore.collection("calls").document(myNumber).get().addOnSuccessListener { doc ->
            if (doc.exists()) {
                val offerSdp = doc.getString("offerSdp") ?: return@addOnSuccessListener
                peer.webRTCClient.setRemoteDescription(offerSdp, true) {
                    peer.webRTCClient.createAnswer { answerDesc ->
                        firestore.collection("calls").document(myNumber).update(
                            mapOf("status" to "answered", "answerSdp" to answerDesc.description)
                        )
                    }
                    listenRemoteCandidates(myNumber, listenForCaller = true, peer.webRTCClient)
                }
            }
        }
    }

    private fun listenPrimaryCall(docId: String, participantNumber: String, client: WebRTCClient, isCaller: Boolean) {
        var remoteDescriptionSet = false
        val sessionListener = firestore.collection("calls").document(docId)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                if (!snapshot.exists()) {
                    hangupPeer(participantNumber)
                    return@addSnapshotListener
                }

                val status = snapshot.getString("status") ?: ""
                val answerSdp = snapshot.getString("answerSdp")
                val hold = snapshot.getBoolean("hold") == true
                val holdBy = snapshot.getString("holdBy") ?: ""
                val conferenceWith = snapshot.getString("conferenceWith")

                if (status == "rejected" || status == "ended") {
                    hangupPeer(participantNumber)
                    return@addSnapshotListener
                }

                if (status == "answered" && answerSdp != null && !remoteDescriptionSet) {
                    remoteDescriptionSet = true
                    client.setRemoteDescription(answerSdp, false) {
                        listenRemoteCandidates(docId, listenForCaller = !isCaller, client)
                    }
                    val peer = activePeers[participantNumber]
                    if (peer != null) {
                        peer.status = "connected"
                    }
                    if (activePeers.size == 1 && _callState.value != CallState.Connected) {
                        _callState.value = CallState.Connected
                        requestCallAudioFocus()
                        startTimer()
                    }
                    updateParticipantsListFlow()
                }

                // Manejo de Hold (Retención) por la otra parte
                val peer = activePeers[participantNumber]
                if (peer != null) {
                    val isHeld = hold && holdBy != myNumber
                    if (peer.hold != isHeld) {
                        peer.hold = isHeld
                        peer.webRTCClient.setRemoteAudioEnabled(!isHeld)
                        updateParticipantsListFlow()
                    }

                    // Manejo de Conferencia entrante (para B o C)
                    if (conferenceWith != null && conferenceWith.isNotEmpty() && !organizerMode && !conferenceMode) {
                        conferenceMode = true
                        initiateParticipantToParticipantCall(conferenceWith)
                    }
                }
            }

        activePeers[participantNumber]?.sessionListener = sessionListener
    }

    private fun listenRemoteCandidates(docId: String, listenForCaller: Boolean, client: WebRTCClient) {
        val listener = firestore.collection("calls").document(docId).collection("candidates")
            .whereEqualTo("caller", listenForCaller)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                for (change in snapshot.documentChanges) {
                    if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        val d = change.document
                        val sdpMid = d.getString("sdpMid") ?: ""
                        val sdpMLineIndex = d.getLong("sdpMLineIndex")?.toInt() ?: 0
                        val sdp = d.getString("sdp") ?: ""
                        val candidate = org.webrtc.IceCandidate(sdpMid, sdpMLineIndex, sdp)
                        client.addRemoteIceCandidate(candidate)
                    }
                }
            }
        
        val key = activePeers.keys.firstOrNull { activePeers[it]?.webRTCClient == client }
        if (key != null) {
            activePeers[key]?.candidatesListener = listener
        }
    }

    private fun sendIceCandidate(docId: String, isCaller: Boolean, candidate: IceCandidate) {
        val data = hashMapOf(
            "sdpMid" to candidate.sdpMid,
            "sdpMLineIndex" to candidate.sdpMLineIndex,
            "sdp" to candidate.sdp,
            "caller" to isCaller
        )
        firestore.collection("calls").document(docId).collection("candidates").add(data)
    }

    // Iniciar conexión Mesh secundaria B <-> C
    private fun initiateParticipantToParticipantCall(otherNumber: String) {
        if (activePeers.containsKey(otherNumber)) return

        val isCaller = myNumber < otherNumber
        val callId = if (isCaller) "${myNumber}_${otherNumber}" else "${otherNumber}_${myNumber}"
        participantToParticipantCallId = callId

        serviceScope.launch(Dispatchers.IO) {
            try {
                val client = WebRTCClient(this@CallForegroundService, object : WebRTCClient.Listener {
                    override fun onIceCandidateGathered(candidate: IceCandidate) {
                        sendIceCandidate(callId, isCaller = isCaller, candidate = candidate)
                    }

                    override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
                        serviceScope.launch {
                            handleConnectionStateChange(otherNumber, state)
                        }
                    }
                })

                withContext(Dispatchers.Main) {
                    val peer = ActivePeer(
                        phoneNumber = otherNumber,
                        webRTCClient = client,
                        isCaller = isCaller,
                        status = "ringing",
                        hold = false
                    )
                    activePeers[otherNumber] = peer
                    updateParticipantsListFlow()

                    if (isCaller) {
                        client.createOffer { offerDesc ->
                            serviceScope.launch {
                                val session = WebRTCSignaling.CallSession(
                                    callerNumber = myNumber,
                                    calleeNumber = otherNumber,
                                    status = "ringing",
                                    offerSdp = offerDesc.description,
                                    answerSdp = null
                                )
                                firestore.collection("calls").document(callId).set(session)
                                
                                secondaryCallListener = firestore.collection("calls").document(callId)
                                    .addSnapshotListener { snap, err ->
                                        if (err != null || snap == null) return@addSnapshotListener
                                        if (!snap.exists()) {
                                            hangupPeer(otherNumber)
                                            return@addSnapshotListener
                                        }
                                        val stat = snap.getString("status") ?: ""
                                        val answerSdp = snap.getString("answerSdp")
                                        if (stat == "answered" && answerSdp != null) {
                                            client.setRemoteDescription(answerSdp, false) {
                                                listenRemoteCandidates(callId, listenForCaller = false, client)
                                            }
                                            activePeers[otherNumber]?.status = "connected"
                                            updateParticipantsListFlow()
                                        }
                                        if (stat == "ended") {
                                            hangupPeer(otherNumber)
                                        }
                                    }
                            }
                        }
                    } else {
                        secondaryCallListener = firestore.collection("calls").document(callId)
                            .addSnapshotListener { snap, err ->
                                if (err != null || snap == null) return@addSnapshotListener
                                if (!snap.exists()) {
                                    hangupPeer(otherNumber)
                                    return@addSnapshotListener
                                }
                                val stat = snap.getString("status") ?: ""
                                val offerSdp = snap.getString("offerSdp")
                                if (stat == "ringing" && offerSdp != null && client.isRemoteDescriptionSet == false) {
                                    client.setRemoteDescription(offerSdp, true) {
                                        listenRemoteCandidates(callId, listenForCaller = true, client)
                                        client.createAnswer { answerDesc ->
                                            firestore.collection("calls").document(callId).update(
                                                mapOf("status" to "answered", "answerSdp" to answerDesc.description)
                                            )
                                        }
                                        activePeers[otherNumber]?.status = "connected"
                                        updateParticipantsListFlow()
                                    }
                                }
                                if (stat == "ended") {
                                    hangupPeer(otherNumber)
                                }
                            }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    // Alternar retención del participante (Hold / Resume)
    fun toggleParticipantHold(targetNumber: String, forceHold: Boolean? = null) {
        val peer = activePeers[targetNumber] ?: return
        val targetHold = forceHold ?: !peer.hold

        peer.hold = targetHold
        peer.webRTCClient.setMicrophoneMute(targetHold || isMuted)
        peer.webRTCClient.setRemoteAudioEnabled(!targetHold)

        val docId = if (peer.isCaller) targetNumber else myNumber
        firestore.collection("calls").document(docId).update(
            mapOf("hold" to targetHold, "holdBy" to myNumber)
        )
        updateParticipantsListFlow()
    }

    // Fusionar llamadas en conferencia (A une B y C)
    fun mergeCalls() {
        val keys = activePeers.keys.toList()
        if (keys.size < 2) return

        val B = keys[0]
        val C = keys[1]

        // Quitar retención
        toggleParticipantHold(B, false)
        toggleParticipantHold(C, false)

        val docB = if (activePeers[B]?.isCaller == true) B else myNumber
        val docC = if (activePeers[C]?.isCaller == true) C else myNumber

        firestore.collection("calls").document(docB).update(
            mapOf("hold" to false, "conferenceWith" to C)
        )
        firestore.collection("calls").document(docC).update(
            mapOf("hold" to false, "conferenceWith" to B)
        )

        conferenceMode = true
        updateParticipantsListFlow()
    }

    // Alternar llamadas (Pone a uno en espera y reanuda al otro)
    fun swapCalls() {
        val keys = activePeers.keys.toList()
        if (keys.size < 2) return

        val B = keys[0]
        val C = keys[1]

        val bHold = activePeers[B]?.hold == true
        val cHold = activePeers[C]?.hold == true

        if (bHold == cHold) {
            toggleParticipantHold(B, true)
            toggleParticipantHold(C, false)
        } else {
            toggleParticipantHold(B, !bHold)
            toggleParticipantHold(C, !cHold)
        }
    }

    fun rejectOrHangup() {
        val keys = activePeers.keys.toList()
        keys.forEach { num ->
            hangupPeer(num)
        }
        stopAllCalls()
    }

    fun hangupPeer(targetNumber: String) {
        val peer = activePeers[targetNumber] ?: return
        
        peer.sessionListener?.remove()
        peer.candidatesListener?.remove()

        serviceScope.launch(Dispatchers.IO) {
            try {
                saveCallToHistory(targetNumber, peer.status == "connected")
            } catch (e: Exception) {}

            val docId = if (peer.isCaller) targetNumber else myNumber
            if (docId == targetNumber) {
                firestore.collection("calls").document(docId).delete()
                firestore.collection("calls").document(docId).collection("candidates").get()
                    .addOnSuccessListener { snap -> snap.forEach { it.reference.delete() } }
            } else {
                firestore.collection("calls").document(docId).update("status", "ended")
            }

            // Si hay llamada mesh activa, cerrarla
            if (participantToParticipantCallId != null && !organizerMode) {
                firestore.collection("calls").document(participantToParticipantCallId!!).update("status", "ended")
                participantToParticipantCallId = null
                withContext(Dispatchers.Main) {
                    secondaryCallListener?.remove()
                    secondaryCallListener = null
                }
            }

            withContext(Dispatchers.Main) {
                peer.webRTCClient.destroy()
                activePeers.remove(targetNumber)
                updateParticipantsListFlow()
                
                if (activePeers.isEmpty()) {
                    stopAllCalls()
                }
            }
        }
    }

    private fun handleConnectionStateChange(number: String, state: PeerConnection.PeerConnectionState) {
        if (state == PeerConnection.PeerConnectionState.CONNECTED) {
            activePeers[number]?.status = "connected"
            updateParticipantsListFlow()
        } else if (state == PeerConnection.PeerConnectionState.FAILED || 
                   state == PeerConnection.PeerConnectionState.DISCONNECTED || 
                   state == PeerConnection.PeerConnectionState.CLOSED) {
            hangupPeer(number)
        }
    }

    private fun updateParticipantsListFlow() {
        val list = activePeers.map { (num, peer) ->
            val statusText = if (peer.hold) "En espera" else (if (peer.status == "connected") "Conectado" else "Marcando...")
            Pair(num, statusText)
        }
        _participantsList.value = list
    }

    private fun requestCallAudioFocus() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { }
                    .build()
                audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
        } catch (e: Exception) {}
    }

    private fun abandonCallAudioFocus() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_NORMAL
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {}
    }

    private fun startTimer() {
        durationJob?.cancel()
        _durationSeconds.value = 0
        durationJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                _durationSeconds.value += 1
                updateNotificationText()
            }
        }
    }

    fun toggleSpeaker(on: Boolean) {
        isSpeakerOn = on
        activePeers.values.forEach { it.webRTCClient.setSpeakerphoneOn(on) }
    }

    fun toggleMute(mute: Boolean) {
        isMuted = mute
        activePeers.values.forEach { it.webRTCClient.setMicrophoneMute(mute || it.hold) }
    }

    private fun stopAllCalls() {
        if (_callState.value == CallState.Ended) return
        _callState.value = CallState.Ended
        durationJob?.cancel()
        abandonCallAudioFocus()
        
        secondaryCallListener?.remove()
        secondaryCallListener = null

        activePeers.values.forEach { peer ->
            peer.sessionListener?.remove()
            peer.candidatesListener?.remove()
            peer.webRTCClient.destroy()
        }
        activePeers.clear()
        updateParticipantsListFlow()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
    }

    private suspend fun saveCallToHistory(targetNumber: String, isConnected: Boolean) {
        try {
            val dbUserUid = FirebaseAuth.getInstance().currentUser?.uid
            if (dbUserUid != null) {
                val type = if (isIncoming) {
                    if (isConnected) "incoming" else "missed"
                } else {
                    "outgoing"
                }
                val duration = _durationSeconds.value.toLong()
                
                val userRepo = com.simplecallapp.data.repository.UserRepository()
                val otherUser = userRepo.getUserByNumber(targetNumber)
                val contactName = otherUser?.name ?: targetNumber

                val callHistoryRepo = com.simplecallapp.data.repository.CallHistoryRepository()
                callHistoryRepo.addCallRecord(
                    dbUserUid,
                    CallHistory(
                        phoneNumber = targetNumber,
                        contactName = contactName,
                        type = type,
                        timestamp = System.currentTimeMillis(),
                        duration = duration
                    )
                )
            }
        } catch (e: Exception) {}
    }

    private fun startForegroundServiceNotification() {
        val notification = buildCallNotification("Llamada VoIP", "Iniciando llamada...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotificationText() {
        val durationStr = String.format("%02d:%02d", _durationSeconds.value / 60, _durationSeconds.value % 60)
        val text = "Llamada activa • $durationStr"
        val notification = buildCallNotification("Llamada VoIP en Curso", text)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildCallNotification(title: String, text: String): Notification {
        val intent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_CALLER, primaryCallerNumber)
            putExtra(EXTRA_CALLEE, primaryCalleeNumber)
            putExtra(EXTRA_IS_INCOMING, isIncoming)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Llamadas VoIP de SimpleCallApp",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de llamadas en primer plano"
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        if (activeInstance == this) {
            activeInstance = null
        }
        if (!isDestroyed) {
            isDestroyed = true
            stopAllCalls()
        }
        serviceScope.cancel()
        super.onDestroy()
    }
}
