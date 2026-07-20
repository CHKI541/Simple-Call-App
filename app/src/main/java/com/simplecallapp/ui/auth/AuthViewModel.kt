package com.simplecallapp.ui.auth

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.AuthCredential
import com.simplecallapp.data.model.User
import com.simplecallapp.data.repository.AuthRepository
import com.simplecallapp.data.repository.UserRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class AuthViewModel : ViewModel() {
    private val authRepository = AuthRepository()
    private val userRepository = UserRepository()

    private val _authState = MutableLiveData<AuthState>(AuthState.Idle)
    val authState: LiveData<AuthState> = _authState

    private val _emailVerificationSent = MutableLiveData<Boolean>()
    val emailVerificationSent: LiveData<Boolean> = _emailVerificationSent

    val currentUserEmail: String?
        get() = authRepository.currentUser?.email

    sealed class AuthState {
        object Idle : AuthState()
        object Loading : AuthState()
        object Success : AuthState()
        data class Error(val message: String) : AuthState()
        object NeedsVerification : AuthState()
        object NeedsNumber : AuthState()
    }

    fun registerWithEmail(email: String, password: String) {
        _authState.value = AuthState.Loading
        viewModelScope.launch {
            try {
                val result = authRepository.registerWithEmail(email, password)
                val firebaseUser = result.user
                if (firebaseUser != null) {
                    // Enviar verificación de email obligatoria
                    firebaseUser.sendEmailVerification().await()
                    _emailVerificationSent.postValue(true)
                    
                    // Crear perfil inicial en Firestore
                    val newUser = User(
                        uid = firebaseUser.uid,
                        email = firebaseUser.email ?: "",
                        name = "",
                        number = "",
                        status = "offline"
                    )
                    userRepository.saveUserProfile(newUser)
                    
                    _authState.postValue(AuthState.NeedsVerification)
                } else {
                    _authState.postValue(AuthState.Error("Error al registrar: usuario nulo"))
                }
            } catch (e: Exception) {
                _authState.postValue(AuthState.Error(e.message ?: "Error desconocido"))
            }
        }
    }

    fun loginWithEmail(email: String, password: String) {
        _authState.value = AuthState.Loading
        viewModelScope.launch {
            try {
                val result = authRepository.loginWithEmail(email, password)
                val firebaseUser = result.user
                if (firebaseUser != null) {
                    firebaseUser.reload().await()
                    if (firebaseUser.isEmailVerified) {
                        checkUserRedirectState(firebaseUser.uid)
                    } else {
                        // Forzar el envío del correo de verificación para el inicio de sesión si no está verificado
                        try {
                            firebaseUser.sendEmailVerification().await()
                            _emailVerificationSent.postValue(true)
                        } catch (e: Exception) {
                            _emailVerificationSent.postValue(false)
                        }
                        _authState.postValue(AuthState.NeedsVerification)
                    }
                } else {
                    _authState.postValue(AuthState.Error("Error al iniciar sesión: usuario nulo"))
                }
            } catch (e: Exception) {
                _authState.postValue(AuthState.Error(e.message ?: "Error de credenciales"))
            }
        }
    }

    fun loginWithGoogleCredential(credential: AuthCredential) {
        _authState.value = AuthState.Loading
        viewModelScope.launch {
            try {
                val result = authRepository.loginWithCredential(credential)
                val firebaseUser = result.user
                if (firebaseUser != null) {
                    // Google Sign-In no requiere verificación de email
                    val profile = userRepository.getUserProfile(firebaseUser.uid)
                    if (profile == null) {
                        // Crear perfil inicial
                        val newUser = User(
                            uid = firebaseUser.uid,
                            email = firebaseUser.email ?: "",
                            name = firebaseUser.displayName ?: "",
                            number = "",
                            status = "offline"
                        )
                        userRepository.saveUserProfile(newUser)
                        _authState.postValue(AuthState.NeedsNumber)
                    } else if (profile.number.isEmpty()) {
                        _authState.postValue(AuthState.NeedsNumber)
                    } else {
                        _authState.postValue(AuthState.Success)
                    }
                } else {
                    _authState.postValue(AuthState.Error("Error de Google Auth: usuario nulo"))
                }
            } catch (e: Exception) {
                _authState.postValue(AuthState.Error(e.message ?: "Error desconocido en Google Sign-In"))
            }
        }
    }

    fun sendEmailVerification() {
        viewModelScope.launch {
            try {
                authRepository.sendEmailVerification()
                _emailVerificationSent.postValue(true)
            } catch (e: Exception) {
                _emailVerificationSent.postValue(false)
            }
        }
    }

    // Bug 1 Fix: recarga explícita mediante `.reload()` en FirebaseUser antes de verificar
    fun checkEmailVerificationAndProfile() {
        _authState.value = AuthState.Loading
        viewModelScope.launch {
            try {
                val user = authRepository.currentUser
                if (user != null) {
                    user.reload().await() // Forzar recarga explícita de Firebase Auth
                    if (user.isEmailVerified) {
                        checkUserRedirectState(user.uid)
                    } else {
                        _authState.postValue(AuthState.NeedsVerification)
                    }
                } else {
                    _authState.postValue(AuthState.Idle)
                }
            } catch (e: Exception) {
                _authState.postValue(AuthState.Error("Error al verificar estado: ${e.message}"))
            }
        }
    }

    private suspend fun checkUserRedirectState(uid: String) {
        val userProfile = userRepository.getUserProfile(uid)
        if (userProfile == null) {
            val user = authRepository.currentUser
            val newUser = User(
                uid = uid,
                email = user?.email ?: "",
                name = user?.displayName ?: "",
                number = "",
                status = "offline"
            )
            userRepository.saveUserProfile(newUser)
            _authState.postValue(AuthState.NeedsNumber)
        } else if (userProfile.number.isEmpty()) {
            _authState.postValue(AuthState.NeedsNumber)
        } else {
            _authState.postValue(AuthState.Success)
        }
    }

    fun saveUserNameAndNumber(name: String, number: String) {
        _authState.value = AuthState.Loading
        viewModelScope.launch {
            try {
                val uid = authRepository.currentUser?.uid
                if (uid != null) {
                    if (userRepository.isNumberTaken(number)) {
                        _authState.postValue(AuthState.Error("El número ya está registrado por otro usuario"))
                        return@launch
                    }
                    userRepository.updateUserNameAndNumber(uid, name, number)
                    _authState.postValue(AuthState.Success)
                } else {
                    _authState.postValue(AuthState.Error("No hay sesión activa"))
                }
            } catch (e: Exception) {
                _authState.postValue(AuthState.Error(e.message ?: "Error al guardar el número"))
            }
        }
    }

    fun signOut() {
        authRepository.signOut()
        _authState.value = AuthState.Idle
    }
}
