package com.simplecallapp.adapter

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.simplecallapp.data.model.CallHistory
import com.simplecallapp.databinding.ItemCallHistoryBinding
import java.text.SimpleDateFormat
import java.util.*

class CallHistoryAdapter(
    private val records: List<CallHistory>,
    private val onCallBackClick: (CallHistory) -> Unit
) : RecyclerView.Adapter<CallHistoryAdapter.CallViewHolder>() {

    class CallViewHolder(val binding: ItemCallHistoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CallViewHolder {
        val binding = ItemCallHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return CallViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CallViewHolder, position: Int) {
        val record = records[position]
        holder.binding.txtHistoryName.text = record.contactName

        // Formatear fecha
        val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
        val dateStr = sdf.format(Date(record.timestamp))

        // Formatear duración
        val durationStr = String.format("%02d:%02d", record.duration / 60, record.duration % 60)

        // Configurar tipo de llamada e ícono
        when (record.type) {
            "incoming" -> {
                holder.binding.imgCallType.setImageResource(android.R.drawable.sym_call_incoming)
                holder.binding.imgCallType.imageTintList = ColorStateList.valueOf(Color.GREEN)
                holder.binding.txtHistoryDetails.text = "$dateStr • Recibida ($durationStr)"
                holder.binding.txtHistoryName.setTextColor(Color.WHITE)
            }
            "outgoing" -> {
                holder.binding.imgCallType.setImageResource(android.R.drawable.sym_call_outgoing)
                holder.binding.imgCallType.imageTintList = ColorStateList.valueOf(Color.parseColor("#2EA6DE"))
                holder.binding.txtHistoryDetails.text = "$dateStr • Realizada ($durationStr)"
                holder.binding.txtHistoryName.setTextColor(Color.WHITE)
            }
            "missed" -> {
                holder.binding.imgCallType.setImageResource(android.R.drawable.sym_call_incoming)
                holder.binding.imgCallType.imageTintList = ColorStateList.valueOf(Color.RED)
                holder.binding.txtHistoryDetails.text = "$dateStr • Perdida"
                holder.binding.txtHistoryName.setTextColor(Color.RED)
            }
            else -> {
                holder.binding.imgCallType.setImageResource(android.R.drawable.sym_call_incoming)
                holder.binding.txtHistoryDetails.text = "$dateStr • $durationStr"
                holder.binding.txtHistoryName.setTextColor(Color.WHITE)
            }
        }

        holder.binding.btnHistoryCall.setOnClickListener {
            onCallBackClick(record)
        }
    }

    override fun getItemCount(): Int = records.size
}
