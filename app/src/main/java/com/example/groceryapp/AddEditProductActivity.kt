package com.example.groceryapp

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.groceryapp.databinding.ActivityAddEditProductBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class AddEditProductActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddEditProductBinding
    private val productsRef = FirebaseDatabase.getInstance().getReference("products")
    private var editingId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddEditProductBinding.inflate(layoutInflater)
        setContentView(binding.root)

        listOf(binding.tilName, binding.tilPrice, binding.tilQuantity).forEach { it.clearErrorOnType() }

        editingId = intent.getStringExtra(Roles.EXTRA_PRODUCT_ID)
        if (editingId != null) {
            binding.tvHeading.text = "Edit product"
            binding.tvSubtitle.text = "Update the product details"
            loadExisting(editingId!!)
        }

        binding.btnSave.setOnClickListener { save() }
    }

    private fun loadExisting(id: String) {
        productsRef.child(id).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val product = snapshot.getValue(Product::class.java) ?: return
                binding.etName.setText(product.name)
                binding.etPrice.setText(product.price.toString())
                binding.etQuantity.setText(product.quantity.toString())
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@AddEditProductActivity, error.message, Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun save() {
        val name = binding.etName.text.toString().trim()
        val priceText = binding.etPrice.text.toString().trim()
        val qtyText = binding.etQuantity.text.toString().trim()
        val uid = FirebaseAuth.getInstance().currentUser?.uid

        if (name.isEmpty()) { binding.tilName.error = "Enter a product name"; return }
        val price = priceText.toDoubleOrNull()
        if (price == null || price < 0) { binding.tilPrice.error = "Enter a valid price"; return }
        val quantity = qtyText.toIntOrNull()
        if (quantity == null || quantity < 0) { binding.tilQuantity.error = "Enter a valid quantity"; return }
        if (uid == null) { Toast.makeText(this, "Not signed in", Toast.LENGTH_LONG).show(); return }

        val product = Product(name = name, price = price, quantity = quantity, sellerId = uid)
        val ref = if (editingId != null) productsRef.child(editingId!!) else productsRef.push()

        binding.btnSave.isEnabled = false
        ref.setValue(product)
            .addOnSuccessListener { finish() }
            .addOnFailureListener {
                binding.btnSave.isEnabled = true
                Toast.makeText(this, it.message ?: "Save failed", Toast.LENGTH_LONG).show()
            }
    }
}
