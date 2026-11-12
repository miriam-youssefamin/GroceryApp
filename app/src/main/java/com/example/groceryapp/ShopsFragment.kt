package com.example.groceryapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import com.example.groceryapp.databinding.FragmentShopsBinding
import com.google.android.material.chip.Chip
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/** Buyer "Shops" tab: every shop that has products, in a 2-column RecyclerView grid (photo + name), with search and filters. */
class ShopsFragment : Fragment() {

    private var _binding: FragmentShopsBinding? = null
    private val binding get() = _binding!!

    private val db = FirebaseDatabase.getInstance()
    private val shopsRef = db.getReference("shops")
    private val productsRef = db.getReference("products")

    private val adapter = ShopGridAdapter { shop ->
        (requireActivity() as BuyerHomeActivity).openShop(shop.id)
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentShopsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.rvShops.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvShops.adapter = adapter

        binding.ivProfileIcon.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), ProfileActivity::class.java))
        }
        binding.ivCart.setOnClickListener {
            (requireActivity() as BuyerHomeActivity).selectTab(R.id.nav_cart)
        }
        binding.etSearch.doAfterTextChanged {
            query = it?.toString()?.trim() ?: ""
            rebuild()
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
        val b = _binding ?: return
        val count = CartManager.totalCount()
        b.tvCartBadge.visibility = if (count == 0) View.GONE else View.VISIBLE
        b.tvCartBadge.text = if (count > 9) "9+" else count.toString()
    }

    /** Applies the search + chip filters to the shops that have products and refreshes the grid. */
    private fun rebuild() {
        val b = _binding ?: return
        val bySeller = products.groupBy { it.sellerId }
        // Products whose seller has no shop record yet still get a (default) shop card.
        val allShops = shops + bySeller.keys.filter { id -> shops.none { it.id == id } }.map { Shop(id = it) }

        val visible = allShops.filter { shop ->
            val shopProducts = bySeller[shop.id].orEmpty()
            if (shopProducts.isEmpty()) return@filter false
            when (selectedKey) {
                "all" -> {}
                "free" -> if (shop.deliveryFee > 0.0) return@filter false
                "top" -> if (shop.avgRating < 4.0) return@filter false
                else -> if (shop.category != selectedKey) return@filter false
            }
            query.isEmpty() ||
                    shop.name.contains(query, ignoreCase = true) ||
                    shopProducts.any { it.name.contains(query, ignoreCase = true) }
        }.sortedWith(compareByDescending<Shop> { it.avgRating }.thenBy { it.name.lowercase() })

        adapter.submitList(visible)
        b.tvCount.text = if (visible.size == 1) "1 shop" else "${visible.size} shops"
        b.tvEmpty.text = if (products.isEmpty()) "No shops available yet" else "No matching shops"
        b.tvEmpty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
    }

    // Reload on every return so an edited name/photo shows up after coming back from Profile.
    override fun onStart() {
        super.onStart()
        val host = requireActivity() as AppCompatActivity
        host.loadUserName(binding.tvWelcome)
        host.loadHeaderPhoto(binding.ivProfileIcon)
        updateCartBadge()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        shopsRef.removeEventListener(shopsListener)
        productsRef.removeEventListener(productsListener)
        _binding = null
    }
}
