package com.example.groceryapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.groceryapp.databinding.ActivitySellerHomeBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener

class SellerHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySellerHomeBinding
    private lateinit var adapter: SellerProductAdapter
    private val productsRef = FirebaseDatabase.getInstance().getReference("products")
    private var productsQuery: Query? = null
    private var shopRef: DatabaseReference? = null

    private val shopListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            binding.tvShopMeta.text =
                if (snapshot.exists()) Shop.from(snapshot).metaLine() else "Setting up your shop…"
        }

        override fun onCancelled(error: DatabaseError) { /* ignore */ }
    }

    private val productsListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val list = snapshot.children.mapNotNull { child ->
                child.getValue(Product::class.java)?.apply { id = child.key ?: "" }
            }
            adapter.submitList(list)
            val inStock = list.count { it.inStock }
            binding.tvStatTotal.text = list.size.toString()
            binding.tvStatIn.text = inStock.toString()
            binding.tvStatOut.text = (list.size - inStock).toString()
            binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }

        override fun onCancelled(error: DatabaseError) {
            Toast.makeText(this@SellerHomeActivity, error.message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySellerHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = SellerProductAdapter(
            onEdit = { product ->
                startActivity(
                    Intent(this, AddEditProductActivity::class.java)
                        .putExtra(Roles.EXTRA_PRODUCT_ID, product.id)
                )
            },
            onDelete = { product -> confirmDelete(product) }
        )
        binding.rvProducts.layoutManager = LinearLayoutManager(this)
        binding.rvProducts.adapter = adapter

        binding.ivProfileIcon.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
        binding.fabAdd.setOnClickListener {
            startActivity(Intent(this, AddEditProductActivity::class.java))
        }

        binding.cardShopInfo.setOnClickListener {
            startActivity(Intent(this, ShopSettingsActivity::class.java))
        }

        loadProducts()
        listenToShop()
    }

    private fun listenToShop() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        shopRef = FirebaseDatabase.getInstance().getReference("shops").child(uid).also {
            it.addValueEventListener(shopListener)
        }
    }

    /** Makes sure shops/{uid} exists (default delivery info) and its name matches the account name. */
    private fun syncShop() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        fetchUserName { fetched ->
            val name = if (fetched == "Customer") "My shop" else fetched
            val ref = FirebaseDatabase.getInstance().getReference("shops").child(uid)
            ref.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) {
                        ref.updateChildren(
                            mapOf(
                                "name" to name,
                                "category" to "other",
                                "deliveryFee" to Shop.DEFAULT_FEE,
                                "deliveryMinutes" to Shop.DEFAULT_MINUTES
                            )
                        )
                    } else if (snapshot.child("name").getValue(String::class.java) != name) {
                        ref.child("name").setValue(name)
                    }
                }

                override fun onCancelled(error: DatabaseError) { /* ignore */ }
            })
        }
    }

    /** Copies the seller's profile photo URL onto shops/{uid} so buyers can see it (only if the shop record exists). */
    private fun syncShopPhoto() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = FirebaseDatabase.getInstance()
        db.getReference("users").child(uid).child("photoUrl")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(userSnap: DataSnapshot) {
                    val url = userSnap.value?.toString().orEmpty()
                    if (url.isBlank()) return
                    val shopRef = db.getReference("shops").child(uid)
                    shopRef.addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(shopSnap: DataSnapshot) {
                            if (shopSnap.exists() && shopSnap.child("photoUrl").value?.toString() != url) {
                                shopRef.child("photoUrl").setValue(url)
                            }
                        }

                        override fun onCancelled(error: DatabaseError) { /* ignore */ }
                    })
                }

                override fun onCancelled(error: DatabaseError) { /* ignore */ }
            })
    }

    // Reload on every return so an edited name shows up after coming back from Profile.
    override fun onStart() {
        super.onStart()
        loadUserName(binding.tvWelcome)
        loadHeaderPhoto(binding.ivProfileIcon)
        syncShop()
        syncShopPhoto()
    }

    private fun loadProducts() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        productsQuery = productsRef.orderByChild("sellerId").equalTo(uid).also {
            it.addValueEventListener(productsListener)
        }
    }

    private fun confirmDelete(product: Product) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete product")
            .setMessage("Remove \"${product.name}\" from your shop?")
            .setPositiveButton("Delete") { _, _ ->
                productsRef.child(product.id).removeValue()
            }
            .setNegativeButton("Cancel", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                        .setTextColor(getColor(R.color.danger))
                }
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        productsQuery?.removeEventListener(productsListener)
        shopRef?.removeEventListener(shopListener)
    }
}