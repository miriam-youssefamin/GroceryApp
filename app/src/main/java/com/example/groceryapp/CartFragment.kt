package com.example.groceryapp

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.groceryapp.databinding.FragmentCartBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Buyer "Cart" tab: review the items, adjust quantities, choose delivery or pickup, see the
 * (dynamically calculated) delivery fee and total, clear the cart, or place the order.
 *
 * Checkout constraints (Milestone 4):
 *  - Delivery or pickup: picked with the toggle; pickup costs no delivery fee.
 *  - Delivery needs a valid address ([DeliveryPricing.validateAddress]); it is pre-filled from the profile.
 *  - Delivery fee = base + distance + rush hour + large order ([DeliveryPricing.quote]).
 *    The distance is measured between the shop's saved location and the buyer's phone location;
 *    the buyer can't pick it. The rush hour uses server time ([ServerClock]), not the phone clock.
 *  - Quantity check: client side in [CartManager], server side in [OrderRepository.placeOrder].
 *  - Stock is decremented by [OrderRepository.placeOrder] when the order is saved.
 */
class CartFragment : Fragment() {

    private var _binding: FragmentCartBinding? = null
    private val binding get() = _binding!!

    // Any +/- tap in the list re-renders the totals (fee can change with the item count).
    private val adapter = CartAdapter { render() }

    /** True while waiting for the phone's location (the button shows "Finding you…"). */
    private var locating = false

