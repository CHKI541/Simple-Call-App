package com.simplecallapp.ui.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.simplecallapp.adapter.CallHistoryAdapter
import com.simplecallapp.data.repository.CallHistoryRepository
import com.simplecallapp.databinding.FragmentCallHistoryBinding
import com.simplecallapp.service.CallForegroundService
import com.simplecallapp.ui.call.CallActivity
import kotlinx.coroutines.launch

class CallHistoryFragment : Fragment() {

    private var _binding: FragmentCallHistoryBinding? = null
    private val binding get() = _binding!!
    private val historyRepo = CallHistoryRepository()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCallHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvCallHistory.layoutManager = LinearLayoutManager(requireContext())
        
        setupListeners()
        loadCallHistory()
    }

    private fun setupListeners() {
        binding.btnClearHistory.setOnClickListener {
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            if (uid != null) {
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        historyRepo.clearCallHistory(uid)
                        val binding = _binding ?: return@launch
                        loadCallHistory()
                        val context = context ?: return@launch
                        Toast.makeText(context, "Historial limpiado", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        val context = context ?: return@launch
                        Toast.makeText(context, "Error al limpiar historial", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun loadCallHistory() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val records = historyRepo.getCallHistory(uid)
                val binding = _binding ?: return@launch
                if (records.isEmpty()) {
                    binding.txtHistoryEmpty.visibility = View.VISIBLE
                    binding.rvCallHistory.visibility = View.GONE
                } else {
                    binding.txtHistoryEmpty.visibility = View.GONE
                    binding.rvCallHistory.visibility = View.VISIBLE
                    
                    val context = context ?: return@launch
                    binding.rvCallHistory.adapter = CallHistoryAdapter(records) { record ->
                        val ctx = context ?: return@CallHistoryAdapter
                        val prefs = ctx.getSharedPreferences("SimpleCallAppPrefs", Context.MODE_PRIVATE)
                        val myNumber = prefs.getString("user_number", "") ?: ""

                        if (record.phoneNumber == myNumber) {
                            Toast.makeText(ctx, "No puedes llamarte a ti mismo", Toast.LENGTH_SHORT).show()
                            return@CallHistoryAdapter
                        }

                        val intent = Intent(ctx, CallActivity::class.java).apply {
                            putExtra(CallForegroundService.EXTRA_CALLER, myNumber)
                            putExtra(CallForegroundService.EXTRA_CALLEE, record.phoneNumber)
                            putExtra(CallForegroundService.EXTRA_IS_INCOMING, false)
                        }
                        startActivity(intent)
                    }
                }
            } catch (e: Exception) {
                val binding = _binding ?: return@launch
                binding.txtHistoryEmpty.text = "Error al cargar historial"
                binding.txtHistoryEmpty.visibility = View.VISIBLE
                binding.rvCallHistory.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
