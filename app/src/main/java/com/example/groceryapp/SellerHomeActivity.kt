package com.example.groceryapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.example.groceryapp.databinding.ActivitySellerHomeBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener

/**
 * Seller home: hosts the seller fragments behind a bottom navigation bar.
 *
 *  - Products tab -> [SellerProductsFragment] (grid of the seller's items); Edit opens [AddEditProductFragment]
 *  - Add tab      -> [AddEditProductFragment] (new item, with camera/gallery photo uploaded to S3)
 *  - Orders tab   -> [SellerOrdersFragment] (incoming orders, accept / reject); its icon shows a badge
 *                    with the number of orders still waiting for approval
 *  - Profile tab  -> opens [ProfileActivity]
 *
 * It also keeps the public shop record shops/{uid} in sync with the seller's account.
 */
class SellerHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySellerHomeBinding
    private var ordersQuery: Query? = null

    // Live count of orders waiting for this seller, shown as a badge on the Orders tab.
    private val ordersBadgeListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val waiting = snapshot.children.count {
                it.child("status").getValue(String::class.java) == Order.STATUS_PENDING
            }
            if (waiting > 0) {
                binding.bottomNav.getOrCreateBadge(R.id.nav_orders).number = waiting
            } else {
                binding.bottomNav.removeBadge(R.id.nav_orders)
            }
        }

        override fun onCancelled(error: DatabaseError) { /* ignore */ }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySellerHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_products -> { showRoot(SellerProductsFragment()); true }
                R.id.nav_add -> { showRoot(AddEditProductFragment.newInstance(null)); true }
                R.id.nav_orders -> { showRoot(SellerOrdersFragment()); true }
                R.id.nav_profile -> {
                    startActivity(Intent(this, ProfileActivity::class.java))
                    false // keep the previous tab highlighted
                }
                else -> false
            }
        }
        // Tapping "Products" while editing an item goes back to the product grid.
        binding.bottomNav.setOnItemReselectedListener { item ->
            if (item.itemId == R.id.nav_products) {
                supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            }
        }

        // "Products" is already the selected tab, so selecting it again would not fire the listener:
        // show the first screen directly.
        if (savedInstanceState == null) showRoot(SellerProductsFragment())

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid != null) {
            ordersQuery = FirebaseDatabase.getInstance().getReference("orders")
                .orderByChild("shopId").equalTo(uid)
                .also { it.addValueEventListener(ordersBadgeListener) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ordersQuery?.removeEventListener(ordersBadgeListener)
    }

    private fun showRoot(fragment: Fragment) {
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    /** Opens the editor for an existing product on top of the product grid (Back returns to the grid). */
    fun openEditor(productId: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, AddEditProductFragment.newInstance(productId))
            .addToBackStack("edit")
            .commit()
    }

    /** Switches to a bottom-navigation tab (R.id.nav_products / nav_add / nav_orders). */
    fun selectTab(itemId: Int) {
        binding.bottomNav.selectedItemId = itemId
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

    // Re-sync on every return so a changed name/photo reaches the shop record after coming back from Profile.
    override fun onStart() {
        super.onStart()
        syncShop()
        syncShopPhoto()
    }
}
