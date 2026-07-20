package com.simplecallapp

import android.app.Application
import android.content.Context
import android.content.Intent
import com.google.firebase.auth.FirebaseAuth
import com.simplecallapp.service.IncomingCallListenerService
import org.webrtc.PeerConnectionFactory

class SimpleCallApplication : Application() {

    companion object {
        private var webRTCInitialized = false

        fun initWebRTCIfNeeded(context: Context) {
            if (!webRTCInitialized) {
                try {
                    val options = PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                    PeerConnectionFactory.initialize(options)
                    webRTCInitialized = true
                } catch (e: Exception) {
                    // Ignorar si ya fue inicializado
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Inicializar WebRTC una sola vez a nivel de aplicación (evita crash en llamadas)
        initWebRTCIfNeeded(this)

        // Registrar token FCM para notificaciones push de llamadas de forma segura
        try {
            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser != null) {
                com.simplecallapp.service.FcmTokenManager.ensureTokenRegistered()
                val prefs = getSharedPreferences("SimpleCallAppPrefs", Context.MODE_PRIVATE)
                val myNumber = prefs.getString("user_number", "") ?: ""
                if (myNumber.isNotEmpty()) {
                    startListenerServiceSafe()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SimpleCallApp", "Error initializing Firebase in application onCreate", e)
        }
    }

    fun startListenerServiceSafe() {
        try {
            val serviceIntent = Intent(this, IncomingCallListenerService::class.java)
            startService(serviceIntent)
        } catch (e: Exception) {
            // Ignorar errores al iniciar servicio en segundo plano al arrancar
        }
    }
}
