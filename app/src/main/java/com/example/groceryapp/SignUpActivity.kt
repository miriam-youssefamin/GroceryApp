package com.example.groceryapp

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.groceryapp.databinding.ActivitySignupBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class SignUpActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignupBinding
    private val auth = FirebaseAuth.getInstance()
    private lateinit var role: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        role = intent.getStringExtra(Roles.EXTRA_ROLE) ?: Roles.BUYER
        listOf(
            binding.tilName, binding.tilAddress, binding.tilPhone,
            binding.tilEmail, binding.tilPassword, binding.tilConfirm
        ).forEach { it.clearErrorOnType() }

        if (role == Roles.SELLER) {
            binding.tvHeading.text = "Create seller account"
            binding.tvSubtitle.text = "Start listing your products"
            binding.tilName.hint = "Shop name"
            binding.tilAddress.hint = "Shop address"
        } else {
            binding.tvHeading.text = "Create buyer account"
        }

        binding.btnSignUp.setOnClickListener { signUp() }
        binding.tvGoLogin.setOnClickListener { finish() }
    }

    private fun signUp() {
        val name = binding.etName.text.toString().trim()
        val address = binding.etAddress.text.toString().trim()
        val phone = binding.etPhone.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val confirm = binding.etConfirm.text.toString()

        when {
            name.isEmpty() -> binding.tilName.error = "This field is required"
            address.isEmpty() -> binding.tilAddress.error = "This field is required"
            phone.length !in 11..12 -> binding.tilPhone.error = "Phone number must be 11 or 12 digits"
            !phone.all { it.isDigit() } -> binding.tilPhone.error = "Phone number must contain digits only"
            !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> binding.tilEmail.error = "Enter a valid email"
            password.length < 6 -> binding.tilPassword.error = "At least 6 characters"
            password != confirm -> binding.tilConfirm.error = "Passwords do not match"
            else -> createAccount(name, address, phone, email, password)
        }
    }

    private fun createAccount(name: String, address: String, phone: String, email: String, password: String) {
        binding.btnSignUp.isEnabled = false
        auth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener { result ->
                val uid = result.user!!.uid
                val profile = UserProfile(role = role, name = name, email = email, address = address, phone = phone)
                FirebaseDatabase.getInstance().getReference("users").child(uid)
                    .setValue(profile)
                    .addOnSuccessListener {
                        val target = if (role == Roles.SELLER) SellerHomeActivity::class.java else BuyerHomeActivity::class.java
                        startActivity(
                            Intent(this, target)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        )
                    }
                    .addOnFailureListener { fail(it) }
            }
            .addOnFailureListener { fail(it) }
    }

    private fun fail(e: Exception) {
        binding.btnSignUp.isEnabled = true
        Toast.makeText(this, e.message ?: "Sign up failed", Toast.LENGTH_LONG).show()
    }
}