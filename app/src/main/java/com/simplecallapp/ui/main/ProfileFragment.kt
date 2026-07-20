package com.simplecallapp.ui.main

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.simplecallapp.R
import com.simplecallapp.data.repository.UserRepository
import com.simplecallapp.databinding.FragmentProfileBinding
import com.simplecallapp.service.IncomingCallListenerService
import com.simplecallapp.ui.auth.LoginActivity
import kotlinx.coroutines.launch

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private val userRepo = UserRepository()

    private val callRingtonePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            if (uri != null) {
                val prefs = requireContext().getSharedPreferences("SimpleCallAppPrefs", Context.MODE_PRIVATE)
                prefs.edit().putString("notif_calls_ringtone", uri.toString()).apply()
                Toast.makeText(requireContext(), "Tono de llamada actualizado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val messageRingtonePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            if (uri != null) {
                val prefs = requireContext().getSharedPreferences("SimpleCallAppPrefs", Context.MODE_PRIVATE)
                prefs.edit().putString("notif_messages_ringtone", uri.toString()).apply()
                Toast.makeText(requireContext(), "Tono de notificaciones actualizado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadUserProfile()
        setupListeners()
    }

    private fun loadUserProfile() {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val uid = currentUser.uid
        binding.txtProfileUserEmail.text = currentUser.email

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val profile = userRepo.getUserProfile(uid)
                val binding = _binding ?: return@launch
                if (profile != null) {
                    binding.txtProfileUserName.text = profile.name.ifEmpty { "Sin nombre" }
                    binding.txtProfileUserNumber.text = profile.number
                    
                    val context = context ?: return@launch
                    val prefs = context.getSharedPreferences("SimpleCallAppPrefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("user_number", profile.number).apply()
                }
            } catch (e: Exception) {
                val context = context ?: return@launch
                Toast.makeText(context, "Error al cargar datos del perfil", Toast.LENGTH_SHORT).show()
            }
        }

        // Cargar estado de notificaciones y ajustes
        val prefs = requireContext().getSharedPreferences("SimpleCallAppPrefs", Context.MODE_PRIVATE)
        binding.switchCallNotifications.isChecked = prefs.getBoolean("notif_calls_enabled", true)
        binding.switchMessageNotifications.isChecked = prefs.getBoolean("notif_messages_enabled", true)
        binding.switchReadReceipts.isChecked = prefs.getBoolean("read_receipts_enabled", true)
        binding.switchEnterToSend.isChecked = prefs.getBoolean("enter_to_send", false)
        binding.sliderCallVolume.value = prefs.getInt("call_volume", 100).toFloat()
    }

    private fun setupListeners() {
        val prefs = requireContext().getSharedPreferences("SimpleCallAppPrefs", Context.MODE_PRIVATE)

        binding.switchCallNotifications.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("notif_calls_enabled", isChecked).apply()
            val msg = if (isChecked) "Notificaciones de llamadas activadas" else "Notificaciones de llamadas desactivadas"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        binding.switchMessageNotifications.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("notif_messages_enabled", isChecked).apply()
            val msg = if (isChecked) "Notificaciones de chats activadas" else "Notificaciones de chats desactivadas"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        binding.switchReadReceipts.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("read_receipts_enabled", isChecked).apply()
            val msg = if (isChecked) "Confirmación de lectura activada" else "Confirmación de lectura desactivada"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        binding.switchEnterToSend.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("enter_to_send", isChecked).apply()
        }

        binding.sliderCallVolume.addOnChangeListener { _, value, _ ->
            prefs.edit().putInt("call_volume", value.toInt()).apply()
        }

        binding.btnCallRingtone.setOnClickListener {
            val currentUriStr = prefs.getString("notif_calls_ringtone", null)
            val currentUri = if (currentUriStr != null) Uri.parse(currentUriStr) else null
            
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Seleccionar Tono de Llamada")
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            }
            callRingtonePickerLauncher.launch(intent)
        }

        binding.btnMessageRingtone.setOnClickListener {
            val currentUriStr = prefs.getString("notif_messages_ringtone", null)
            val currentUri = if (currentUriStr != null) Uri.parse(currentUriStr) else null
            
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Seleccionar Tono de Mensaje")
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            }
            messageRingtonePickerLauncher.launch(intent)
        }

        binding.btnChatWallpaper.setOnClickListener {
            showChatWallpaperDialog()
        }

        binding.btnChangePassword.setOnClickListener {
            showChangePasswordDialog()
        }

        binding.btnBlockContacts.setOnClickListener {
            showBlockContactDialog()
        }

        binding.btnSignOut.setOnClickListener {
            // Detener el servicio de escucha
            val serviceIntent = Intent(requireContext(), IncomingCallListenerService::class.java)
            requireContext().stopService(serviceIntent)

            // Limpiar caché de pref
            prefs.edit().remove("user_number").apply()

            // Cerrar sesión
            FirebaseAuth.getInstance().signOut()

            // Redirigir a LoginActivity
            val intent = Intent(requireContext(), LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            activity?.finish()
        }
    }

    private fun showChangePasswordDialog() {
        val context = requireContext()
        val builder = AlertDialog.Builder(context, R.style.Theme_SimpleCallApp)
        builder.setTitle("Cambiar Contraseña")

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = context.resources.getDimensionPixelSize(R.dimen.margin_normal)
            setPadding(padding, padding, padding, padding)
        }

        val inputPassword = EditText(context).apply {
            hint = "Nueva Contraseña"
            setTextColor(context.getColor(R.color.text_white))
            setHintTextColor(context.getColor(R.color.text_light_gray))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        container.addView(inputPassword)
        builder.setView(container)

        builder.setPositiveButton("Actualizar") { dialog, _ ->
            val newPass = inputPassword.text.toString().trim()
            if (newPass.length < 6) {
                Toast.makeText(context, "La contraseña debe tener al menos 6 caracteres", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }

            val user = FirebaseAuth.getInstance().currentUser
            val appContext = context.applicationContext
            user?.updatePassword(newPass)?.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Toast.makeText(appContext, "Contraseña actualizada exitosamente", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(appContext, "Error al actualizar: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
            dialog.dismiss()
        }

        builder.setNegativeButton("Cancelar") { dialog, _ ->
            dialog.cancel()
        }

        builder.show()
    }

    private fun showBlockContactDialog() {
        val context = requireContext()
        val prefs = context.getSharedPreferences("SimpleCallAppPrefs", Context.MODE_PRIVATE)
        val blockedStr = prefs.getString("blocked_numbers", "") ?: ""
        val blockedList = if (blockedStr.isNotEmpty()) blockedStr.split(",") else emptyList()

        val builder = AlertDialog.Builder(context, R.style.Theme_SimpleCallApp)
        builder.setTitle("Bloquear Contacto")

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = context.resources.getDimensionPixelSize(R.dimen.margin_normal)
            setPadding(padding, padding, padding, padding)
        }

        val tvBlocked = android.widget.TextView(context).apply {
            text = if (blockedList.isEmpty()) "No tienes números bloqueados." else "Bloqueados:\n${blockedList.joinToString("\n")}"
            setTextColor(context.getColor(R.color.text_light_gray))
            setPadding(0, 0, 0, 32)
        }

        val inputNumber = EditText(context).apply {
            hint = "Número a bloquear/desbloquear"
            setTextColor(context.getColor(R.color.text_white))
            setHintTextColor(context.getColor(R.color.text_light_gray))
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }

        container.addView(tvBlocked)
        container.addView(inputNumber)
        builder.setView(container)

        builder.setPositiveButton("Bloquear / Desbloquear") { dialog, _ ->
            val number = inputNumber.text.toString().trim()
            if (number.isEmpty()) return@setPositiveButton
            
            val newList = blockedList.toMutableList()
            if (newList.contains(number)) {
                newList.remove(number)
                Toast.makeText(context, "Número desbloqueado", Toast.LENGTH_SHORT).show()
            } else {
                newList.add(number)
                Toast.makeText(context, "Número bloqueado", Toast.LENGTH_SHORT).show()
            }
            prefs.edit().putString("blocked_numbers", newList.joinToString(",")).apply()
            dialog.dismiss()
        }

        builder.setNegativeButton("Cerrar", null)
        builder.show()
    }

    private fun showChatWallpaperDialog() {
        val context = requireContext()
        val prefs = context.getSharedPreferences("SimpleCallAppPrefs", Context.MODE_PRIVATE)
        val colors = arrayOf("Por defecto (Oscuro)", "Gris Claro", "Azul Noche", "Verde Bosque")
        val colorValues = arrayOf("#121212", "#2c2c2c", "#0a192f", "#1e392a")
        
        val builder = AlertDialog.Builder(context, R.style.Theme_SimpleCallApp)
        builder.setTitle("Elegir Fondo de Chat")
        builder.setItems(colors) { dialog, which ->
            prefs.edit().putString("chat_bg_color", colorValues[which]).apply()
            Toast.makeText(context, "Fondo actualizado", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        builder.setNegativeButton("Cancelar", null)
        builder.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
