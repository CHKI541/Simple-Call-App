package com.simplecallapp.data.model

data class Message(
    val id: String = "",
    val fromNumber: String = "",
    val toNumber: String = "",
    val text: String = "",
    val timestamp: Any? = null,
    val delivered: Boolean = false,
    val read: Boolean = false,
    val deletedBy: List<String> = emptyList()
) {
    val safeTimestamp: Long
        get() {
            return when (val t = timestamp) {
                is Long -> t
                is com.google.firebase.Timestamp -> t.toDate().time
                is java.util.Date -> t.time
                is Number -> t.toLong()
                else -> System.currentTimeMillis()
            }
        }
}
