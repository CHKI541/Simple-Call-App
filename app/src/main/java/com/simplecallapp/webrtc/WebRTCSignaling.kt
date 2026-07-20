package com.simplecallapp.webrtc

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.simplecallapp.data.model.CallHistory
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import org.webrtc.IceCandidate

class WebRTCSignaling {
    private val firestore = FirebaseFirestore.getInstance()
    private val callsCollection = firestore.collection("calls")
    private val sentCandidates = HashSet<String>()

    data class CallSession(
        val callerNumber: String = "",
        val calleeNumber: String = "",
        val status: String = "", // "ringing", "answered", "rejected", "ended"
        val offerSdp: String? = null,
        val answerSdp: String? = null
    )

    data class SignaledCandidate(
        val sdpMid: String = "",
        val sdpMLineIndex: Int = 0,
        val sdp: String = "",
        val caller: Boolean = false
    )

    fun clearCandidateFilter() {
        sentCandidates.clear()
    }

    suspend fun initiateCall(caller: String, callee: String, offerSdp: String) {
        val session = CallSession(
            callerNumber = caller,
            calleeNumber = callee,
            status = "ringing",
            offerSdp = offerSdp,
            answerSdp = null
        )
        clearCandidateFilter()
        callsCollection.document(callee).set(session).await()
    }

    suspend fun answerCall(callee: String, answerSdp: String) {
        callsCollection.document(callee).update(
            mapOf(
                "status" to "answered",
                "answerSdp" to answerSdp
            )
        ).await()
    }

    suspend fun rejectCall(callee: String) {
        callsCollection.document(callee).update("status", "rejected").await()
    }

    suspend fun endCall(callee: String) {
        // Marcamos como finalizado
        callsCollection.document(callee).update("status", "ended").await()
    }

    suspend fun cleanUpSession(callee: String) {
        try {
            // Eliminar subcolección de candidatos
            val candidatesSnapshot = callsCollection.document(callee).collection("candidates").get().await()
            val batch = firestore.batch()
            for (doc in candidatesSnapshot.documents) {
                batch.delete(doc.reference)
            }
            batch.delete(callsCollection.document(callee))
            batch.commit().await()
        } catch (e: Exception) {
            // Ignorar errores en limpieza de Firebase
        }
    }

    suspend fun sendIceCandidate(callee: String, isCaller: Boolean, candidate: IceCandidate) {
        // Filtrar duplicados en memoria
        val candidateKey = "${candidate.sdpMid}_${candidate.sdpMLineIndex}_${candidate.sdp}"
        if (sentCandidates.contains(candidateKey)) {
            return
        }
        sentCandidates.add(candidateKey)

        val signaled = SignaledCandidate(
            sdpMid = candidate.sdpMid,
            sdpMLineIndex = candidate.sdpMLineIndex,
            sdp = candidate.sdp,
            caller = isCaller
        )

        callsCollection.document(callee)
            .collection("candidates")
            .document()
            .set(signaled)
            .await()
    }

    fun listenToCallSession(callee: String): Flow<CallSession?> = callbackFlow {
        val listener = callsCollection.document(callee)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    trySend(snapshot.toObject(CallSession::class.java))
                } else {
                    trySend(null)
                }
            }
        awaitClose { listener.remove() }
    }

    fun listenToCandidates(callee: String, listenForCallerCandidates: Boolean): Flow<IceCandidate> = callbackFlow {
        val query = callsCollection.document(callee)
            .collection("candidates")
            .whereEqualTo("caller", listenForCallerCandidates)

        val listener = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            if (snapshot != null) {
                // Notificar candidatos nuevos
                for (docChange in snapshot.documentChanges) {
                    if (docChange.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        val sigCandidate = docChange.document.toObject(SignaledCandidate::class.java)
                        val candidate = IceCandidate(
                            sigCandidate.sdpMid,
                            sigCandidate.sdpMLineIndex,
                            sigCandidate.sdp
                        )
                        trySend(candidate)
                    }
                }
            }
        }
        awaitClose { listener.remove() }
    }
}
