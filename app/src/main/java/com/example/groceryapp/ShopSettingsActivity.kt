package com.example.groceryapp

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.groceryapp.databinding.ActivityShopSettingsBinding
import com.google.android.material.chip.Chip
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/** Lets a seller set the delivery fee and delivery time shown to buyers. */
class ShopSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShopSettingsBinding
    private val chipKeys = HashMap<Int, String>()
    private var selectedCategory = "other"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShopSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run { finish(); return }
        val shopRef = FirebaseDatabase.getInstance().getReference("shops").child(uid)

        listOf(binding.tilFee, binding.tilMinutes).forEach { it.clearErrorOnType() }

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

        shopRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val shop = if (snapshot.exists()) Shop.from(snapshot) else Shop(id = uid)
                binding.etFee.setText(
                    if (shop.deliveryFee % 1.0 == 0.0) "%.0f".format(shop.deliveryFee)
                    else shop.deliveryFee.toString()
                )
                binding.etMinutes.setText(shop.deliveryMinutes.toString())
                binding.tvShopName.text = "Shop name: ${shop.name}"
                selectedCategory = shop.category
                chipKeys.entries.firstOrNull { it.value == shop.category }
                    ?.let { binding.chipGroupCategory.check(it.key) }
            }

            override fun onCancelled(error: DatabaseError) { /* ignore */ }
        })

        binding.btnSave.setOnClickListener {
            val fee = binding.etFee.text.toString().trim().toDoubleOrNull()
            val minutes = binding.etMinutes.text.toString().trim().toIntOrNull()
            if (fee == null || fee < 0) { binding.tilFee.error = "Enter a valid fee (0 for free)"; return@setOnClickListener }
            if (minutes == null || minutes <= 0) { binding.tilMinutes.error = "Enter a valid time in minutes"; return@setOnClickListener }

            binding.btnSave.isEnabled = false
            shopRef.updateChildren(mapOf("deliveryFee" to fee, "deliveryMinutes" to minutes, "category" to selectedCategory))
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
}
