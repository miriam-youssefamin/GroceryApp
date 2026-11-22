package com.example.groceryapp

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.groceryapp.databinding.ActivityShopSettingsBinding
import com.google.android.material.chip.Chip
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Lets a seller set their shop category, base delivery fee, delivery time and location (shops/{uid}).
 * The base fee and the location are the starting points of the dynamic delivery price; see [DeliveryPricing].
 * The location is taken from the phone (the seller taps the button while at the shop), not typed in.
 */
class ShopSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShopSettingsBinding
    /** Chip view id -> category key, filled when the chips are created. */
    private val chipKeys = HashMap<Int, String>()
    private var selectedCategory = "other"

    /** Shop location shown on screen; saved together with the other fields. */
    private var shopLat: Double? = null
    private var shopLng: Double? = null

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { it }) fetchShopLocation()
            else Toast.makeText(this, "Location permission is needed to set the shop location", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShopSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run { finish(); return }
        val shopRef = FirebaseDatabase.getInstance().getReference("shops").child(uid)

        listOf(binding.tilFee, binding.tilMinutes).forEach { it.clearErrorOnType() }

        binding.btnSetLocation.setOnClickListener {
            if (LocationHelper.hasPermission(this)) fetchShopLocation()
            else locationPermission.launch(LocationHelper.PERMISSIONS)
        }

        // One chip per category, built from Categories.all so the list lives in one place.
        val density = resources.displayMetrics.density
        Categories.all.forEach { cat ->
            val chip = layoutInflater.inflate(R.layout.item_chip, binding.chipGroupCategory, false) as Chip
            chip.id = View.generateViewId()
            chip.text = "${cat.emoji} ${cat.label}"
            chip.chipStrokeWidth = 1.5f * density
            chip.chipStrokeColor = ColorStateList.valueOf(getColor(R.color.green_primary))
            chipKeys[chip.id] = cat.key
            binding.chipGroupCategory.addView(chip)
        }
        binding.chipGroupCategory.setOnCheckedStateChangeListener { _, ids ->
            selectedCategory = ids.firstOrNull()?.let { chipKeys[it] } ?: selectedCategory
        }

        // Load the current settings into the form.
        shopRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val shop = if (snapshot.exists()) Shop.from(snapshot) else Shop(id = uid)
                binding.etFee.setText(
                    if (shop.deliveryFee % 1.0 == 0.0) "%.0f".format(shop.deliveryFee)
                    else shop.deliveryFee.toString()
                )
                binding.etMinutes.setText(shop.deliveryMinutes.toString())
                binding.tvShopName.text = "Shop name: ${shop.name}"
                shopLat = shop.latitude
                shopLng = shop.longitude
                showLocation()
                selectedCategory = shop.category
                chipKeys.entries.firstOrNull { it.value == shop.category }
                    ?.let { binding.chipGroupCategory.check(it.key) }
            }

            override fun onCancelled(error: DatabaseError) { /* ignore */ }
        })

        // Validate, then save only the fields this screen owns (updateChildren keeps name/photo/reviews).
        binding.btnSave.setOnClickListener {
            val fee = binding.etFee.text.toString().trim().toDoubleOrNull()
            val minutes = binding.etMinutes.text.toString().trim().toIntOrNull()
            if (fee == null || fee < 0) { binding.tilFee.error = "Enter a valid fee (0 for free)"; return@setOnClickListener }
            if (minutes == null || minutes <= 0) { binding.tilMinutes.error = "Enter a valid time in minutes"; return@setOnClickListener }

            binding.btnSave.isEnabled = false
            val updates = mutableMapOf<String, Any>(
                "deliveryFee" to fee, "deliveryMinutes" to minutes, "category" to selectedCategory
            )
            val lat = shopLat
            val lng = shopLng
            if (lat != null && lng != null) {
                updates["latitude"] = lat
                updates["longitude"] = lng
            }
            shopRef.updateChildren(updates)
                .addOnSuccessListener {
                    Toast.makeText(this, "Shop details saved", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .addOnFailureListener {
                    binding.btnSave.isEnabled = true
                    Toast.makeText(this, it.message ?: "Couldn't save", Toast.LENGTH_LONG).show()
                }
        }
    }

    /** Gets the phone's location and shows it; it is saved when the seller taps Save. */
    private fun fetchShopLocation() {
        binding.btnSetLocation.isEnabled = false
        binding.btnSetLocation.text = "Finding your location…"
        LocationHelper.getCurrentLocation(this) { location ->
            binding.btnSetLocation.isEnabled = true
            if (location != null) {
                shopLat = location.latitude
                shopLng = location.longitude
                Toast.makeText(this, "Location found. Tap Save to keep it.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Couldn't get your location. Make sure location is turned on.", Toast.LENGTH_LONG).show()
            }
            showLocation()
        }
    }

    private fun showLocation() {
        val lat = shopLat
        val lng = shopLng
        if (lat != null && lng != null) {
            binding.tvShopLocation.text = "📍 Set (%.5f, %.5f)".format(lat, lng)
            binding.btnSetLocation.text = "📍 Update to my current location"
        } else {
            binding.tvShopLocation.text = "📍 Not set. Buyers can only pick up until you set it."
            binding.btnSetLocation.text = "📍 Use my current location"
        }
    }
}
