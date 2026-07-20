package com.simplecallapp.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.simplecallapp.data.model.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import com.google.firebase.firestore.FieldValue
import com.simplecallapp.data.model.ChatRoom

class ChatRepository {
    private val firestore = FirebaseFirestore.getInstance()
    private val chatsCollection = firestore.collection("chats")
    private val repositoryScope = CoroutineScope(Dispatchers.IO)

    fun getChatRoomId(number1: String, number2: String): String {
        return if (number1 < number2) {
            "${number1}_${number2}"
        } else {
            "${number2}_${number1}"
        }
    }

    suspend fun sendMessage(fromNumber: String, toNumber: String, text: String) {
        if (fromNumber.isEmpty() || toNumber.isEmpty()) {
            throw IllegalArgumentException("Los números de teléfono no pueden estar vacíos")
        }
        val roomId = getChatRoomId(fromNumber, toNumber)
        val messagesRef = chatsCollection.document(roomId).collection("messages")
        
        val docRef = messagesRef.document()
        val message = Message(
            id = docRef.id,
            fromNumber = fromNumber,
            toNumber = toNumber,
            text = text,
            timestamp = FieldValue.serverTimestamp(),
            delivered = false,
            read = false
        )
        
        docRef.set(message).await()
        
        // Actualizar o crear ChatRoom parent
        val chatRoomRef = chatsCollection.document(roomId)
        val chatRoom = ChatRoom(
            roomId = roomId,
            participants = listOf(fromNumber, toNumber),
            lastMessageText = text,
            lastMessageTimestamp = message.safeTimestamp,
            lastSender = fromNumber
        )
        chatRoomRef.set(chatRoom, com.google.firebase.firestore.SetOptions.merge()).await()
    }

    suspend fun markMessageDelivered(roomId: String, messageId: String) {
        try {
            chatsCollection.document(roomId).collection("messages").document(messageId)
                .update("delivered", true).await()
        } catch (e: Exception) {
            // Ignorar fallos de red
        }
    }

    suspend fun markMessageRead(roomId: String, messageId: String) {
        try {
            chatsCollection.document(roomId).collection("messages").document(messageId)
                .update("read", true).await()
        } catch (e: Exception) {
            // Ignorar fallos de red
        }
    }

    suspend fun deleteMessage(roomId: String, messageId: String, myNumber: String) {
        try {
            chatsCollection.document(roomId).collection("messages").document(messageId)
                .update("deletedBy", FieldValue.arrayUnion(myNumber)).await()
        } catch (e: Exception) {}
    }

    suspend fun deleteConversation(roomId: String, myNumber: String) {
        try {
            val messages = chatsCollection.document(roomId).collection("messages").get().await()
            val batch = firestore.batch()
            for (doc in messages.documents) {
                batch.update(doc.reference, "deletedBy", FieldValue.arrayUnion(myNumber))
            }
            batch.commit().await()
        } catch (e: Exception) {}
    }

    fun getConversations(myNumber: String): Flow<List<ChatRoom>> = callbackFlow {
        val query = chatsCollection.whereArrayContains("participants", myNumber)
        
        val listener = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            if (snapshot != null) {
                val rooms = snapshot.documents.mapNotNull { doc ->
                    doc.toObject(ChatRoom::class.java)
                }.sortedByDescending { it.lastMessageTimestamp }
                
                trySend(rooms)
            }
        }
        awaitClose { listener.remove() }
    }

    fun listenConversation(myNumber: String, otherNumber: String): Flow<List<Message>> = callbackFlow {
        val roomId = getChatRoomId(myNumber, otherNumber)
        val query = chatsCollection.document(roomId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)

        val listener = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }

            if (snapshot != null) {
                val messages = snapshot.documents.mapNotNull { doc ->
                    val msg = doc.toObject(Message::class.java)
                    if (msg != null && !msg.deletedBy.contains(myNumber)) {
                        msg
                    } else {
                        null
                    }
                }
                trySend(messages)
            }
        }

        awaitClose {
            listener.remove()
        }
    }
}
