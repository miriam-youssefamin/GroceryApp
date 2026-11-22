package com.example.groceryapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.example.groceryapp.databinding.ActivitySellerHomeBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener

/**
 * Seller home: the seller's tabs in a swipeable ViewPager2, kept in sync with the bottom navigation.
 *
 *  - Products tab -> [SellerProductsFragment] (grid of the seller's items); Edit opens [AddEditProductFragment]
 *                    in an overlay above the tabs (Back returns to the grid)
 *  - Add tab      -> [AddEditProductFragment] (new item, with camera/gallery photo uploaded to S3)
 *  - Orders tab   -> [SellerOrdersFragment] (incoming orders: accept / reject / next status); its icon shows
 *                    a badge with the number of orders still waiting for approval
 *  - Profile tab  -> opens [ProfileActivity] (an activity, so it is not a swipe page)
 *
 * While the app is open, a "New order" notification is shown whenever a buyer places an order
 * with this shop. It also keeps the public shop record shops/{uid} in sync with the seller's account.
 */
class SellerHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySellerHomeBinding
    private var ordersQuery: Query? = null

    /** Bottom-navigation item of each swipe page, in swipe order. */
    private val tabs = listOf(R.id.nav_products, R.id.nav_add, R.id.nav_orders)

    /** Order ids already seen, so only brand-new orders trigger a notification. */
    private val seenOrderIds = HashSet<String>()

    /** The first snapshot only records the existing orders; no notifications for those. */
    private var firstSnapshot = true

    /**
     * Watches this shop's orders:
     *  - badge on the Orders tab = number of orders waiting for approval
     *  - "New order" notification for every pending order that wasn't there before
     */
    private val ordersListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val waiting = snapshot.children.count {
                it.child("status").getValue(String::class.java) == Order.STATUS_PENDING
            }
            if (waiting > 0) {
                binding.bottomNav.getOrCreateBadge(R.id.nav_orders).number = waiting
            } else {
                binding.bottomNav.removeBadge(R.id.nav_orders)
            }

            snapshot.children.forEach { child ->
                val id = child.key ?: return@forEach
                val isNew = seenOrderIds.add(id) // false if we already knew this order
                val pending = child.child("status").getValue(String::class.java) == Order.STATUS_PENDING
                if (isNew && pending && !firstSnapshot) notifyNewOrder(id, child)
            }
            firstSnapshot = false
        }

        override fun onCancelled(error: DatabaseError) { /* ignore */ }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySellerHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPager()

        // Android 13+: ask once so "New order" notifications can be shown.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // The shop id is the seller's uid, so this query returns exactly this shop's orders.
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid != null) {
            ordersQuery = FirebaseDatabase.getInstance().getReference("orders")
                .orderByChild("shopId").equalTo(uid)
                .also { it.addValueEventListener(ordersListener) }
        }
    }

    /** Creates the swipe pages and links them with the bottom navigation in both directions. */
    private fun setupPager() {
        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = tabs.size
            override fun createFragment(position: Int): Fragment = when (tabs[position]) {
                R.id.nav_add -> AddEditProductFragment.newInstance(null)
                R.id.nav_orders -> SellerOrdersFragment()
                else -> SellerProductsFragment()
            }
        }
        // Keep all pages alive so swiping back doesn't reload them (and keeps a half-filled Add form).
        binding.viewPager.offscreenPageLimit = tabs.size - 1

        // Swipe -> highlight the matching bottom-nav item (setting isChecked doesn't fire the listener).
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.bottomNav.menu.findItem(tabs[position]).isChecked = true
            }
        })

        // Bottom-nav tap -> scroll the pager to that page.
        binding.bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId == R.id.nav_profile) {
                startActivity(Intent(this, ProfileActivity::class.java))
                false // keep the current tab highlighted
            } else {
                selectTab(item.itemId)
                true
            }
        }
        // Tapping the current tab again closes the product editor opened on top of it.
        binding.bottomNav.setOnItemReselectedListener { closeOverlay() }

        // The overlay is visible only while a screen is on the back stack; no swiping underneath it.
        supportFragmentManager.addOnBackStackChangedListener { updateOverlay() }
        updateOverlay() // also restores the right state after a rotation
    }

    /** Shows/hides the overlay container and pauses swiping while it is open. */
    private fun updateOverlay() {
        val open = supportFragmentManager.backStackEntryCount > 0
        binding.fragmentContainer.visibility = if (open) View.VISIBLE else View.GONE
        binding.viewPager.isUserInputEnabled = !open
    }

    /** Closes the product editor opened on top of the tabs (does nothing if none is open). */
    private fun closeOverlay() {
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }

    /** "New order 🛒 — Sara placed an EGP 120.00 delivery order." Tapping it opens the app. */
    private fun notifyNewOrder(orderId: String, order: DataSnapshot) {
        val buyer = order.child("buyerName").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: "A customer"
        val total = (order.child("total").value as? Number)?.toDouble() ?: 0.0
        val method = if (order.child("deliveryMethod").getValue(String::class.java) == Order.METHOD_PICKUP)
            "pickup" else "delivery"
        OrderNotifier.show(
            applicationContext, orderId.hashCode(),
            "New order 🛒",
            "$buyer placed a ${formatMoney(total)} $method order. Open Orders to accept or reject it."
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        ordersQuery?.removeEventListener(ordersListener)
    }

    /** Opens the editor for an existing product on top of the tabs (Back returns to the grid). */
    fun openEditor(productId: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, AddEditProductFragment.newInstance(productId))
            .addToBackStack("edit")
            .commit()
    }

    /** Switches to a tab (R.id.nav_products / nav_add / nav_orders), closing the editor first. */
    fun selectTab(itemId: Int) {
        val page = tabs.indexOf(itemId)
        if (page < 0) return
        closeOverlay()
        binding.viewPager.currentItem = page
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
