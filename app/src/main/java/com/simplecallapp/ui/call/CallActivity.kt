package com.simplecallapp.ui.call

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.simplecallapp.R
import com.simplecallapp.databinding.ActivityCallBinding
import com.simplecallapp.service.CallForegroundService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallBinding
    private var callService: CallForegroundService? = null
    private var isBound = false

    private var isMuted = false
    private var isSpeakerOn = false
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CallForegroundService.CallBinder
            callService = binder.getService()
            isBound = true
            observeServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            callService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Sensor de proximidad
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (powerManager.isWakeLockLevelSupported(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            wakeLock = powerManager.newWakeLock(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "SimpleCallApp::ProximityWakeLock")
            wakeLock?.acquire()
        }

        // Obtener detalles del Intent - caller=quien llama, callee=quien recibe
        val callerNumber = intent.getStringExtra(CallForegroundService.EXTRA_CALLER) ?: ""
        val calleeNumber = intent.getStringExtra(CallForegroundService.EXTRA_CALLEE) ?: ""
        val isIncoming = intent.getBooleanExtra(CallForegroundService.EXTRA_IS_INCOMING, false)

        // Mostrar siempre el número del OTRO usuario
        val otherNumber = if (isIncoming) callerNumber else calleeNumber
        binding.txtCallName.text = otherNumber
        binding.txtCallNumber.text = otherNumber

        setupListeners()
        bindCallService(callerNumber, calleeNumber, isIncoming)
    }

    private fun bindCallService(caller: String, callee: String, isIncoming: Boolean) {
        val intent = Intent(this, CallForegroundService::class.java).apply {
            putExtra(CallForegroundService.EXTRA_CALLER, caller)
            putExtra(CallForegroundService.EXTRA_CALLEE, callee)
            putExtra(CallForegroundService.EXTRA_IS_INCOMING, isIncoming)
        }
        // Iniciar el servicio (si no está corriendo) en primer plano y luego bindear
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun setupListeners() {
        binding.btnAccept.setOnClickListener {
            callService?.answerIncomingCall()
        }

        binding.btnHangup.setOnClickListener {
            callService?.rejectOrHangup()
            finish()
        }

        binding.btnMute.setOnClickListener {
            isMuted = !isMuted
            callService?.toggleMute(isMuted)
            updateMuteButtonUI()
        }

        binding.btnSpeaker.setOnClickListener {
            isSpeakerOn = !isSpeakerOn
            callService?.toggleSpeaker(isSpeakerOn)
            updateSpeakerButtonUI()
            updateProximityWakeLock()
        }

        binding.btnMinimize.setOnClickListener {
            finish() // Cierra la pantalla pero mantiene el servicio activo
        }

        binding.btnAddParticipant.setOnClickListener {
            showAddParticipantDialog()
        }

        binding.btnMerge.setOnClickListener {
            callService?.mergeCalls()
        }

        binding.btnSwap.setOnClickListener {
            callService?.swapCalls()
        }
    }

    private fun showAddParticipantDialog() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            hint = "Número de participante"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Agregar Participante")
            .setMessage("Ingresa el número para sumarlo a la llamada en curso:")
            .setView(input)
            .setPositiveButton("Llamar") { _, _ ->
                val number = input.text.toString().trim()
                if (number.isNotEmpty()) {
                    callService?.initiateSecondaryCall(number)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun updateMuteButtonUI() {
        if (isMuted) {
            binding.btnMute.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#40F44336"))
            binding.btnMute.setColorFilter(Color.RED)
            Toast.makeText(this, "Micrófono silenciado", Toast.LENGTH_SHORT).show()
        } else {
            binding.btnMute.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#20FFFFFF"))
            binding.btnMute.setColorFilter(Color.WHITE)
            Toast.makeText(this, "Micrófono activo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateSpeakerButtonUI() {
        if (isSpeakerOn) {
            binding.btnSpeaker.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#402EA6DE"))
            binding.btnSpeaker.setColorFilter(Color.parseColor("#2EA6DE"))
            Toast.makeText(this, "Altavoz activado", Toast.LENGTH_SHORT).show()
        } else {
            binding.btnSpeaker.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#20FFFFFF"))
            binding.btnSpeaker.setColorFilter(Color.WHITE)
            Toast.makeText(this, "Altavoz desactivado", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeServiceState() {
        val service = callService ?: return

        lifecycleScope.launch {
            service.callState.collectLatest { state ->
                when (state) {
                    CallForegroundService.CallState.RingingIncoming -> {
                        binding.txtCallStatus.text = "Llamada Entrante..."
                        binding.btnAccept.visibility = View.VISIBLE
                        binding.layoutCallControls.visibility = View.GONE
                        binding.btnAddParticipant.visibility = View.GONE
                    }
                    CallForegroundService.CallState.RingingOutgoing -> {
                        binding.txtCallStatus.text = "Llamando..."
                        binding.btnAccept.visibility = View.GONE
                        binding.layoutCallControls.visibility = View.VISIBLE
                        binding.btnAddParticipant.visibility = View.GONE
                    }
                    CallForegroundService.CallState.Connected -> {
                        binding.txtCallStatus.text = "Llamada Conectada"
                        binding.btnAccept.visibility = View.GONE
                        binding.txtCallDuration.visibility = View.VISIBLE
                        binding.layoutCallControls.visibility = View.VISIBLE
                        // Habilitar botón para añadir miembro si es 1-a-1
                        if (service.activePeers.size == 1) {
                            binding.btnAddParticipant.visibility = View.VISIBLE
                        }
                    }
                    CallForegroundService.CallState.Ended -> {
                        binding.txtCallStatus.text = "Llamada Finalizada"
                        finish()
                    }
                    else -> {}
                }
            }
        }

        lifecycleScope.launch {
            service.durationSeconds.collectLatest { seconds ->
                val minutesStr = String.format("%02d:%02d", seconds / 60, seconds % 60)
                binding.txtCallDuration.text = minutesStr
            }
        }

        // Observar lista de participantes para la teleconferencia
        lifecycleScope.launch {
            service.participantsList.collectLatest { list ->
                if (list.size > 1) {
                    binding.layoutConference.visibility = View.VISIBLE
                    binding.btnAddParticipant.visibility = View.GONE // Límite de 3 alcanzado

                    binding.layoutConferenceParticipants.removeAllViews()
                    var hasHold = false
                    var hasActive = false

                    list.forEach { (number, statusText) ->
                        val row = android.widget.LinearLayout(this@CallActivity).apply {
                            orientation = android.widget.LinearLayout.HORIZONTAL
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                setMargins(0, 12, 0, 12)
                            }
                            gravity = android.view.Gravity.CENTER_VERTICAL

                            // Número
                            addView(android.widget.TextView(this@CallActivity).apply {
                                text = number
                                setTextColor(Color.WHITE)
                                textSize = 16f
                                layoutParams = android.widget.LinearLayout.LayoutParams(
                                    0,
                                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                                    1f
                                )
                            })

                            // Estado
                            addView(android.widget.TextView(this@CallActivity).apply {
                                text = statusText
                                textSize = 14f
                                val isHeld = statusText == "En espera"
                                val isConnected = statusText == "Conectado"
                                setTextColor(
                                    if (isHeld) Color.parseColor("#F59E0B")
                                    else if (isConnected) Color.parseColor("#31B752")
                                    else Color.GRAY
                                )
                                
                                if (isHeld) hasHold = true
                                else if (isConnected) hasActive = true
                            })

                            // Botón de Retener/Reanudar (solo organizador)
                            if (service.organizerMode) {
                                val isHeld = statusText == "En espera"
                                addView(android.widget.ImageButton(this@CallActivity).apply {
                                    layoutParams = android.widget.LinearLayout.LayoutParams(
                                        (40 * resources.displayMetrics.density).toInt(),
                                        (40 * resources.displayMetrics.density).toInt()
                                    ).apply {
                                        setMargins((16 * resources.displayMetrics.density).toInt(), 0, 0, 0)
                                    }
                                    setBackgroundResource(android.R.drawable.screen_background_dark_transparent)
                                    setImageResource(if (isHeld) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
                                    setColorFilter(Color.WHITE)
                                    setOnClickListener {
                                        service.toggleParticipantHold(number, !isHeld)
                                    }
                                })
                            }
                        }
                        binding.layoutConferenceParticipants.addView(row)
                    }

                    // Acciones del organizador
                    if (service.organizerMode) {
                        if (hasHold && hasActive) {
                            binding.btnMerge.visibility = View.VISIBLE
                            binding.btnSwap.visibility = View.VISIBLE
                        } else if (hasHold && !hasActive) {
                            binding.btnMerge.visibility = View.GONE
                            binding.btnSwap.visibility = View.VISIBLE
                        } else {
                            binding.btnMerge.visibility = View.GONE
                            binding.btnSwap.visibility = View.GONE
                        }
                    } else {
                        binding.btnMerge.visibility = View.GONE
                        binding.btnSwap.visibility = View.GONE
                    }
                } else {
                    binding.layoutConference.visibility = View.GONE
                    if (service.callState.value == CallForegroundService.CallState.Connected) {
                        binding.btnAddParticipant.visibility = View.VISIBLE
                    } else {
                        binding.btnAddParticipant.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun updateProximityWakeLock() {
        try {
            if (isSpeakerOn) {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            } else {
                if (wakeLock != null && !wakeLock!!.isHeld) {
                    wakeLock?.acquire()
                }
            }
        } catch (e: Exception) {}
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode
            if (keyCode == android.view.KeyEvent.KEYCODE_CALL || keyCode == android.view.KeyEvent.KEYCODE_HEADSETHOOK) {
                if (binding.btnAccept.visibility == android.view.View.VISIBLE) {
                    binding.btnAccept.performClick()
                    return true
                } else if (keyCode == android.view.KeyEvent.KEYCODE_HEADSETHOOK) {
                    binding.btnHangup.performClick()
                    return true
                }
            }
            if (keyCode == android.view.KeyEvent.KEYCODE_ENDCALL) {
                binding.btnHangup.performClick()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (e: Exception) {}
        }
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }
}
