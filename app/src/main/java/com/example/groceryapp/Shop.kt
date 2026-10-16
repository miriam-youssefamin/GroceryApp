package com.example.groceryapp

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.Exclude

/** A review a buyer left on a shop: shops/{shopId}/reviews/{buyerUid}. */
data class Review(
    @get:Exclude var userId: String = "",
    val rating: Int = 0,
    val comment: String = "",
    val userName: String = "",
    val timestamp: Long = 0L
)

data class Category(val key: String, val label: String, val emoji: String)

object Categories {
    val all = listOf(
        Category("restaurant", "Restaurants", "🍽️"),
        Category("takeout", "Takeout", "🍔"),
        Category("coffee", "Coffee", "☕"),
        Category("bakery", "Bakery", "🥐"),
        Category("sweets", "Sweets", "🍰"),
        Category("groceries", "Groceries", "🛒"),
        Category("pharmacy", "Pharmacy", "💊"),
        Category("other", "Other", "🛍️")
    )

    fun byKey(key: String): Category = all.firstOrNull { it.key == key } ?: all.last()
}

/** A seller's shop: shops/{sellerUid}. Ratings are computed from the reviews. */
data class Shop(
    var id: String = "",
    val name: String = "Shop",
    val category: String = "other",
    val deliveryFee: Double = DEFAULT_FEE,
    val deliveryMinutes: Int = DEFAULT_MINUTES,
    val reviews: List<Review> = emptyList(),
    val photoUrl: String = ""
) {
    val categoryInfo: Category get() = Categories.byKey(category)
    val ratingCount: Int get() = reviews.size
    val avgRating: Double get() = if (reviews.isEmpty()) 0.0 else reviews.map { it.rating }.average()

    fun feeText(): String = when {
        deliveryFee <= 0.0 -> "Free"
        deliveryFee % 1.0 == 0.0 -> "EGP %.0f".format(deliveryFee)
        else -> "EGP %.2f".format(deliveryFee)
    }

    fun ratingText(): String =
        if (ratingCount == 0) "★ New" else "★ %.1f (%d)".format(avgRating, ratingCount)

    fun metaLine(): String = "${ratingText()}  ·  🕒 $deliveryMinutes min  ·  🛵 ${feeText()}"

    companion object {
        const val DEFAULT_FEE = 15.0
        const val DEFAULT_MINUTES = 30

        fun from(s: DataSnapshot): Shop {
            val reviews = s.child("reviews").children.mapNotNull { r ->
                r.getValue(Review::class.java)?.apply { userId = r.key ?: "" }
            }
            return Shop(
                id = s.key ?: "",
                name = s.child("name").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: "Shop",
                deliveryFee = (s.child("deliveryFee").value as? Number)?.toDouble() ?: DEFAULT_FEE,
                deliveryMinutes = (s.child("deliveryMinutes").value as? Number)?.toInt() ?: DEFAULT_MINUTES,
                category = s.child("category").getValue(String::class.java) ?: "other",
                reviews = reviews,
                photoUrl = s.child("photoUrl").getValue(String::class.java) ?: ""
            )
        }
    }
}