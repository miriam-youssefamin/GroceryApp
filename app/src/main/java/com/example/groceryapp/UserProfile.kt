package com.example.groceryapp

// Default values give Firebase the no-argument constructor it needs to read a node into this class.
data class UserProfile(
    val name: String = "",
    val email: String = "",
    val address: String = "",
    val phone: String = ""
)