    /** Asks for the location permission; on success gets the location right away. */
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { it }) fetchBuyerLocation()
            else context?.let {
                Toast.makeText(it, "Location is needed to price delivery. You can still choose pickup.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCartBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.rvCart.layoutManager = LinearLayoutManager(requireContext())
        binding.rvCart.adapter = adapter
        binding.btnBrowse.setOnClickListener {
            (requireActivity() as BuyerHomeActivity).selectTab(R.id.nav_shops)
        }
        binding.tvClearCart.setOnClickListener { confirmClear() }
        binding.btnPlaceOrder.setOnClickListener { placeOrder() }
        binding.btnUseLocation.setOnClickListener { requestBuyerLocation() }
        ServerClock.start() // rush hour follows the server's time, not the phone clock
        setupDeliveryOptions()
        render()
    }

    // The cart is a swipe page that stays alive: refresh it each time it becomes the visible page,
    // because items may have been added on a shop page in the meantime.
    override fun onResume() {
        super.onResume()
        render()
        refreshShop()
        // Permission already given: measure the distance without making the buyer tap.
        if (!CartManager.isEmpty() && !CartManager.isPickup() && CartManager.buyerLat == null &&
            LocationHelper.hasPermission(requireContext())
        ) fetchBuyerLocation()
    }

    /** Re-reads the shop (base fee, location) in case the seller changed it after items were added. */
    private fun refreshShop() {
        val id = CartManager.shopId ?: return
        FirebaseDatabase.getInstance().getReference("shops").child(id)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        CartManager.updateShop(Shop.from(snapshot))
                        render()
                    }
                }

                override fun onCancelled(error: DatabaseError) { /* keep the values we have */ }
            })
    }

    /** "Use my location" button: asks for the permission first if needed. */
    private fun requestBuyerLocation() {
        if (LocationHelper.hasPermission(requireContext())) fetchBuyerLocation()
        else locationPermission.launch(LocationHelper.PERMISSIONS)
    }

    /** Gets the phone's location and saves it as the delivery location; the fee updates in [render]. */
    private fun fetchBuyerLocation() {
        if (locating) return
        locating = true
        render()
        LocationHelper.getCurrentLocation(requireContext()) { location ->
            locating = false
            if (location != null) {
                CartManager.setBuyerLocation(location.latitude, location.longitude)
            } else {
                context?.let {
                    Toast.makeText(it, "Couldn't get your location. Make sure location is turned on.", Toast.LENGTH_LONG).show()
                }
            }
            render()
        }
    }

    /** Wires the delivery/pickup toggle and the address field to [CartManager]. */
    private fun setupDeliveryOptions() {
        // Restore the buyer's previous choices.
        binding.toggleMethod.check(
            if (CartManager.isPickup()) R.id.btnMethodPickup else R.id.btnMethodDelivery
        )
        binding.etDeliveryAddress.setText(CartManager.deliveryAddress)

        binding.toggleMethod.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener // ignore the "unchecked" event of the other button
            CartManager.deliveryMethod =
                if (checkedId == R.id.btnMethodPickup) Order.METHOD_PICKUP else Order.METHOD_DELIVERY
            render()
            // Switched to delivery and the permission is already given: measure the distance now.
            if (!CartManager.isPickup() && CartManager.buyerLat == null && LocationHelper.hasPermission(requireContext())) {
                fetchBuyerLocation()
            }
        }
        binding.etDeliveryAddress.doAfterTextChanged {
            CartManager.deliveryAddress = it?.toString().orEmpty()
            binding.tilDeliveryAddress.error = null
        }

        // Pre-fill the address from the buyer's profile (users/{uid}/address) if nothing is typed yet.
        if (CartManager.deliveryAddress.isBlank()) loadProfileAddress()
    }

    /** Reads users/{uid}/address once and puts it in the address field if the buyer hasn't typed anything. */
    private fun loadProfileAddress() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseDatabase.getInstance().getReference("users").child(uid).child("address")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val address = snapshot.value?.toString().orEmpty()
                    val field = _binding?.etDeliveryAddress ?: return
                    if (field.text.isNullOrBlank() && address.isNotBlank()) field.setText(address)
                }

                override fun onCancelled(error: DatabaseError) { /* the buyer can still type it */ }
            })
    }

    /** Redraws the list, the delivery section and the price summary from [CartManager]. */
    private fun render() {
        val b = _binding ?: return
        val items = CartManager.items
        val empty = items.isEmpty()
        adapter.submitList(items)

        // Empty cart: show only the "Browse shops" state.
        b.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        b.scrollCart.visibility = if (empty) View.GONE else View.VISIBLE
        b.cardSummary.visibility = if (empty) View.GONE else View.VISIBLE
        b.tvClearCart.visibility = if (empty) View.GONE else View.VISIBLE
        b.tvCartShop.text = if (empty) "Nothing here yet" else "From ${CartManager.shopName}"

        // Delivery section: address + distance only make sense for delivery.
        val pickup = CartManager.isPickup()
        b.deliveryOptions.visibility = if (pickup) View.GONE else View.VISIBLE
        b.tvPickupNote.visibility = if (pickup) View.VISIBLE else View.GONE
        renderLocation(b)

        // Price summary. While delivery can't be priced (no location yet, too far...), say why instead of a fee.
        val quote = CartManager.quote()
        val problem = CartManager.deliveryProblem()
        b.tvSubtotal.text = formatMoney(CartManager.subtotal())
        b.tvFeeLabel.text = if (pickup) "Pickup" else "Delivery fee"
        b.tvFee.text = when {
            problem != null -> "—"
            quote.total <= 0.0 -> "Free"
            else -> formatMoney(quote.total)
        }
        b.tvFeeBreakdown.text = when {
            pickup -> "No delivery fee for pickup"
            problem != null -> problem
            else -> quote.breakdown()
        }
        b.tvTotal.text = formatMoney(CartManager.total())
        b.btnPlaceOrder.text = if (pickup) "Place order · Pay at pickup" else "Place order · Cash on delivery"
    }

    /** Location line in the delivery card: distance to the shop, or what is still missing. */
    private fun renderLocation(b: FragmentCartBinding) {
        val shopHasLocation = CartManager.shopLat != null && CartManager.shopLng != null
        val km = CartManager.distanceKm()
        b.tvLocationStatus.text = when {
            !shopHasLocation -> "📍 This shop hasn't set its location yet"
            locating -> "📍 Finding your location…"
            km == null -> "📍 Location not set"
            else -> "📍 ${formatKm(km)} from ${CartManager.shopName}"
        }
        b.btnUseLocation.visibility = if (shopHasLocation) View.VISIBLE else View.GONE
        b.btnUseLocation.isEnabled = !locating
        b.btnUseLocation.text = if (CartManager.buyerLat == null) "Use my location" else "Update"
    }

    /** Alert dialog: asks for confirmation, then empties the cart. */
    private fun confirmClear() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Clear cart?")
            .setMessage("Remove all items from your cart?")
            .setPositiveButton("Clear") { _, _ ->
                CartManager.clear()
                render()
            }
            .setNegativeButton("Cancel", null)
            .create()
            .also { dialog ->
                // Red "Clear" button, because the action can't be undone.
                dialog.setOnShowListener {
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                        .setTextColor(requireContext().getColor(R.color.danger))
                }
            }
            .show()
    }

    /**
     * Client-side checks before anything is sent to Firebase.
     * Returns the cleaned delivery address ("" for pickup), or null if a check failed (error already shown).
     */
    private fun validateCheckout(): String? {
        // 1) Quantity check (client side): nothing may exceed the stock the buyer saw.
        val tooMany = CartManager.overStockLines()
        if (tooMany.isNotEmpty()) {
            val line = tooMany.first()
            showError("Only ${line.maxQty} of \"${line.name}\" available. Please lower the quantity.")
            return null
        }
        if (CartManager.isPickup()) return ""

        // 2) Delivery needs a measured distance within range (shop + buyer location).
        CartManager.deliveryProblem()?.let { problem ->
            showError(problem)
            return null
        }

        // 3) Delivery orders need a valid address.
        val address = binding.etDeliveryAddress.text?.toString()?.trim().orEmpty()
        DeliveryPricing.validateAddress(address)?.let { error ->
            binding.tilDeliveryAddress.error = error
            binding.scrollCart.post { binding.scrollCart.smoothScrollTo(0, binding.cardDelivery.top) }
            binding.etDeliveryAddress.requestFocus()
            return null
        }
        return address
    }

    /** Builds the [Order] from the cart and hands it to [OrderRepository.placeOrder]. */
    private fun placeOrder() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val shopId = CartManager.shopId
        if (uid == null || shopId == null || CartManager.isEmpty()) return
        val address = validateCheckout() ?: return

        val ctx = requireContext()
        binding.btnPlaceOrder.isEnabled = false
        fetchUserName { name ->
            val pickup = CartManager.isPickup()
            val total = CartManager.total()
            val order = Order(
                buyerId = uid,
                buyerName = name,
                shopId = shopId,
                shopName = CartManager.shopName,
                items = CartManager.items.associate {
                    it.productId to OrderItem(name = it.name, price = it.price, quantity = it.qty)
                },
                subtotal = CartManager.subtotal(),
                deliveryMethod = CartManager.deliveryMethod,
                deliveryAddress = address,
                distanceKm = if (pickup) 0.0 else CartManager.distanceKm() ?: 0.0,
                deliveryLat = if (pickup) null else CartManager.buyerLat,
                deliveryLng = if (pickup) null else CartManager.buyerLng,
                deliveryFee = CartManager.fee(), // fee is fixed at the moment the order is placed
                total = total,
                status = Order.STATUS_PENDING,   // "Waiting for seller approval"
                timestamp = System.currentTimeMillis()
            )
            // Server-side check: reserves (decrements) the stock in transactions first, then saves the order.
            OrderRepository.placeOrder(order) { result ->
                result.onSuccess {
                    CartManager.clear()
                    if (!isAdded) return@onSuccess
                    render()
                    _binding?.btnPlaceOrder?.isEnabled = true
                    val how = if (pickup) "Pay when you pick it up at the shop."
                              else "Pay cash on delivery when your order arrives."
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Order placed 🎉")
                        .setMessage("Total: ${formatMoney(total)}\n$how\n\nYou'll get a notification when the shop updates your order.")
                        .setCancelable(false)
                        .setPositiveButton("Track order") { _, _ ->
                            (requireActivity() as BuyerHomeActivity).selectTab(R.id.nav_orders)
                        }
                        .setNegativeButton("Keep shopping") { _, _ ->
                            (requireActivity() as BuyerHomeActivity).selectTab(R.id.nav_shops)
                        }
                        .show()
                }.onFailure { e ->
                    _binding?.btnPlaceOrder?.isEnabled = true
                    if (isAdded) {
                        showError((e.message ?: "Something went wrong") + "\n\nPlease adjust your cart and try again.")
                    } else {
                        Toast.makeText(ctx, e.message ?: "Couldn't place the order", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /** Alert dialog for checkout problems (e.g. "Only 1 of "Milk" left"). */
    private fun showError(message: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Couldn't place the order")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
