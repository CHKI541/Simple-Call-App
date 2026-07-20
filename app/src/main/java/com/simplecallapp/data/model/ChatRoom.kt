package com.simplecallapp.data.model

data class ChatRoom(
    val roomId: String = "",
    val participants: List<String> = emptyList(),
    val lastMessageText: String = "",
    val lastMessageTimestamp: Long = 0,
    val lastSender: String = "",
    val unreadCountMap: Map<String, Int> = emptyMap() // Number -> Unread Count (Optional for now)
)
