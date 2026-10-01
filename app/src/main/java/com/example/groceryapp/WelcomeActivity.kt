package com.example.groceryapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.groceryapp.databinding.ActivityWelcomeBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val openProfile = {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
        binding.ivProfileIcon.setOnClickListener { openProfile() }
        binding.cardProfile.setOnClickListener { openProfile() }
    }

    // Reload on every return so an edited name shows up after coming back from Profile.
    override fun onStart() {
        super.onStart()
        loadName()
    }

    private fun loadName() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            binding.tvWelcome.text = "Friend"
            return
        }
        FirebaseDatabase.getInstance().getReference("users").child(user.uid)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val name = snapshot.child("name").value?.toString()
                    binding.tvWelcome.text = if (!name.isNullOrBlank()) name else "Friend"
                }

                override fun onCancelled(error: DatabaseError) {
                    binding.tvWelcome.text = "Friend"
                }
            })
    }
}