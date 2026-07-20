package com.simplecallapp.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.simplecallapp.data.model.ChatRoom
import com.simplecallapp.databinding.ItemConversationBinding
import java.text.SimpleDateFormat
import java.util.*

class ConversationsAdapter(
    private val myNumber: String,
    private val chatRooms: List<ChatRoom>,
    private val contactMap: Map<String, String> = emptyMap(),
    private val onConversationClick: (String) -> Unit,
    private val onConversationLongClick: (ChatRoom) -> Unit
) : RecyclerView.Adapter<ConversationsAdapter.ConversationViewHolder>() {

    class ConversationViewHolder(val binding: ItemConversationBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationViewHolder {
        val binding = ItemConversationBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ConversationViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ConversationViewHolder, position: Int) {
        val chatRoom = chatRooms[position]
        val otherNumber = chatRoom.participants.firstOrNull { it != myNumber } ?: "Desconocido"
        val displayName = contactMap[otherNumber] ?: otherNumber

        holder.binding.txtConvName.text = displayName
        holder.binding.txtConvLastMessage.text = chatRoom.lastMessageText

        if (chatRoom.lastMessageTimestamp > 0) {
            val now = System.currentTimeMillis()
            val diff = now - chatRoom.lastMessageTimestamp
            val sdf = if (diff < 86400000L) { // menos de 24 horas
                SimpleDateFormat("HH:mm", Locale.getDefault())
            } else {
                SimpleDateFormat("dd/MM", Locale.getDefault())
            }
            holder.binding.txtConvTime.text = sdf.format(Date(chatRoom.lastMessageTimestamp))
        } else {
            holder.binding.txtConvTime.text = ""
        }

        holder.itemView.setOnClickListener {
            onConversationClick(otherNumber)
        }

        holder.itemView.setOnLongClickListener {
            onConversationLongClick(chatRoom)
            true
        }
    }

    override fun getItemCount(): Int = chatRooms.size
}
