package com.simplecallapp.ui.auth

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.GoogleAuthProvider
import com.simplecallapp.R
import com.simplecallapp.databinding.ActivityLoginBinding
import com.simplecallapp.ui.main.MainActivity

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val viewModel: AuthViewModel by viewModels()
    private lateinit var googleSignInClient: GoogleSignInClient

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)!!
                val credential = com.google.firebase.auth.GoogleAuthProvider.getCredential(account.idToken, null)
                viewModel.loginWithGoogleCredential(credential)
            } catch (e: com.google.android.gms.common.api.ApiException) {
                Toast.makeText(this, "Error Google Auth (Cod: ${e.statusCode}): ${e.message}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Error en Google Sign-In: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Configurar Google Sign-In
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        setupListeners()
        setupObservers()

        // Si ya hay sesión iniciada, mostrar cargando app y ocultar formulario sincrónicamente
        val hasActiveSession = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null
        if (hasActiveSession) {
            binding.layoutAppLoading.visibility = View.VISIBLE
            binding.scrollView.visibility = View.GONE
        } else {
            binding.layoutAppLoading.visibility = View.GONE
            binding.scrollView.visibility = View.VISIBLE
        }

        viewModel.checkEmailVerificationAndProfile()
    }

    private fun setupListeners() {
        binding.btnLogin.setOnClickListener {
            val email = binding.edtEmail.text.toString().trim()
            val password = binding.edtPassword.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Por favor completa todos los campos", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.loginWithEmail(email, password)
        }

        binding.btnRegister.setOnClickListener {
            val email = binding.edtEmail.text.toString().trim()
            val password = binding.edtPassword.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Por favor completa todos los campos para registrarte", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (password.length < 6) {
                Toast.makeText(this, "La contraseña debe tener al menos 6 caracteres", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.registerWithEmail(email, password)
        }

        binding.btnGoogleSignIn.setOnClickListener {
            // Cerrar sesión previa de Google para permitir seleccionar cuenta
            googleSignInClient.signOut()
            val signInIntent = googleSignInClient.signInIntent
            googleSignInLauncher.launch(signInIntent)
        }

        // Overlay de verificación
        binding.btnCheckVerified.setOnClickListener {
            viewModel.checkEmailVerificationAndProfile()
        }

        binding.btnResendVerification.setOnClickListener {
            viewModel.sendEmailVerification()
        }

        binding.btnCancelVerification.setOnClickListener {
            viewModel.signOut()
            binding.layoutVerification.visibility = View.GONE
        }

        binding.btnForgotPassword.setOnClickListener {
            val email = binding.edtEmail.text.toString().trim()
            if (email.isEmpty()) {
                Toast.makeText(this, "Ingresá tu correo primero", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            com.google.firebase.auth.FirebaseAuth.getInstance()
                .sendPasswordResetEmail(email)
                .addOnSuccessListener {
                    Toast.makeText(this, "✅ Correo de reseteo enviado a $email", Toast.LENGTH_LONG).show()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun setupObservers() {
        viewModel.authState.observe(this) { state ->
            when (state) {
                is AuthViewModel.AuthState.Loading -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.btnLogin.isEnabled = false
                    binding.btnRegister.isEnabled = false
                    binding.btnGoogleSignIn.isEnabled = false
                }
                is AuthViewModel.AuthState.Idle -> {
                    binding.layoutAppLoading.visibility = View.GONE
                    binding.scrollView.visibility = View.VISIBLE
                    binding.progressBar.visibility = View.GONE
                    binding.btnLogin.isEnabled = true
                    binding.btnRegister.isEnabled = true
                    binding.btnGoogleSignIn.isEnabled = true
                    binding.layoutVerification.visibility = View.GONE
                }
                is AuthViewModel.AuthState.NeedsVerification -> {
                    binding.layoutAppLoading.visibility = View.GONE
                    binding.scrollView.visibility = View.GONE
                    binding.progressBar.visibility = View.GONE
                    binding.btnLogin.isEnabled = true
                    binding.btnRegister.isEnabled = true
                    binding.btnGoogleSignIn.isEnabled = true
                    binding.layoutVerification.visibility = View.VISIBLE
                }
                is AuthViewModel.AuthState.NeedsNumber -> {
                    binding.layoutAppLoading.visibility = View.GONE
                    binding.progressBar.visibility = View.GONE
                    val intent = Intent(this, ChooseNumberActivity::class.java)
                    startActivity(intent)
                    finish()
                }
                is AuthViewModel.AuthState.Success -> {
                    binding.layoutAppLoading.visibility = View.GONE
                    binding.progressBar.visibility = View.GONE
                    checkPermissionsAndNavigate()
                }
                is AuthViewModel.AuthState.Error -> {
                    binding.layoutAppLoading.visibility = View.GONE
                    binding.scrollView.visibility = View.VISIBLE
                    binding.progressBar.visibility = View.GONE
                    binding.btnLogin.isEnabled = true
                    binding.btnRegister.isEnabled = true
                    binding.btnGoogleSignIn.isEnabled = true
                    Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                }
            }
        }

        viewModel.emailVerificationSent.observe(this) { sent ->
            if (sent) {
                Toast.makeText(this, "Correo de verificación enviado", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Error al enviar correo de verificación", Toast.LENGTH_SHORT).show()
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
