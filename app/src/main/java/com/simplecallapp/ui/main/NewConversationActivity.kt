package com.simplecallapp.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.simplecallapp.R
import com.simplecallapp.adapter.ContactsAdapter
import com.simplecallapp.data.repository.ContactRepository
import com.simplecallapp.data.repository.UserRepository
import com.simplecallapp.databinding.ActivityNewConversationBinding
import com.simplecallapp.ui.chat.ChatActivity
import kotlinx.coroutines.launch

class NewConversationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNewConversationBinding
    private val contactRepo = ContactRepository()
    private val userRepo = UserRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNewConversationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        try {
            binding.toolbar.navigationIcon?.setTint(ContextCompat.getColor(this, R.color.text_white))
        } catch (e: Exception) {}
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.rvContacts.layoutManager = LinearLayoutManager(this)
        
        loadContacts()

        binding.btnStartChat.setOnClickListener {
            val targetNumber = binding.edtTargetNumber.text.toString().trim()
            if (targetNumber.isEmpty()) {
                Toast.makeText(this, "Ingresa un número", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startChatWithNumber(targetNumber)
        }
    }

    private fun loadContacts() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val list = contactRepo.getContacts(uid)
                binding.progressBar.visibility = View.GONE
                
                binding.rvContacts.adapter = ContactsAdapter(
                    contacts = list.toMutableList(),
                    onCallClick = { /* No usado aquí */ },
                    onChatClick = { contact ->
                        val intent = Intent(this@NewConversationActivity, ChatActivity::class.java).apply {
                            putExtra(ChatActivity.EXTRA_CHAT_NUMBER, contact.number)
                            putExtra(ChatActivity.EXTRA_CHAT_NAME, contact.name)
                        }
                        startActivity(intent)
                        finish()
                    }
                )
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@NewConversationActivity, "Error al cargar contactos", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startChatWithNumber(targetNumber: String) {
        val myNumber = getSharedPreferences("SimpleCallAppPrefs", Context.MODE_PRIVATE)
            .getString("user_number", "") ?: ""

        if (targetNumber == myNumber) {
            Toast.makeText(this, "No puedes chatear contigo mismo", Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val user = userRepo.getUserByNumber(targetNumber)
                binding.progressBar.visibility = View.GONE
                if (user != null) {
                    val intent = Intent(this@NewConversationActivity, ChatActivity::class.java).apply {
                        putExtra(ChatActivity.EXTRA_CHAT_NUMBER, user.number)
                        putExtra(ChatActivity.EXTRA_CHAT_NAME, user.name)
                    }
                    startActivity(intent)
                    finish()
                } else {
                    Toast.makeText(this@NewConversationActivity, "El número no está registrado en SimpleCallApp", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@NewConversationActivity, "Error al buscar el usuario", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
