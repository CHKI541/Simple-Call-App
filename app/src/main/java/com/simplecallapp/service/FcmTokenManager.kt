package com.simplecallapp.service

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging

object FcmTokenManager {

    private const val TAG = "FcmTokenManager"

    fun updateFcmToken(token: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (token.isEmpty()) return

        FirebaseFirestore.getInstance().collection("users").document(uid)
            .update("fcmToken", token)
            .addOnSuccessListener {
                Log.d(TAG, "FCM Token updated successfully in Firestore for user $uid")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to update FCM Token in Firestore", e)
            }
    }

    fun ensureTokenRegistered() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.w(TAG, "Fetching FCM registration token failed", task.exception)
                    return@addOnCompleteListener
                }
                val token = task.result ?: ""
                Log.d(TAG, "Current FCM Token: $token")
                updateFcmToken(token)
            }
    }
}
