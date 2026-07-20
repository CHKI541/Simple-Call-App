package com.simplecallapp.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.simplecallapp.R
import com.simplecallapp.data.model.Contact
import com.simplecallapp.data.repository.ContactRepository
import com.simplecallapp.databinding.ItemContactBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ContactsAdapter(
    private val contacts: MutableList<Contact>,
    private val onCallClick: (Contact) -> Unit,
    private val onChatClick: (Contact) -> Unit,
    private val onContactsChanged: (() -> Unit)? = null
) : RecyclerView.Adapter<ContactsAdapter.ContactViewHolder>() {

    private val contactRepo = ContactRepository()

    class ContactViewHolder(val binding: ItemContactBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val binding = ItemContactBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ContactViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        val contact = contacts[position]
        val context = holder.itemView.context
        val prefs = context.getSharedPreferences("SimpleCallAppPrefs", Context.MODE_PRIVATE)
        val isMuted = prefs.getBoolean("silenced_contact_${contact.number}", false)

        if (isMuted) {
            holder.binding.txtContactName.text = "${contact.name} 🔇"
        } else {
            holder.binding.txtContactName.text = contact.name
        }
        holder.binding.txtContactNumber.text = contact.number

        holder.binding.btnContactCall.setOnClickListener {
            onCallClick(contact)
        }

        holder.binding.btnContactChat.setOnClickListener {
            onChatClick(contact)
        }

        holder.itemView.setOnLongClickListener {
            val muteOption = if (isMuted) "Activar sonido" else "Silenciar notificaciones"
            AlertDialog.Builder(context, R.style.Theme_SimpleCallApp)
                .setTitle(contact.name)
                .setItems(arrayOf(muteOption, "✏️  Editar contacto", "🗑️  Eliminar contacto")) { _, which ->
                    when (which) {
                        0 -> {
                            // Mute/unmute
                            val newMutedState = !isMuted
                            prefs.edit().putBoolean("silenced_contact_${contact.number}", newMutedState).apply()
                            notifyItemChanged(position)
                            val msg = if (newMutedState) "Contacto silenciado" else "Sonido activado"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                        1 -> showEditContactDialog(context, contact, position)
                        2 -> showDeleteContactDialog(context, contact, position)
                    }
                }
                .show()
            true
        }
    }

    private fun showEditContactDialog(context: android.content.Context, contact: Contact, position: Int) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        val padding = context.resources.getDimensionPixelSize(R.dimen.margin_normal)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val nameInput = EditText(context).apply {
            hint = "Nombre"
            setText(contact.name)
            setTextColor(context.getColor(R.color.text_white))
            setHintTextColor(context.getColor(R.color.text_light_gray))
        }
        val numberInput = EditText(context).apply {
            hint = "Número"
            setText(contact.number)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setTextColor(context.getColor(R.color.text_white))
            setHintTextColor(context.getColor(R.color.text_light_gray))
        }
        container.addView(nameInput)
        container.addView(numberInput)

        AlertDialog.Builder(context, R.style.Theme_SimpleCallApp)
            .setTitle("Editar Contacto")
            .setView(container)
            .setPositiveButton("Guardar") { _, _ ->
                val newName = nameInput.text.toString().trim()
                val newNumber = numberInput.text.toString().trim()
                if (newName.isEmpty() || newNumber.isEmpty()) {
                    Toast.makeText(context, "Nombre y número son obligatorios", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val updatedContact = Contact(name = newName, number = newNumber)
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        contactRepo.updateContact(uid, contact.number, updatedContact)
                        val currPos = contacts.indexOfFirst { it.number == contact.number }
                        if (currPos != -1) {
                            contacts[currPos] = updatedContact
                            notifyItemChanged(currPos)
                        }
                        Toast.makeText(context, "Contacto actualizado", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Error al actualizar: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showDeleteContactDialog(context: android.content.Context, contact: Contact, position: Int) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        AlertDialog.Builder(context, R.style.Theme_SimpleCallApp)
            .setTitle("Eliminar Contacto")
            .setMessage("¿Eliminar a ${contact.name} de tu lista?")
            .setPositiveButton("Eliminar") { _, _ ->
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        contactRepo.deleteContact(uid, contact.number)
                        val currPos = contacts.indexOfFirst { it.number == contact.number }
                        if (currPos != -1) {
                            contacts.removeAt(currPos)
                            notifyItemRemoved(currPos)
                            notifyItemRangeChanged(currPos, contacts.size)
                        }
                        onContactsChanged?.invoke()
                        Toast.makeText(context, "Contacto eliminado", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Error al eliminar: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun getItemCount(): Int = contacts.size
}
