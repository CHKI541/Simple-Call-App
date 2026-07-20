package com.simplecallapp.ui.auth

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.simplecallapp.databinding.ActivityChooseNumberBinding
import com.simplecallapp.ui.main.MainActivity

class ChooseNumberActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChooseNumberBinding
    private val viewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChooseNumberBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        setupObservers()
    }

    private fun setupListeners() {
        binding.btnSaveProfile.setOnClickListener {
            val name = binding.edtProfileName.text.toString().trim()
            val number = binding.edtProfileNumber.text.toString().trim()

            if (name.isEmpty() || number.isEmpty()) {
                Toast.makeText(this, "Completa todos los campos", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (number.length < 4) {
                Toast.makeText(this, "El número debe tener al menos 4 caracteres", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.saveUserNameAndNumber(name, number)
        }
    }

    private fun setupObservers() {
        viewModel.authState.observe(this) { state ->
            when (state) {
                is AuthViewModel.AuthState.Loading -> {
                    binding.prgChooseNumber.visibility = View.VISIBLE
                    binding.btnSaveProfile.isEnabled = false
                }
                is AuthViewModel.AuthState.Success -> {
                    binding.prgChooseNumber.visibility = View.GONE
                    Toast.makeText(this, "Perfil configurado con éxito", Toast.LENGTH_SHORT).show()
                    checkPermissionsAndNavigate()
                }
                is AuthViewModel.AuthState.Error -> {
                    binding.prgChooseNumber.visibility = View.GONE
                    binding.btnSaveProfile.isEnabled = true
                    Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                }
                else -> {
                    binding.prgChooseNumber.visibility = View.GONE
                    binding.btnSaveProfile.isEnabled = true
                }
            }
        }
    }

    private fun checkPermissionsAndNavigate() {
        if (arePermissionsGranted()) {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        } else {
            val intent = Intent(this, PermissionActivity::class.java)
            startActivity(intent)
            finish()
        }
    }

    private fun arePermissionsGranted(): Boolean {
        val audioGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return audioGranted && notificationGranted
    }
}
