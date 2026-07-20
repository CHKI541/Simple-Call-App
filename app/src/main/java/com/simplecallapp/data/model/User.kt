package com.simplecallapp.data.model

data class User(
    val uid: String = "",
    val email: String = "",
    val number: String = "",
    val name: String = "",
    val status: String = "offline",
    val fcmToken: String = ""
)
