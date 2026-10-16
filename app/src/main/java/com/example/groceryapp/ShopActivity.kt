package com.example.groceryapp

import android.content.Intent
import android.view.View
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.groceryapp.databinding.ActivityShopBinding
import com.example.groceryapp.databinding.DialogReviewBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/** A single shop's page: delivery info, rating, all its products, and reviews (buyers can add one). */
class ShopActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShopBinding
    private lateinit var shopId: String
    private lateinit var shopRef: com.google.firebase.database.DatabaseReference
    private lateinit var productsQuery: com.google.firebase.database.Query

    private val productAdapter = BuyerProductAdapter(
        cartControls = true,
        onAdd = { addToCart(it) },
        onRemove = { CartManager.decrement(it.id); refreshCart() }
    )
    private val reviewAdapter = ReviewAdapter()
    private var currentShop: Shop? = null
    private val myUid get() = FirebaseAuth.getInstance().currentUser?.uid

    private val shopListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val shop = if (snapshot.exists()) Shop.from(snapshot) else Shop(id = shopId)
            currentShop = shop
            bindShop(shop)
        }

        override fun onCancelled(error: DatabaseError) { /* ignore */ }
    }

    private val productsListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val list = snapshot.children.mapNotNull { child ->
                child.getValue(Product::class.java)?.apply { id = child.key ?: "" }
            }
            productAdapter.submitList(list)
            binding.tvNoProducts.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }

        override fun onCancelled(error: DatabaseError) { /* ignore */ }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShopBinding.inflate(layoutInflater)
        setContentView(binding.root)

        shopId = intent.getStringExtra(Roles.EXTRA_SHOP_ID) ?: run { finish(); return }
        val db = FirebaseDatabase.getInstance()
        shopRef = db.getReference("shops").child(shopId)
        productsQuery = db.getReference("products").orderByChild("sellerId").equalTo(shopId)

        binding.ivBack.setOnClickListener { finish() }
        binding.rvProducts.layoutManager = GridLayoutManager(this, 2)
        binding.rvProducts.adapter = productAdapter
        binding.rvReviews.layoutManager = LinearLayoutManager(this)
        binding.rvReviews.adapter = reviewAdapter
        binding.btnWriteReview.setOnClickListener { showReviewDialog() }
        binding.cardCartBar.setOnClickListener { startActivity(Intent(this, CartActivity::class.java)) }

        shopRef.addValueEventListener(shopListener)
        productsQuery.addValueEventListener(productsListener)
    }

    override fun onResume() {
        super.onResume()
        refreshCart()
    }

    private fun addToCart(product: Product) {
        val shop = currentShop ?: return
        if (CartManager.belongsToOtherShop(shop.id)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Start a new cart?")
                .setMessage("Your cart has items from ${CartManager.shopName}. Clear it and add from ${shop.name}?")
                .setPositiveButton("Start new cart") { _, _ ->
                    CartManager.clear()
                    addToCart(product)
                }
                .setNegativeButton("Keep current cart", null)
                .show()
            return
        }
        if (!CartManager.add(shop, product)) {
            Toast.makeText(this, "Only ${product.quantity} available", Toast.LENGTH_SHORT).show()
        }
        refreshCart()
    }

    private fun refreshCart() {
        productAdapter.refresh()
        val count = CartManager.totalCount()
        binding.cardCartBar.visibility = if (count == 0) View.GONE else View.VISIBLE
        binding.tvCartInfo.text = if (count == 1) "View cart · 1 item" else "View cart · $count items"
        binding.tvCartTotal.text = formatMoney(CartManager.subtotal())
    }

    private fun bindShop(shop: Shop) {
        binding.tvShopCategory.text = "${shop.categoryInfo.emoji} ${shop.categoryInfo.label}"
        binding.tvShopName.text = shop.name
        binding.tvRating.text = if (shop.ratingCount == 0) "New" else "★ %.1f".format(shop.avgRating)
        binding.tvRatingSub.text = when (shop.ratingCount) {
            0 -> "No reviews yet"
            1 -> "1 review"
            else -> "${shop.ratingCount} reviews"
        }
        binding.tvTime.text = "${shop.deliveryMinutes} min"
        binding.tvFee.text = shop.feeText()

        val sorted = shop.reviews.sortedByDescending { it.timestamp }
        reviewAdapter.submitList(sorted)
        binding.tvNoReviews.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
        binding.btnWriteReview.text =
            if (shop.reviews.any { it.userId == myUid }) "Edit your review" else "Write a review"
    }

    private fun showReviewDialog() {
        val uid = myUid ?: return
        val existing = currentShop?.reviews?.firstOrNull { it.userId == uid }
        val d = DialogReviewBinding.inflate(layoutInflater)
        d.ratingBar.rating = existing?.rating?.toFloat() ?: 0f
        d.etComment.setText(existing?.comment ?: "")

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Rate ${currentShop?.name ?: "this shop"}")
            .setView(d.root)
            .setPositiveButton("Submit", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).apply {
                setTextColor(getColor(R.color.green_primary))
                setOnClickListener {
                    val rating = d.ratingBar.rating.toInt()
                    if (rating < 1) {
                        Toast.makeText(this@ShopActivity, "Please choose a star rating", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    isEnabled = false
                    val comment = d.etComment.text.toString().trim()
                    fetchUserName { name ->
                        val review = Review(rating = rating, comment = comment, userName = name,
                            timestamp = System.currentTimeMillis())
                        shopRef.child("reviews").child(uid).setValue(review)
                            .addOnSuccessListener {
                                dialog.dismiss()
                                Toast.makeText(this@ShopActivity, "Thanks for your review!", Toast.LENGTH_SHORT).show()
                            }
                            .addOnFailureListener {
                                isEnabled = true
                                Toast.makeText(this@ShopActivity, it.message ?: "Couldn't save review",
                                    Toast.LENGTH_LONG).show()
                            }
                    }
                }
            }
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)
                .setTextColor(getColor(R.color.text_secondary))
        }
        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::shopRef.isInitialized) shopRef.removeEventListener(shopListener)
        if (::productsQuery.isInitialized) productsQuery.removeEventListener(productsListener)
    }
}
