package com.example.groceryapp

/**
 * Account record stored at users/{uid} (role is "buyer" or "seller").
 * The seller's public shop data (delivery info, reviews, photo) lives in shops/{uid}, see [Shop].
 */
data class UserProfile(
    val role: String = "",
    val name: String = "",
    val email: String = "",
    val address: String = "",
    val phone: String = "",
    val photoUrl: String = ""
)

/** A buyer account: users/{uid} with role == "buyer". */
typealias Buyer = UserProfile

/** A seller account: users/{uid} with role == "seller" (its shop record is [Shop]). */
typealias Seller = UserProfile
