package com.simplecallapp.data.model

data class CallHistory(
    val id: String = "",
    val phoneNumber: String = "",
    val contactName: String = "",
    val type: String = "", // "incoming", "outgoing", "missed"
    val timestamp: Long = 0L,
    val duration: Long = 0L // en segundos
)
