package com.simplecallapp.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.simplecallapp.data.model.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class ContactRepository {
    private val firestore = FirebaseFirestore.getInstance()
    private val usersCollection = firestore.collection("users")

    suspend fun getContacts(uid: String): List<Contact> = withContext(Dispatchers.IO) {
        try {
            val snapshot = usersCollection.document(uid).collection("contacts").get().await()
            snapshot.documents.mapNotNull { doc ->
                doc.toObject(Contact::class.java)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun addContact(uid: String, contact: Contact) = withContext(Dispatchers.IO) {
        try {
            usersCollection.document(uid)
                .collection("contacts")
                .document(contact.number)
                .set(contact)
                .await()
        } catch (e: Exception) {
            throw e
        }
    }

    suspend fun deleteContact(uid: String, contactNumber: String) = withContext(Dispatchers.IO) {
        try {
            usersCollection.document(uid)
                .collection("contacts")
                .document(contactNumber)
                .delete()
                .await()
        } catch (e: Exception) {
            throw e
        }
    }

    suspend fun updateContact(uid: String, oldNumber: String, newContact: Contact) = withContext(Dispatchers.IO) {
        try {
            // Delete old entry if number changed
            if (oldNumber != newContact.number) {
                usersCollection.document(uid)
                    .collection("contacts")
                    .document(oldNumber)
                    .delete()
                    .await()
            }
            usersCollection.document(uid)
                .collection("contacts")
                .document(newContact.number)
                .set(newContact)
                .await()
        } catch (e: Exception) {
            throw e
        }
    }
}
