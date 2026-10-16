package com.example.groceryapp

import android.graphics.Bitmap
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/** Clears the field's error as soon as the user edits it. */
fun TextInputLayout.clearErrorOnType() {
    editText?.doAfterTextChanged { error = null }
}

/** Loads the signed-in user's name into [target] ("Friend" if unavailable). */
fun AppCompatActivity.loadUserName(target: TextView) {
    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run { target.text = "Friend"; return }
    FirebaseDatabase.getInstance().getReference("users").child(uid).child("name")
        .addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                target.text = s.value?.toString()?.takeIf { it.isNotBlank() } ?: "Friend"
            }

            override fun onCancelled(e: DatabaseError) {
                target.text = "Friend"
            }
        })
}

/** Shows the signed-in user's S3 profile photo in the round header button; keeps the person icon if there is none. */
fun AppCompatActivity.loadHeaderPhoto(target: ImageView) {
    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
    FirebaseDatabase.getInstance().getReference("users").child(uid).child("photoUrl")
        .addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val url = s.value?.toString().orEmpty()
                if (url.isBlank() || !S3Helper.isConfigured()) return
                S3Helper.downloadImage(url, 300) { result ->
                    if (isDestroyed) return@downloadImage
                    result.onSuccess { bitmap ->
                        target.clipToOutline = true
                        target.scaleType = ImageView.ScaleType.CENTER_CROP
                        target.setPadding(0, 0, 0, 0)
                        target.imageTintList = null
                        target.setImageBitmap(bitmap)
                    }
                }
            }

            override fun onCancelled(e: DatabaseError) { /* keep the default icon */ }
        })
}

private val imageCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

/**
 * Shows the S3 picture at [url] in [photo] (hiding [fallback], e.g. the emoji). While loading, or if there is
 * no picture or the download fails, [fallback] stays visible. Safe to call from RecyclerView adapters.
 */
fun showRemoteImage(photo: ImageView, fallback: View, url: String) {
    photo.tag = url
    photo.visibility = View.GONE
    fallback.visibility = View.VISIBLE
    if (url.isBlank() || !S3Helper.isConfigured()) return

    fun show(bitmap: Bitmap) {
        photo.setImageBitmap(bitmap)
        photo.visibility = View.VISIBLE
        fallback.visibility = View.GONE
    }

    imageCache.get(url)?.let { show(it); return }
    S3Helper.downloadImage(url, 400) { result ->
        result.onSuccess { bitmap ->
            imageCache.put(url, bitmap)
            if (photo.tag == url) show(bitmap)
        }
    }
}

private val EMOJI_MAP: List<Pair<List<String>, String>> = listOf(
    listOf("burger", "برجر") to "🍔",
    listOf("pizza", "بيتزا") to "🍕",
    listOf("shawarma", "شاورما") to "🌯",
    listOf("taco", "tacos") to "🌮",
    listOf("sandwich", "ساندوتش", "سندوتش") to "🥪",
    listOf("fries", "بطاطس مقلية") to "🍟",
    listOf("hotdog") to "🌭",
    listOf("kebab", "kofta", "كباب", "كفتة") to "🍢",
    listOf("koshari", "koshary", "soup", "كشري", "شوربة") to "🍲",
    listOf("noodles", "ramen", "نودلز") to "🍜",
    listOf("sushi", "سوشي") to "🍣",
    listOf("salad", "سلطة") to "🥗",
    listOf("cake", "تورتة", "كيك") to "🍰",
    listOf("donut", "doughnut", "دونات") to "🍩",
    listOf("cookie", "cookies", "كوكيز", "بسكويت") to "🍪",
    listOf("croissant", "كرواسون") to "🥐",
    listOf("waffle", "وافل") to "🧇",
    listOf("pancake", "بان كيك") to "🥞",
    listOf("icecream", "gelato", "dessert", "آيس كريم", "ايس كريم") to "🍨",
    listOf("smoothie", "milkshake", "shake", "soda", "cola", "pepsi", "سموذي", "ميلك شيك") to "🥤",
    listOf("latte", "cappuccino", "espresso", "americano", "mocha", "cortado", "لاتيه", "كابتشينو", "اسبريسو") to "☕",
    listOf("vitamin", "vitamins", "pill", "pills", "فيتامين") to "💊",
    listOf("watermelon", "بطيخ") to "🍉",
    listOf("strawberry", "فراولة") to "🍓",
    listOf("grape", "عنب") to "🍇",
    listOf("apple", "تفاح") to "🍎",
    listOf("banana", "موز") to "🍌",
    listOf("orange", "برتقال") to "🍊",
    listOf("lemon", "ليمون") to "🍋",
    listOf("mango", "مانجو") to "🥭",
    listOf("tomato", "طماطم") to "🍅",
    listOf("potato", "بطاطس") to "🥔",
    listOf("carrot", "جزر") to "🥕",
    listOf("cucumber", "خيار") to "🥒",
    listOf("onion", "بصل") to "🧅",
    listOf("garlic", "ثوم") to "🧄",
    listOf("pepper", "فلفل") to "🌶️",
    listOf("milk", "yogurt", "yoghurt", "laban", "لبن", "حليب", "زبادي") to "🥛",
    listOf("cheese", "جبن", "جبنة") to "🧀",
    listOf("butter", "زبدة") to "🧈",
    listOf("egg", "بيض") to "🥚",
    listOf("bread", "toast", "عيش", "خبز") to "🍞",
    listOf("chicken", "دجاج", "فراخ") to "🍗",
    listOf("meat", "beef", "steak", "لحم") to "🥩",
    listOf("fish", "سمك") to "🐟",
    listOf("rice", "أرز", "رز") to "🍚",
    listOf("pasta", "macaroni", "مكرونة") to "🍝",
    listOf("water", "مياه", "ماء") to "💧",
    listOf("juice", "عصير") to "🧃",
    listOf("coffee", "قهوة") to "☕",
    listOf("tea", "شاي") to "🍵",
    listOf("chocolate", "شوكولاتة") to "🍫",
    listOf("honey", "عسل") to "🍯"
)

/** Picks a fitting emoji for a product name; falls back to a shopping cart. */
fun productEmoji(name: String): String {
    val words = name.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
    for ((keys, emoji) in EMOJI_MAP) {
        if (words.any { w -> keys.any { k -> w == k || w == k + "s" } }) return emoji
    }
    return "🛒"
}

/** Reads the signed-in user's name once ("Customer" if unavailable). */
fun fetchUserName(onResult: (String) -> Unit) {
    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return onResult("Customer")
    FirebaseDatabase.getInstance().getReference("users").child(uid).child("name")
        .addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                onResult(s.value?.toString()?.takeIf { it.isNotBlank() } ?: "Customer")
            }

            override fun onCancelled(e: DatabaseError) = onResult("Customer")
        })
}

fun formatMoney(value: Double): String = "EGP %.2f".format(value)