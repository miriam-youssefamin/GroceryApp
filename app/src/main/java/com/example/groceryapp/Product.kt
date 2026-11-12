package com.example.groceryapp

import com.google.firebase.database.Exclude

/**
 * A product listed by a seller: products/{productId}.
 * [photoUrl] is the S3 URL of the product picture (product_photos/{sellerUid}/{timestamp}.jpg), or "" if none.
 */
data class Product(
    @get:Exclude var id: String = "",
    val name: String = "",
    val price: Double = 0.0,
    val quantity: Int = 0,
    val sellerId: String = "",
    val photoUrl: String = ""
) {
    @get:Exclude
    val inStock: Boolean
        get() = quantity > 0
}