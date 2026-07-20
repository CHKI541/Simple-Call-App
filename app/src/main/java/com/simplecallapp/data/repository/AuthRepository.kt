package com.simplecallapp.data.repository

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.tasks.await

class AuthRepository {
    private val firebaseAuth = FirebaseAuth.getInstance()

    val currentUser: FirebaseUser?
        get() = firebaseAuth.currentUser

    val isUserLoggedIn: Boolean
        get() = currentUser != null

    val isEmailVerified: Boolean
        get() {
            currentUser?.reload()
            return currentUser?.isEmailVerified ?: false
        }

    suspend fun loginWithEmail(email: String, password: String): AuthResult {
        return firebaseAuth.signInWithEmailAndPassword(email, password).await()
    }

    suspend fun registerWithEmail(email: String, password: String): AuthResult {
        return firebaseAuth.createUserWithEmailAndPassword(email, password).await()
    }

    suspend fun sendEmailVerification(): Void? {
        return firebaseAuth.currentUser?.sendEmailVerification()?.await()
    }

    suspend fun loginWithCredential(credential: AuthCredential): AuthResult {
        return firebaseAuth.signInWithCredential(credential).await()
    }

    fun reloadUser(): Task<Void>? {
        return firebaseAuth.currentUser?.reload()
    }

    fun signOut() {
        firebaseAuth.signOut()
    }
}
