package com.simplecallapp.ui.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.simplecallapp.R
import com.simplecallapp.adapter.MessageAdapter
import com.simplecallapp.data.repository.ChatRepository
import com.simplecallapp.databinding.ActivityChatBinding
import com.simplecallapp.service.CallForegroundService
import com.simplecallapp.ui.call.CallActivity
import com.simplecallapp.worker.SendMessageWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHAT_NUMBER = "chat_number"
        const val EXTRA_CHAT_NAME = "chat_name"
        var activeChatNumber = ""
    }

    private lateinit var binding: ActivityChatBinding
    private val chatRepository = ChatRepository()
    private var myNumber = ""
    private var targetNumber = ""
    private var targetName = ""
    private lateinit var messageAdapter: MessageAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("SimpleCallAppPrefs", Context.MODE_PRIVATE)
        val rawNumber = prefs.getString("user_number", "")
        myNumber = if (rawNumber.isNullOrEmpty() || rawNumber == "null") "" else rawNumber
        val rawTargetNumber = intent.getStringExtra(EXTRA_CHAT_NUMBER)
        targetNumber = if (rawTargetNumber.isNullOrEmpty()) "" else rawTargetNumber
        val rawTargetName = intent.getStringExtra(EXTRA_CHAT_NAME)
        targetName = if (rawTargetName.isNullOrEmpty()) "Usuario" else rawTargetName

        if (targetNumber.isEmpty()) {
            Toast.makeText(this, "Número de destino no válido", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (myNumber.isEmpty()) {
            Toast.makeText(this, "Error de sesión local. Vuelve a iniciar sesión.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (targetNumber == myNumber) {
            Toast.makeText(this, "No puedes chatear contigo mismo", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupToolbar()
        setupRecyclerView()
        val bgColorStr = prefs.getString("chat_bg_color", "#121212") ?: "#121212"
        try {
            binding.rvChatHistory.setBackgroundColor(android.graphics.Color.parseColor(bgColorStr))
        } catch (e: Exception) {}

        setupListeners()
        observeMessages()
    }

    override fun onResume() {
        super.onResume()
        activeChatNumber = targetNumber
    }

    override fun onPause() {
        super.onPause()
        activeChatNumber = ""
    }

    private fun setupToolbar() {
        binding.txtChatTitleName.text = targetName
        binding.txtChatTitleNumber.text = targetNumber

        binding.btnChatBack.setOnClickListener {
            finish()
        }

        binding.btnChatCall.setOnClickListener {
            val intent = Intent(this, CallActivity::class.java).apply {
                putExtra(CallForegroundService.EXTRA_CALLER, myNumber)
                putExtra(CallForegroundService.EXTRA_CALLEE, targetNumber)
                putExtra(CallForegroundService.EXTRA_IS_INCOMING, false)
            }
            startActivity(intent)
        }
    }

    private fun setupRecyclerView() {
        binding.rvChatHistory.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        val safeMyNumber = if (myNumber.isNullOrEmpty()) "" else myNumber
        val safeTargetNumber = if (targetNumber.isNullOrEmpty()) "" else targetNumber
        val roomId = chatRepository.getChatRoomId(safeMyNumber, safeTargetNumber)
        messageAdapter = MessageAdapter(safeMyNumber) { messageToDelete ->
            androidx.appcompat.app.AlertDialog.Builder(this@ChatActivity, R.style.Theme_SimpleCallApp)
                .setTitle("Eliminar mensaje")
                .setMessage("¿Quieres eliminar este mensaje solo para ti?")
                .setPositiveButton("Eliminar") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        chatRepository.deleteMessage(roomId, messageToDelete.id, myNumber)
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        binding.rvChatHistory.adapter = messageAdapter
    }

    private fun setupListeners() {
        binding.btnChatSend.setOnClickListener {
            val text = binding.edtChatMessage.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener

            sendMessageOffline(text)
            binding.edtChatMessage.setText("")
        }

        val prefs = getSharedPreferences("SimpleCallAppPrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("enter_to_send", false)) {
            binding.edtChatMessage.setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                    binding.btnChatSend.performClick()
                    return@setOnKeyListener true
                }
                false
            }
        }

        binding.btnChatSchedule.setOnClickListener {
            val text = binding.edtChatMessage.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Escribe un mensaje para programarlo", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showDateTimePickerDialog(text)
        }
    }

    private fun showDateTimePickerDialog(text: String) {
        val currentCalendar = Calendar.getInstance()
        val datePickerDialog = android.app.DatePickerDialog(
            this, R.style.Theme_SimpleCallApp,
            { _, year, month, dayOfMonth ->
                val timePickerDialog = android.app.TimePickerDialog(
                    this, R.style.Theme_SimpleCallApp,
                    { _, hourOfDay, minute ->
                        val selectedCalendar = Calendar.getInstance().apply {
                            set(Calendar.YEAR, year)
                            set(Calendar.MONTH, month)
                            set(Calendar.DAY_OF_MONTH, dayOfMonth)
                            set(Calendar.HOUR_OF_DAY, hourOfDay)
                            set(Calendar.MINUTE, minute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }

                        val delayMs = selectedCalendar.timeInMillis - System.currentTimeMillis()
                        if (delayMs <= 0) {
                            Toast.makeText(this, "Por favor selecciona una hora en el futuro", Toast.LENGTH_SHORT).show()
                        } else {
                            scheduleMessage(text, delayMs)
                            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                            Toast.makeText(this, "Mensaje programado para: ${sdf.format(selectedCalendar.time)}", Toast.LENGTH_LONG).show()
                            binding.edtChatMessage.setText("")
                        }
                    },
                    currentCalendar.get(Calendar.HOUR_OF_DAY),
                    currentCalendar.get(Calendar.MINUTE),
                    true
                )
                timePickerDialog.show()
            },
            currentCalendar.get(Calendar.YEAR),
            currentCalendar.get(Calendar.MONTH),
            currentCalendar.get(Calendar.DAY_OF_MONTH)
        )
        datePickerDialog.show()
    }

    private fun scheduleMessage(text: String, delayMs: Long) {
        val inputData = Data.Builder()
            .putString(SendMessageWorker.KEY_FROM_NUMBER, myNumber)
            .putString(SendMessageWorker.KEY_TO_NUMBER, targetNumber)
            .putString(SendMessageWorker.KEY_TEXT, text)
            .build()

        val sendWorkRequest = OneTimeWorkRequestBuilder<SendMessageWorker>()
            .setInputData(inputData)
            .setInitialDelay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(applicationContext).enqueue(sendWorkRequest)
    }

    private fun sendMessageOffline(text: String) {
        val inputData = Data.Builder()
            .putString(SendMessageWorker.KEY_FROM_NUMBER, myNumber)
            .putString(SendMessageWorker.KEY_TO_NUMBER, targetNumber)
            .putString(SendMessageWorker.KEY_TEXT, text)
            .build()

        val sendWorkRequest = OneTimeWorkRequestBuilder<SendMessageWorker>()
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(applicationContext).enqueue(sendWorkRequest)
    }

    private fun observeMessages() {
        lifecycleScope.launch(Dispatchers.Main) {
            chatRepository.listenConversation(myNumber, targetNumber)
                .catch { e ->
                    Toast.makeText(this@ChatActivity, "Error al cargar mensajes: ${e.message}", Toast.LENGTH_LONG).show()
                }
                .collectLatest { messageList ->
                    val roomId = chatRepository.getChatRoomId(myNumber, targetNumber)
                    val prefs = getSharedPreferences("SimpleCallAppPrefs", Context.MODE_PRIVATE)
                    val readReceiptsEnabled = prefs.getBoolean("read_receipts_enabled", true)

                    messageList.forEach { msg ->
                        if (msg.fromNumber == targetNumber) {
                            if (!msg.delivered) {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    chatRepository.markMessageDelivered(roomId, msg.id)
                                }
                            }
                            if (readReceiptsEnabled && !msg.read) {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    chatRepository.markMessageRead(roomId, msg.id)
                                }
                            }
                        }
                    }

                    messageAdapter.submitList(messageList)
                    if (messageList.isNotEmpty()) {
                        binding.rvChatHistory.smoothScrollToPosition(messageList.size - 1)
                    }
                }
        }
    }
}
