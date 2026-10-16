package com.example.groceryapp

import com.google.firebase.database.Exclude

data class Product(
    @get:Exclude var id: String = "",
    val name: String = "",
    val price: Double = 0.0,
    val quantity: Int = 0,
    val sellerId: String = ""
) {
    @get:Exclude
    val inStock: Boolean
        get() = quantity > 0
}