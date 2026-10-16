package com.example.groceryapp

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.groceryapp.databinding.ActivityCartBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class CartActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCartBinding
    private val adapter = CartAdapter { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCartBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvCart.layoutManager = LinearLayoutManager(this)
        binding.rvCart.adapter = adapter
        binding.ivBack.setOnClickListener { finish() }
        binding.btnBrowse.setOnClickListener { finish() }
        binding.btnPlaceOrder.setOnClickListener { placeOrder() }
        render()
    }

    private fun render() {
        val items = CartManager.items
        val empty = items.isEmpty()
        adapter.submitList(items)

        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.rvCart.visibility = if (empty) View.GONE else View.VISIBLE
        binding.cardSummary.visibility = if (empty) View.GONE else View.VISIBLE
        binding.tvCartShop.text = if (empty) "Nothing here yet" else "From ${CartManager.shopName}"

        binding.tvSubtotal.text = formatMoney(CartManager.subtotal())
        binding.tvFee.text = if (CartManager.fee() <= 0.0) "Free" else formatMoney(CartManager.fee())
        binding.tvTotal.text = formatMoney(CartManager.total())
    }

    private fun placeOrder() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val shopId = CartManager.shopId
        if (uid == null || shopId == null || CartManager.isEmpty()) return

        binding.btnPlaceOrder.isEnabled = false
        fetchUserName { name ->
            val lines = CartManager.items.associate {
                it.productId to mapOf("name" to it.name, "price" to it.price, "quantity" to it.qty)
            }
            val total = CartManager.total()
            val order = mapOf(
                "buyerId" to uid,
                "buyerName" to name,
                "shopId" to shopId,
                "shopName" to CartManager.shopName,
                "items" to lines,
                "subtotal" to CartManager.subtotal(),
                "deliveryFee" to CartManager.fee(),
                "total" to total,
                "status" to "pending",
                "timestamp" to System.currentTimeMillis()
            )
            FirebaseDatabase.getInstance().getReference("orders").push().setValue(order)
                .addOnSuccessListener {
                    CartManager.clear()
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Order placed 🎉")
                        .setMessage("Total: ${formatMoney(total)}\nPay cash on delivery when your order arrives.")
                        .setCancelable(false)
                        .setPositiveButton("Done") { _, _ -> finish() }
                        .show()
                }
                .addOnFailureListener {
                    binding.btnPlaceOrder.isEnabled = true
                    Toast.makeText(this, it.message ?: "Couldn't place the order", Toast.LENGTH_LONG).show()
                }
        }
    }
}
