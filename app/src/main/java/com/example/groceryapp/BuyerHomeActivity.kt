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
import com.example.groceryapp.databinding.ActivityBuyerHomeBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener

/**
 * Buyer home: the buyer's tabs in a swipeable ViewPager2, kept in sync with the bottom navigation.
 *
 *  - Shops tab   -> [ShopsFragment] (grid of shops); tapping a shop opens [ProductsFragment] on top of it
 *  - Cart tab    -> [CartFragment]
 *  - Orders tab  -> [MyOrdersFragment] (cancel with dialog + undo Snackbar)
 *  - Profile tab -> opens [ProfileActivity] (an activity, so it is not a swipe page)
 *
 * Swipe left/right to move between Shops, Cart and Orders (Milestone 4 "Fragment Navigation").
 * A shop's products page opens in an overlay container above the pager; swiping is paused while
 * it is open, and Back closes it.
 *
 * It also watches the buyer's orders while the app is open and shows a notification
 * ([OrderNotifier]) every time the seller changes an order's status.
 */
class BuyerHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBuyerHomeBinding

    /** Bottom-navigation item of each swipe page, in swipe order. */
    private val tabs = listOf(R.id.nav_shops, R.id.nav_cart, R.id.nav_orders)

    /** orders where buyerId == this buyer; kept so the listener can be removed in onDestroy. */
    private var myOrdersQuery: Query? = null

    /** Last status seen per order id, to notice when the seller changes one. */
    private val lastStatus = HashMap<String, String>()

    /** The first Firebase snapshot only fills [lastStatus]; we don't notify for old changes. */
    private var firstSnapshot = true

    /** Compares each order's status with the last one seen and notifies the buyer about changes. */
    private val statusWatcher = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            snapshot.children.forEach { child ->
                val order = child.getValue(Order::class.java)?.apply { id = child.key ?: "" } ?: return@forEach
                val previous = lastStatus[order.id]
                if (!firstSnapshot && previous != null && previous != order.status) {
                    OrderNotifier.notifyStatus(applicationContext, order)
                }
                lastStatus[order.id] = order.status
            }
            firstSnapshot = false
        }

        override fun onCancelled(error: DatabaseError) { /* no notifications, the app still works */ }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBuyerHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPager()

        // Android 13+: ask once so order-status notifications can be shown.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // Start watching this buyer's orders for status changes.
        FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
            myOrdersQuery = FirebaseDatabase.getInstance().getReference("orders")
                .orderByChild("buyerId").equalTo(uid)
                .also { it.addValueEventListener(statusWatcher) }
        }
    }

    /** Creates the swipe pages and links them with the bottom navigation in both directions. */
    private fun setupPager() {
        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = tabs.size
            override fun createFragment(position: Int): Fragment = when (tabs[position]) {
                R.id.nav_cart -> CartFragment()
                R.id.nav_orders -> MyOrdersFragment()
                else -> ShopsFragment()
            }
        }
        // Keep all three pages alive so swiping back doesn't reload them from Firebase.
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
        // Tapping the current tab again closes a shop page opened on top of it.
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

    /** Closes a shop page opened on top of the tabs (does nothing if none is open). */
    private fun closeOverlay() {
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }

    /** Opens a shop's products page on top of the tabs (Back returns to the shops list). */
    fun openShop(shopId: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, ProductsFragment.newInstance(shopId))
            .addToBackStack("shop")
            .commit()
    }

    /** Switches to a tab (R.id.nav_shops / nav_cart / nav_orders), closing any shop page first. */
    fun selectTab(itemId: Int) {
        val page = tabs.indexOf(itemId)
        if (page < 0) return
        closeOverlay()
        binding.viewPager.currentItem = page
    }

    override fun onDestroy() {
        super.onDestroy()
        myOrdersQuery?.removeEventListener(statusWatcher)
    }
}
