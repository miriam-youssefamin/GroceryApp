package com.example.groceryapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.groceryapp.databinding.ActivityBuyerHomeBinding
import com.google.android.material.chip.Chip
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class BuyerHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBuyerHomeBinding
    private val db = FirebaseDatabase.getInstance()
    private val shopsRef = db.getReference("shops")
    private val productsRef = db.getReference("products")

    private val adapter = ShopSectionAdapter { shop ->
        startActivity(Intent(this, ShopActivity::class.java).putExtra(Roles.EXTRA_SHOP_ID, shop.id))
    }

    private var shops: List<Shop> = emptyList()
    private var products: List<Product> = emptyList()
    private var query = ""
    private val chipKeys = HashMap<Int, String>()
    private var selectedKey = "all"

    private val shopsListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            shops = snapshot.children.map { Shop.from(it) }
            rebuild()
        }

        override fun onCancelled(error: DatabaseError) { /* ignore */ }
    }

    private val productsListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            products = snapshot.children.mapNotNull { child ->
                child.getValue(Product::class.java)?.apply { id = child.key ?: "" }
            }
            rebuild()
        }

        override fun onCancelled(error: DatabaseError) { /* ignore */ }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBuyerHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvShops.layoutManager = LinearLayoutManager(this)
        binding.rvShops.adapter = adapter

        binding.ivProfileIcon.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
        binding.etSearch.doAfterTextChanged {
            query = it?.toString()?.trim() ?: ""
            rebuild()
        }
        binding.ivCart.setOnClickListener {
            startActivity(Intent(this, CartActivity::class.java))
        }
        setupChips()

        shopsRef.addValueEventListener(shopsListener)
        productsRef.addValueEventListener(productsListener)
    }

    private fun setupChips() {
        val options = listOf("all" to "All", "top" to "⭐ Top rated", "free" to "🛵 Free delivery") +
                Categories.all.map { it.key to "${it.emoji} ${it.label}" }
        options.forEachIndexed { i, (key, label) ->
            val chip = layoutInflater.inflate(R.layout.item_chip, binding.chipGroup, false) as Chip
            chip.id = View.generateViewId()
            chip.text = label
            chipKeys[chip.id] = key
            binding.chipGroup.addView(chip)
            if (i == 0) chip.isChecked = true
        }
        binding.chipGroup.setOnCheckedStateChangeListener { _, ids ->
            selectedKey = ids.firstOrNull()?.let { chipKeys[it] } ?: "all"
            rebuild()
        }
    }

    private fun updateCartBadge() {
        val count = CartManager.totalCount()
        binding.tvCartBadge.visibility = if (count == 0) View.GONE else View.VISIBLE
        binding.tvCartBadge.text = if (count > 9) "9+" else count.toString()
    }

    /** Groups products by shop, applies search + chip filters, and refreshes the list. */
    private fun rebuild() {
        val bySeller = products.groupBy { it.sellerId }
        // Products whose seller has no shop record yet still get a (default) shop section.
        val allShops = shops + bySeller.keys.filter { id -> shops.none { it.id == id } }.map { Shop(id = it) }


        val sections = allShops.mapNotNull { shop ->
            val shopProducts = bySeller[shop.id].orEmpty()
            if (shopProducts.isEmpty()) return@mapNotNull null
            when (selectedKey) {
                "all" -> {}
                "free" -> if (shop.deliveryFee > 0.0) return@mapNotNull null
                "top" -> if (shop.avgRating < 4.0) return@mapNotNull null
                else -> if (shop.category != selectedKey) return@mapNotNull null
            }
            when {
                query.isEmpty() || shop.name.contains(query, ignoreCase = true) -> ShopSection(shop, shopProducts)
                else -> {
                    val matches = shopProducts.filter { it.name.contains(query, ignoreCase = true) }
                    if (matches.isEmpty()) null else ShopSection(shop, matches)
                }
            }
        }.sortedWith(compareByDescending<ShopSection> { it.shop.avgRating }.thenBy { it.shop.name.lowercase() })

        adapter.submitList(sections)
        binding.tvCount.text = if (sections.size == 1) "1 shop" else "${sections.size} shops"
        binding.tvEmpty.text = if (products.isEmpty()) "No shops available yet" else "No matching shops"
        binding.tvEmpty.visibility = if (sections.isEmpty()) View.VISIBLE else View.GONE
    }

    // Reload on every return so an edited name shows up after coming back from Profile.
    override fun onStart() {
        super.onStart()
        loadUserName(binding.tvWelcome)
        loadHeaderPhoto(binding.ivProfileIcon)
        updateCartBadge()
    }

    override fun onDestroy() {
        super.onDestroy()
        shopsRef.removeEventListener(shopsListener)
        productsRef.removeEventListener(productsListener)
    }
}