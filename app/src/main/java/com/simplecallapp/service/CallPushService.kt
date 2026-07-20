package com.simplecallapp.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.simplecallapp.ui.call.CallActivity

class CallPushService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "CallPushService"
        const val CALL_INCOMING_CHANNEL_ID = "CallIncomingChannel"
        const val INCOMING_CALL_NOTIF_ID = 2003
        private var ringtone: Ringtone? = null

        fun stopRingtone() {
            try {
                ringtone?.stop()
                ringtone = null
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping ringtone", e)
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM Token refreshed: $token")
        FcmTokenManager.updateFcmToken(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "FCM Message received from: ${remoteMessage.from}")

        val data = remoteMessage.data
        val type = data["type"] ?: ""

        if (type == "incoming_call" || type == "ringing") {
            val callerNumber = data["callerNumber"] ?: ""
            val isVideoStr = data["isVideo"] ?: "false"
            val isVideo = isVideoStr.toBoolean()

            if (callerNumber.isNotEmpty()) {
                val prefs = getSharedPreferences("SimpleCallAppPrefs", Context.MODE_PRIVATE)
                val blockedStr = prefs.getString("blocked_numbers", "") ?: ""
                val blockedList = if (blockedStr.isNotEmpty()) blockedStr.split(",") else emptyList()

                if (blockedList.contains(callerNumber)) {
                    Log.d(TAG, "Ignored call from blocked number: $callerNumber")
                    return
                }

                val myNumber = prefs.getString("user_number", "") ?: ""
                triggerIncomingCall(callerNumber, myNumber, isVideo)
            }
        } else if (type == "call_ended") {
            stopRingtone()
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(INCOMING_CALL_NOTIF_ID)
        }
    }

    private fun triggerIncomingCall(callerNumber: String, myNumber: String, isVideo: Boolean) {
        createNotificationChannel()

        // 1. Play ringtone
        playRingtone()

        // 2. Launch CallActivity directly via PendingIntent fullScreenIntent
        val intent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(CallForegroundService.EXTRA_CALLER, callerNumber)
            putExtra(CallForegroundService.EXTRA_CALLEE, myNumber)
            putExtra(CallForegroundService.EXTRA_IS_INCOMING, true)
            putExtra(CallForegroundService.EXTRA_IS_VIDEO, isVideo)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 110, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CALL_INCOMING_CHANNEL_ID)
            .setContentTitle("Llamada Entrante")
            .setContentText("Llamada de $callerNumber")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOngoing(true)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(INCOMING_CALL_NOTIF_ID, notification)
    }

    private fun playRingtone() {
        try {
            stopRingtone()
            val prefs = getSharedPreferences("SimpleCallAppPrefs", Context.MODE_PRIVATE)
            val ringtoneUriStr = prefs.getString("notif_calls_ringtone", null)
            val ringtoneUri = if (ringtoneUriStr != null) {
                Uri.parse(ringtoneUriStr)
            } else {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }

            ringtone = RingtoneManager.getRingtone(applicationContext, ringtoneUri)

            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
            val userVolumePercent = prefs.getInt("call_volume", 100)
            val targetVolume = (maxVolume * (userVolumePercent / 100.0)).toInt()

            audioManager.setStreamVolume(AudioManager.STREAM_RING, targetVolume, 0)
            ringtone?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing ringtone", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val callChannel = NotificationChannel(
                CALL_INCOMING_CHANNEL_ID,
                "Llamadas Entrantes",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones emergentes de llamadas entrantes"
                setSound(null, null)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(callChannel)
        }
    }
}
