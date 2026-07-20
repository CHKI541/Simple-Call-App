package com.simplecallapp.ui.main

import android.graphics.PorterDuff
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.simplecallapp.R
import com.simplecallapp.data.model.Contact
import com.simplecallapp.data.repository.ContactRepository
import com.simplecallapp.databinding.ActivityAddContactBinding
import kotlinx.coroutines.launch

class AddContactActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddContactBinding
    private val contactRepo = ContactRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddContactBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Configurar Toolbar
        setSupportActionBar(binding.toolbar)
        try {
            binding.toolbar.navigationIcon?.setTint(ContextCompat.getColor(this, R.color.text_white))
        } catch (e: Exception) {}
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        binding.btnSaveContact.setOnClickListener {
            saveContact()
        }
    }

    private fun saveContact() {
        val name = binding.edtContactName.text.toString().trim()
        val number = binding.edtContactNumber.text.toString().trim()
        val uid = FirebaseAuth.getInstance().currentUser?.uid

        if (name.isEmpty() || number.isEmpty()) {
            Toast.makeText(this, "Por favor completa todos los campos", Toast.LENGTH_SHORT).show()
            return
        }

        if (uid != null) {
            binding.btnSaveContact.isEnabled = false
            lifecycleScope.launch {
                try {
                    contactRepo.addContact(uid, Contact(name, number))
                    Toast.makeText(this@AddContactActivity, "Contacto guardado exitosamente", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                } catch (e: Exception) {
                    binding.btnSaveContact.isEnabled = true
                    Toast.makeText(this@AddContactActivity, "Error al guardar: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            Toast.makeText(this, "Error: No hay sesión activa", Toast.LENGTH_SHORT).show()
        }
    }
}
