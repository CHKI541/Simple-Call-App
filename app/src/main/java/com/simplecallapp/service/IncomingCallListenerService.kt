package com.simplecallapp.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.simplecallapp.R
import com.simplecallapp.ui.call.CallActivity
import com.simplecallapp.ui.chat.ChatActivity

class IncomingCallListenerService : Service() {

    companion object {
        const val LISTENER_CHANNEL_ID = "IncomingCallListenerChannel"
        const val CALL_INCOMING_CHANNEL_ID = "CallIncomingChannel"
        const val NOTIFICATION_ID = 2002
        const val INCOMING_CALL_NOTIF_ID = 2003
    }

    private var firestoreListener: ListenerRegistration? = null
    private var messagesListener: ListenerRegistration? = null
    private val chatListeners = HashMap<String, ListenerRegistration>()
    private var myNumber = ""
    private var ringtone: android.media.Ringtone? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("SimpleCallAppPrefs", Context.MODE_PRIVATE)
        myNumber = prefs.getString("user_number", "") ?: ""

        if (myNumber.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Iniciar escucha en Firestore (en segundo plano sin notificación permanente)
        startListeningCalls()
        startListeningMessages()

        return START_STICKY
    }

    private fun startListeningCalls() {
        firestoreListener?.remove()
        
        val db = FirebaseFirestore.getInstance()
        firestoreListener = db.collection("calls")
            .document(myNumber)
            .addSnapshotListener { snapshot, error ->
                android.util.Log.d("IncomingCallService", "Snapshot received for number $myNumber. Error: ${error?.message}")
                if (error != null) {
                    android.util.Log.e("IncomingCallService", "Listener error: ", error)
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val status = snapshot.getString("status") ?: ""
                    val callerNumber = snapshot.getString("callerNumber") ?: ""
                    android.util.Log.d("IncomingCallService", "Call status: $status, caller: $callerNumber")
                    val prefs = getSharedPreferences("SimpleCallAppPrefs", Context.MODE_PRIVATE)
                    val blockedStr = prefs.getString("blocked_numbers", "") ?: ""
                    val blockedList = if (blockedStr.isNotEmpty()) blockedStr.split(",") else emptyList()

                    if (status == "ringing" && callerNumber.isNotEmpty()) {
                        if (blockedList.contains(callerNumber)) {
                            android.util.Log.d("IncomingCallService", "Caller $callerNumber is blocked, rejecting call.")
                            // Rechazar llamada silenciosamente (simulamos colgar)
                            db.collection("calls").document(myNumber).update("status", "ended")
                            return@addSnapshotListener
                        }
                        android.util.Log.d("IncomingCallService", "Showing incoming call notification for caller $callerNumber")
                        showIncomingCallNotification(callerNumber)
                        playIncomingCallSound()
                    } else {
                        android.util.Log.d("IncomingCallService", "Stopping sound and cancelling notification (status: $status)")
                        stopIncomingCallSound()
                        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        notificationManager.cancel(INCOMING_CALL_NOTIF_ID)
                    }
                } else {
                    android.util.Log.d("IncomingCallService", "Snapshot is null or does not exist, stopping sound and cancelling notification.")
                    stopIncomingCallSound()
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.cancel(INCOMING_CALL_NOTIF_ID)
                }
            }
    }

