package com.example.groceryapp

data class UserProfile(
    val role: String = "",
    val name: String = "",
    val email: String = "",
    val address: String = "",
    val phone: String = "",
    val photoUrl: String = ""
)