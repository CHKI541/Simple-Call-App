package com.simplecallapp.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.simplecallapp.R
import com.simplecallapp.data.model.Message
import com.simplecallapp.databinding.ItemMessageReceivedBinding
import com.simplecallapp.databinding.ItemMessageSentBinding
import java.text.SimpleDateFormat
import java.util.*

class MessageAdapter(
    private val myNumber: String,
    private val onMessageLongClick: (Message) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var messages: List<Message> = emptyList()

    fun submitList(newMessages: List<Message>) {
        val diffCallback = object : androidx.recyclerview.widget.DiffUtil.Callback() {
            override fun getOldListSize(): Int = messages.size
            override fun getNewListSize(): Int = newMessages.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return messages[oldItemPosition].id == newMessages[newItemPosition].id
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldMsg = messages[oldItemPosition]
                val newMsg = newMessages[newItemPosition]
                return oldMsg.text == newMsg.text &&
                        oldMsg.safeTimestamp == newMsg.safeTimestamp &&
                        oldMsg.read == newMsg.read &&
                        oldMsg.delivered == newMsg.delivered
            }
        }
        val diffResult = androidx.recyclerview.widget.DiffUtil.calculateDiff(diffCallback)
        this.messages = newMessages
        diffResult.dispatchUpdatesTo(this)
    }

    companion object {
        private const val VIEW_TYPE_SENT = 1
        private const val VIEW_TYPE_RECEIVED = 2
    }

    override fun getItemViewType(position: Int): Int {
        val message = messages[position]
        return if (message.fromNumber == myNumber) {
            VIEW_TYPE_SENT
        } else {
            VIEW_TYPE_RECEIVED
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_SENT) {
            val binding = ItemMessageSentBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            SentViewHolder(binding)
        } else {
            val binding = ItemMessageReceivedBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            ReceivedViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val timeStr = sdf.format(Date(message.safeTimestamp))

        if (holder is SentViewHolder) {
            holder.binding.txtMessageSentText.text = message.text
            holder.binding.txtMessageSentTime.text = timeStr
            
            // Lógica de confirmación de entrega y lectura
            val context = holder.itemView.context
            if (message.read) {
                holder.binding.txtMessageStatus.visibility = View.VISIBLE
                holder.binding.txtMessageStatus.text = "✓✓"
                holder.binding.txtMessageStatus.setTextColor(context.getColor(R.color.accent)) // Azul
            } else if (message.delivered) {
                holder.binding.txtMessageStatus.visibility = View.VISIBLE
                holder.binding.txtMessageStatus.text = "✓✓"
                holder.binding.txtMessageStatus.setTextColor(context.getColor(R.color.text_light_gray)) // Gris
            } else {
                holder.binding.txtMessageStatus.visibility = View.GONE
            }
        } else if (holder is ReceivedViewHolder) {
            holder.binding.txtMessageReceivedText.text = message.text
            holder.binding.txtMessageReceivedTime.text = timeStr
        }

        holder.itemView.setOnLongClickListener {
            onMessageLongClick(message)
            true
        }
    }

    override fun getItemCount(): Int = messages.size

    class SentViewHolder(val binding: ItemMessageSentBinding) : RecyclerView.ViewHolder(binding.root)
    class ReceivedViewHolder(val binding: ItemMessageReceivedBinding) : RecyclerView.ViewHolder(binding.root)
}
