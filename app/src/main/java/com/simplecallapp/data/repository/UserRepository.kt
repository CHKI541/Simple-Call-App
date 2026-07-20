package com.simplecallapp.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.simplecallapp.data.model.User
import kotlinx.coroutines.tasks.await

class UserRepository {
    private val firestore = FirebaseFirestore.getInstance()
    private val usersCollection = firestore.collection("users")

    suspend fun saveUserProfile(user: User) {
        usersCollection.document(user.uid).set(user).await()
    }

    suspend fun getUserProfile(uid: String): User? {
        val doc = usersCollection.document(uid).get().await()
        return doc.toObject(User::class.java)
    }

    suspend fun isNumberTaken(number: String): Boolean {
        val query = usersCollection.whereEqualTo("number", number).get().await()
        return !query.isEmpty
    }

    suspend fun getUserByNumber(number: String): User? {
        val query = usersCollection.whereEqualTo("number", number).get().await()
        return if (!query.isEmpty) {
            query.documents.first().toObject(User::class.java)
        } else {
            null
        }
    }

    suspend fun updateUserStatus(uid: String, status: String) {
        usersCollection.document(uid).update("status", status).await()
    }

    suspend fun updateUserNameAndNumber(uid: String, name: String, number: String) {
        val updates = mapOf(
            "name" to name,
            "number" to number
        )
        usersCollection.document(uid).update(updates).await()
    }
}
