package com.simplecallapp.ui.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.simplecallapp.databinding.FragmentDialBinding
import com.simplecallapp.service.CallForegroundService
import com.simplecallapp.ui.call.CallActivity

class DialFragment : Fragment() {

    private var _binding: FragmentDialBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDialBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupDialpad()
        setupListeners()
    }

    private fun setupDialpad() {
        val listener = View.OnClickListener { v ->
            if (v is Button) {
                val currentText = binding.txtDialNumber.text.toString()
                binding.txtDialNumber.text = currentText + v.text
            }
        }

        // Buscar recursivamente todos los botones en el layout principal y asignarles el listener
        fun attachListeners(viewGroup: ViewGroup) {
            for (i in 0 until viewGroup.childCount) {
                val child = viewGroup.getChildAt(i)
                if (child is Button) {
                    child.setOnClickListener(listener)
                } else if (child is ViewGroup) {
                    attachListeners(child)
                }
            }
        }
        attachListeners(binding.root as ViewGroup)
    }

    private fun setupListeners() {
        binding.btnDelete.setOnClickListener {
            val currentText = binding.txtDialNumber.text.toString()
            if (currentText.isNotEmpty()) {
                binding.txtDialNumber.text = currentText.substring(0, currentText.length - 1)
            }
        }

        binding.btnDelete.setOnLongClickListener {
            binding.txtDialNumber.text = ""
            true
        }

        binding.btnInitiateVoip.setOnClickListener {
            val targetNumber = binding.txtDialNumber.text.toString().trim()
            if (targetNumber.isEmpty()) {
                Toast.makeText(requireContext(), "Ingresa un número para llamar", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val prefs = requireContext().getSharedPreferences("SimpleCallAppPrefs", android.content.Context.MODE_PRIVATE)
            val myNumber = prefs.getString("user_number", "") ?: ""

            if (myNumber.isEmpty()) {
                Toast.makeText(requireContext(), "Tu número no está configurado. Ve a Perfil.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            if (targetNumber == myNumber) {
                Toast.makeText(requireContext(), "No puedes llamarte a ti mismo", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = Intent(requireContext(), CallActivity::class.java).apply {
                putExtra(CallForegroundService.EXTRA_CALLER, myNumber)
                putExtra(CallForegroundService.EXTRA_CALLEE, targetNumber)
                putExtra(CallForegroundService.EXTRA_IS_INCOMING, false)
            }
            startActivity(intent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
