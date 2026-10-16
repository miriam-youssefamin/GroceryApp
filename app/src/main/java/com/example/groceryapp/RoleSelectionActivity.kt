package com.example.groceryapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.groceryapp.databinding.ActivityRoleSelectBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class RoleSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRoleSelectBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRoleSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBuyer.setOnClickListener { goToAuth(Roles.BUYER) }
        binding.btnSeller.setOnClickListener { goToAuth(Roles.SELLER) }
    }

    override fun onStart() {
        super.onStart()
        // Already signed in? Skip role selection and go straight to the right home screen.
        val user = FirebaseAuth.getInstance().currentUser ?: return
        FirebaseDatabase.getInstance().getReference("users").child(user.uid).child("role")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    when (snapshot.value?.toString()) {
                        Roles.BUYER -> goToHome(BuyerHomeActivity::class.java)
                        Roles.SELLER -> goToHome(SellerHomeActivity::class.java)
                        else -> FirebaseAuth.getInstance().signOut() // corrupt/missing role, force re-login
                    }
                }
                override fun onCancelled(error: DatabaseError) { /* stay on this screen */ }
            })
    }

    private fun goToAuth(role: String) {
        startActivity(Intent(this, LoginActivity::class.java).putExtra(Roles.EXTRA_ROLE, role))
    }

    private fun goToHome(activity: Class<*>) {
        startActivity(
            Intent(this, activity)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }
}