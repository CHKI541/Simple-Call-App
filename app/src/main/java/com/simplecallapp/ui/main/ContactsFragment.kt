package com.simplecallapp.ui.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.simplecallapp.R
import com.simplecallapp.adapter.ContactsAdapter
import com.simplecallapp.data.model.Contact
import com.simplecallapp.data.repository.ContactRepository
import com.simplecallapp.databinding.FragmentContactsBinding
import com.simplecallapp.service.CallForegroundService
import com.simplecallapp.ui.call.CallActivity
import com.simplecallapp.ui.chat.ChatActivity
import kotlinx.coroutines.launch

class ContactsFragment : Fragment() {

    private var _binding: FragmentContactsBinding? = null
    private val binding get() = _binding!!
    private val contactRepo = ContactRepository()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentContactsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvContacts.layoutManager = LinearLayoutManager(requireContext())
        loadContacts()

        binding.btnNewContact.setOnClickListener {
            showAddContactDialog()
        }
    }

    private val addContactLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            loadContacts()
        }
    }

    private fun showAddContactDialog() {
        val intent = Intent(requireContext(), AddContactActivity::class.java)
        addContactLauncher.launch(intent)
    }


    private fun loadContacts() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val list = contactRepo.getContacts(uid)
                val binding = _binding ?: return@launch
                if (list.isEmpty()) {
                    binding.txtContactsEmpty.visibility = View.VISIBLE
                    binding.rvContacts.visibility = View.GONE
                } else {
                    binding.txtContactsEmpty.visibility = View.GONE
                    binding.rvContacts.visibility = View.VISIBLE
                    
                    val context = context ?: return@launch
                    binding.rvContacts.adapter = ContactsAdapter(
                        contacts = list.toMutableList(),
                        onCallClick = { contact ->
                            val ctx = context ?: return@ContactsAdapter
                            val prefs = ctx.getSharedPreferences("SimpleCallAppPrefs", Context.MODE_PRIVATE)
                            val myNumber = prefs.getString("user_number", "") ?: ""

                            val intent = Intent(ctx, CallActivity::class.java).apply {
                                putExtra(CallForegroundService.EXTRA_CALLER, myNumber)
                                putExtra(CallForegroundService.EXTRA_CALLEE, contact.number)
                                putExtra(CallForegroundService.EXTRA_IS_INCOMING, false)
                            }
                            startActivity(intent)
                        },
                        onChatClick = { contact ->
                            val ctx = context ?: return@ContactsAdapter
                            val intent = Intent(ctx, ChatActivity::class.java).apply {
                                putExtra(ChatActivity.EXTRA_CHAT_NUMBER, contact.number)
                                putExtra(ChatActivity.EXTRA_CHAT_NAME, contact.name)
                            }
                            startActivity(intent)
                        },
                        onContactsChanged = { loadContacts() }
                    )
                }
            } catch (e: Exception) {
                val binding = _binding ?: return@launch
                binding.txtContactsEmpty.text = "Error al cargar contactos"
                binding.txtContactsEmpty.visibility = View.VISIBLE
                binding.rvContacts.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
