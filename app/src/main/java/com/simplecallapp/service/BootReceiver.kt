package com.simplecallapp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.firebase.auth.FirebaseAuth

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser != null) {
                val prefs = context.getSharedPreferences("SimpleCallAppPrefs", Context.MODE_PRIVATE)
                val myNumber = prefs.getString("user_number", "") ?: ""
                if (myNumber.isNotEmpty()) {
                    try {
                        val serviceIntent = Intent(context, IncomingCallListenerService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    } catch (e: Exception) {
                        // Ignorar errores al arrancar
                    }
                }
            }
        }
    }
}
