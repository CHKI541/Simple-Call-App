package com.simplecallapp.ui.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.simplecallapp.R
import com.simplecallapp.adapter.ConversationsAdapter
import com.simplecallapp.data.model.ChatRoom
import com.simplecallapp.data.repository.ChatRepository
import com.simplecallapp.data.repository.ContactRepository
import com.simplecallapp.databinding.FragmentConversationsBinding
import com.simplecallapp.ui.chat.ChatActivity
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ConversationsFragment : Fragment() {

    private var _binding: FragmentConversationsBinding? = null
    private val binding get() = _binding!!
    private val chatRepo = ChatRepository()
    private val contactRepo = ContactRepository()
    private var myNumber = ""
    private var contactMap: Map<String, String> = emptyMap() // number -> name

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConversationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val prefs = requireContext().getSharedPreferences("SimpleCallAppPrefs", Context.MODE_PRIVATE)
        myNumber = prefs.getString("user_number", "") ?: ""

        binding.rvConversations.layoutManager = LinearLayoutManager(requireContext())
        
        binding.btnNewChat.setOnClickListener {
            startActivity(Intent(requireContext(), NewConversationActivity::class.java))
        }

        if (myNumber.isNotEmpty()) {
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            if (uid != null) {
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val contacts = contactRepo.getContacts(uid)
                        contactMap = contacts.associate { it.number to it.name }
                    } catch (e: Exception) {}
                    val binding = _binding ?: return@launch
                    loadConversations()
                }
            } else {
                loadConversations()
            }
        } else {
            binding.txtChatsEmpty.text = "Error de sesión. Vuelve a ingresar."
            binding.txtChatsEmpty.visibility = View.VISIBLE
        }
    }

    private fun loadConversations() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                chatRepo.getConversations(myNumber)
                    .catch { e ->
                        val binding = _binding ?: return@catch
                        binding.txtChatsEmpty.text = "Error al cargar chats: ${e.message}"
                        binding.txtChatsEmpty.visibility = View.VISIBLE
                        binding.rvConversations.visibility = View.GONE
                    }
                    .collectLatest { chatRooms ->
                        val binding = _binding ?: return@collectLatest
                        if (chatRooms.isEmpty()) {
                            binding.txtChatsEmpty.visibility = View.VISIBLE
                            binding.rvConversations.visibility = View.GONE
                        } else {
                            binding.txtChatsEmpty.visibility = View.GONE
                            binding.rvConversations.visibility = View.VISIBLE
                            
                            val context = context ?: return@collectLatest
                            binding.rvConversations.adapter = ConversationsAdapter(
                                myNumber = myNumber,
                                chatRooms = chatRooms,
                                contactMap = contactMap,
                                onConversationClick = { otherNumber ->
                                    val ctx = context ?: return@ConversationsAdapter
                                    val name = contactMap[otherNumber] ?: otherNumber
                                    val intent = Intent(ctx, ChatActivity::class.java).apply {
                                        putExtra(ChatActivity.EXTRA_CHAT_NUMBER, otherNumber)
                                        putExtra(ChatActivity.EXTRA_CHAT_NAME, name)
                                    }
                                    startActivity(intent)
                                },
                                onConversationLongClick = { chatRoom ->
                                    showDeleteConversationDialog(chatRoom)
                                }
                            )
                        }
                    }
            } catch (e: Exception) {
                val binding = _binding ?: return@launch
                binding.txtChatsEmpty.text = "Ocurrió un error inesperado"
                binding.txtChatsEmpty.visibility = View.VISIBLE
                binding.rvConversations.visibility = View.GONE
            }
        }
    }

    private fun showDeleteConversationDialog(chatRoom: ChatRoom) {
        val context = context ?: return
        AlertDialog.Builder(context, R.style.Theme_SimpleCallApp)
            .setTitle("Eliminar chat")
            .setMessage("¿Estás seguro de que quieres eliminar esta conversación solo para ti?")
            .setPositiveButton("Eliminar") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        chatRepo.deleteConversation(chatRoom.roomId, myNumber)
                        val ctx = context ?: return@launch
                        Toast.makeText(ctx, "Conversación eliminada", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {}
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
