package com.simplecallapp.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.simplecallapp.R
import com.simplecallapp.data.repository.UserRepository
import com.simplecallapp.databinding.ActivityMainBinding
import com.simplecallapp.ui.auth.PermissionActivity
import com.simplecallapp.ui.call.CallActivity
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val userRepo = UserRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Verificar si tenemos los permisos requeridos. Si no, redirigir a PermissionActivity
        if (!hasRequiredPermissions()) {
            val intent = Intent(this, PermissionActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBottomNavigation()

        // Registrar token FCM para recibir push notifications de llamadas
        com.simplecallapp.service.FcmTokenManager.ensureTokenRegistered()

        // Cargar el número desde Firestore y luego arrancar el servicio
        ensureUserNumberAndStartService()

        // Cargar DialFragment por defecto
        if (savedInstanceState == null) {
            loadFragment(DialFragment())
        }
    }

    private var activeCallJob: kotlinx.coroutines.Job? = null

    override fun onStart() {
        super.onStart()
        checkActiveCallBanner()
    }

    override fun onStop() {
        activeCallJob?.cancel()
        super.onStop()
    }

    private fun checkActiveCallBanner() {
        activeCallJob?.cancel()
        val service = com.simplecallapp.service.CallForegroundService.activeInstance
        if (service != null && service.callState.value == com.simplecallapp.service.CallForegroundService.CallState.Connected) {
            binding.layoutActiveCallBanner.visibility = android.view.View.VISIBLE
            binding.layoutActiveCallBanner.setOnClickListener {
                val intent = Intent(this, CallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(com.simplecallapp.service.CallForegroundService.EXTRA_CALLER, service.activePeers.keys.firstOrNull() ?: "")
                    putExtra(com.simplecallapp.service.CallForegroundService.EXTRA_CALLEE, "")
                    putExtra(com.simplecallapp.service.CallForegroundService.EXTRA_IS_INCOMING, false)
                }
                startActivity(intent)
            }
            
            activeCallJob = lifecycleScope.launch {
                service.callState.collect { state ->
                    if (state == com.simplecallapp.service.CallForegroundService.CallState.Ended || 
                        state == com.simplecallapp.service.CallForegroundService.CallState.Idle) {
                        binding.layoutActiveCallBanner.visibility = android.view.View.GONE
                    }
                }
            }
        } else {
            binding.layoutActiveCallBanner.visibility = android.view.View.GONE
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val audioPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notifPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return audioPermission && notifPermission
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_dial -> DialFragment()
                R.id.nav_history -> CallHistoryFragment()
                R.id.nav_chats -> ConversationsFragment()
                R.id.nav_contacts -> ContactsFragment()
                R.id.nav_profile -> ProfileFragment()
                else -> DialFragment()
            }
            loadFragment(fragment)
            true
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.mainFragmentContainer, fragment)
            .commit()
    }

    private fun ensureUserNumberAndStartService() {
        val prefs = getSharedPreferences("SimpleCallAppPrefs", Context.MODE_PRIVATE)
        val myNumber = prefs.getString("user_number", "") ?: ""

        if (myNumber.isNotEmpty()) {
            // Ya tenemos el número guardado, arrancar servicio directamente
            (application as? com.simplecallapp.SimpleCallApplication)?.startListenerServiceSafe()
        } else {
            // Necesitamos cargar de Firestore
            lifecycleScope.launch {
                try {
                    val uid = FirebaseAuth.getInstance().currentUser?.uid
                    if (uid != null) {
                        val profile = userRepo.getUserProfile(uid)
                        if (profile != null && profile.number.isNotEmpty()) {
                            prefs.edit().putString("user_number", profile.number).apply()
                        }
                    }
                } catch (e: Exception) {
                    // Ignorar errores de red
                } finally {
                    (application as? com.simplecallapp.SimpleCallApplication)?.startListenerServiceSafe()
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode
            if (keyCode == android.view.KeyEvent.KEYCODE_CALL) {
                val fragment = supportFragmentManager.findFragmentById(R.id.mainFragmentContainer)
                if (fragment is DialFragment) {
                    val number = fragment.view?.findViewById<android.widget.TextView>(R.id.txtDialNumber)?.text?.toString()?.trim() ?: ""
                    if (number.isNotEmpty()) {
                        fragment.view?.findViewById<android.view.View>(R.id.btnInitiateVoip)?.performClick()
                        return true
                    }
                } else {
                    binding.bottomNavigation.selectedItemId = R.id.nav_dial
                    return true
                }
            }
            
            val displayLabel = event.displayLabel
            if (displayLabel in '0'..'9' || displayLabel == '*' || displayLabel == '#') {
                val fragment = supportFragmentManager.findFragmentById(R.id.mainFragmentContainer)
                if (fragment is DialFragment) {
                    val textView = fragment.view?.findViewById<android.widget.TextView>(R.id.txtDialNumber)
                    if (textView != null) {
                        textView.text = textView.text.toString() + displayLabel
                        return true
                    }
                } else {
                    binding.bottomNavigation.selectedItemId = R.id.nav_dial
                    supportFragmentManager.executePendingTransactions()
                    val newFragment = supportFragmentManager.findFragmentById(R.id.mainFragmentContainer)
                    if (newFragment is DialFragment) {
                        val textView = newFragment.view?.findViewById<android.widget.TextView>(R.id.txtDialNumber)
                        if (textView != null) {
                            textView.text = textView.text.toString() + displayLabel
                            return true
                        }
                    }
                }
            }
            
            if (keyCode == android.view.KeyEvent.KEYCODE_DEL) {
                val fragment = supportFragmentManager.findFragmentById(R.id.mainFragmentContainer)
                if (fragment is DialFragment) {
                    val textView = fragment.view?.findViewById<android.widget.TextView>(R.id.txtDialNumber)
                    if (textView != null) {
                        val txt = textView.text.toString()
                        if (txt.isNotEmpty()) {
                            textView.text = txt.substring(0, txt.length - 1)
                            return true
                        }
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