    private fun startListeningMessages() {
        messagesListener?.remove()
        
        // Limpiar listeners individuales previos
        chatListeners.values.forEach { it.remove() }
        chatListeners.clear()

        val db = FirebaseFirestore.getInstance()
        try {
            // Escuchar cambios en las salas donde participa el usuario
            messagesListener = db.collection("chats")
                .whereArrayContains("participants", myNumber)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) return@addSnapshotListener
                    if (snapshot != null) {
                        val currentRoomIds = snapshot.documents.map { it.id }.toSet()
                        
                        // Remover listeners de salas que ya no están activas
                        val iterator = chatListeners.iterator()
                        while (iterator.hasNext()) {
                            val entry = iterator.next()
                            if (!currentRoomIds.contains(entry.key)) {
                                entry.value.remove()
                                iterator.remove()
                            }
                        }

                        // Agregar listeners para nuevas salas
                        for (roomId in currentRoomIds) {
                            if (!chatListeners.containsKey(roomId)) {
                                val listener = db.collection("chats").document(roomId)
                                    .collection("messages")
                                    .whereEqualTo("toNumber", myNumber)
                                    .whereEqualTo("delivered", false)
                                    .addSnapshotListener { msgSnapshot, msgError ->
                                        if (msgError != null) return@addSnapshotListener
                                        if (msgSnapshot != null) {
                                            val prefs = getSharedPreferences("SimpleCallAppPrefs", Context.MODE_PRIVATE)
                                            val isMessageNotifEnabled = prefs.getBoolean("notif_messages_enabled", true)
                                            val blockedStr = prefs.getString("blocked_numbers", "") ?: ""
                                            val blockedList = if (blockedStr.isNotEmpty()) blockedStr.split(",") else emptyList()

                                            msgSnapshot.documentChanges.forEach { change ->
                                                if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                                                    val msg = change.document.toObject(com.simplecallapp.data.model.Message::class.java)
                                                    if (msg.id.isNotEmpty() && msg.fromNumber != myNumber) {
                                                        // Marcar como entregado
                                                        db.collection("chats").document(roomId)
                                                            .collection("messages").document(msg.id)
                                                            .update("delivered", true)

                                                        if (ChatActivity.activeChatNumber != msg.fromNumber && !blockedList.contains(msg.fromNumber)) {
                                                            val isContactMuted = prefs.getBoolean("silenced_contact_${msg.fromNumber}", false)
                                                            if (isMessageNotifEnabled && !isContactMuted) {
                                                                showMessageNotification(msg)
                                                                playMessageNotificationSound()
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                chatListeners[roomId] = listener
                            }
                        }
                    }
                }
        } catch (e: Exception) {}
    }

    private fun getChatRoomId(number1: String, number2: String): String {
        return if (number1 < number2) "${number1}_${number2}" else "${number2}_${number1}"
    }

    private fun playIncomingCallSound() {
        try {
            stopIncomingCallSound()
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
            // Ignorar
        }
    }

    private fun stopIncomingCallSound() {
        try {
            ringtone?.stop()
            ringtone = null
        } catch (e: Exception) {
            // Ignorar
        }
    }

    private fun showIncomingCallNotification(callerNumber: String) {
        val intent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(CallForegroundService.EXTRA_CALLER, callerNumber)
            putExtra(CallForegroundService.EXTRA_CALLEE, myNumber)
            putExtra(CallForegroundService.EXTRA_IS_INCOMING, true)
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

    private fun showMessageNotification(msg: com.simplecallapp.data.model.Message) {
        val intent = Intent(this, ChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ChatActivity.EXTRA_CHAT_NUMBER, msg.fromNumber)
            putExtra(ChatActivity.EXTRA_CHAT_NAME, msg.fromNumber)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, msg.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CALL_INCOMING_CHANNEL_ID)
            .setContentTitle("Mensaje de ${msg.fromNumber}")
            .setContentText(msg.text)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(msg.id.hashCode(), notification)
    }

    private fun playMessageNotificationSound() {
        try {
            val prefs = getSharedPreferences("SimpleCallAppPrefs", Context.MODE_PRIVATE)
            val ringtoneUriStr = prefs.getString("notif_messages_ringtone", null)
            val ringtoneUri = if (ringtoneUriStr != null) {
                Uri.parse(ringtoneUriStr)
            } else {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }
            
            val r = RingtoneManager.getRingtone(applicationContext, ringtoneUri)
            r?.play()
        } catch (e: Exception) {}
    }

    private fun startForegroundNotification() {
        val intent = Intent(this, com.simplecallapp.ui.main.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, LISTENER_CHANNEL_ID)
            .setContentTitle("SimpleCallApp Activo")
            .setContentText("Esperando llamadas entrantes...")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(false) // Permite que el usuario pueda deslizar y quitar la notificación
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Canal para el servicio permanente — IMPORTANCE_MIN = sin icono en status bar
            val listenerChannel = NotificationChannel(
                LISTENER_CHANNEL_ID,
                "Servicio de SimpleCallApp",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Mantiene la aplicación activa para recibir llamadas"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
            notificationManager.createNotificationChannel(listenerChannel)

            // Canal para llamadas entrantes
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

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        firestoreListener?.remove()
        messagesListener?.remove()
        chatListeners.values.forEach { it.remove() }
        chatListeners.clear()
        stopIncomingCallSound()
        super.onDestroy()
    }
}
