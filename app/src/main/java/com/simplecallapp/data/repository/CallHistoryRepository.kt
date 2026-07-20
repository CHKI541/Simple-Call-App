package com.simplecallapp.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.simplecallapp.data.model.CallHistory
import kotlinx.coroutines.tasks.await

class CallHistoryRepository {
    private val firestore = FirebaseFirestore.getInstance()

    suspend fun addCallRecord(uid: String, record: CallHistory) {
        val userCallHistoryCollection = firestore.collection("users")
            .document(uid)
            .collection("call_history")
        
        val docRef = userCallHistoryCollection.document()
        val recordWithId = record.copy(id = docRef.id)
        docRef.set(recordWithId).await()
    }

    suspend fun getCallHistory(uid: String): List<CallHistory> {
        val query = firestore.collection("users")
            .document(uid)
            .collection("call_history")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .get()
            .await()
            
        return query.documents.mapNotNull { doc ->
            doc.toObject(CallHistory::class.java)
        }
    }

    suspend fun clearCallHistory(uid: String) {
        val query = firestore.collection("users")
            .document(uid)
            .collection("call_history")
            .get()
            .await()
            
        val batch = firestore.batch()
        for (doc in query.documents) {
            batch.delete(doc.reference)
        }
        batch.commit().await()
    }
}
