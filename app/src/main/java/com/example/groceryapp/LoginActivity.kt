package com.example.groceryapp

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.groceryapp.databinding.ActivityLoginBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val auth = FirebaseAuth.getInstance()
    private lateinit var role: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        role = intent.getStringExtra(Roles.EXTRA_ROLE) ?: Roles.BUYER
        binding.tvSubtitle.text =
            if (role == Roles.SELLER) "Log in to your seller account" else "Log in to your buyer account"
        listOf(binding.tilEmail, binding.tilPassword).forEach { it.clearErrorOnType() }

        binding.btnLogin.setOnClickListener { login() }
        binding.tvGoSignUp.setOnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java).putExtra(Roles.EXTRA_ROLE, role))
        }
        binding.tvForgotPassword.setOnClickListener { forgotPassword() }
    }

    private fun login() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.error = "Enter a valid email"
            return
        }
        if (password.isEmpty()) {
            binding.tilPassword.error = "Enter your password"
            return
        }

        binding.btnLogin.isEnabled = false
        auth.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener { checkRoleAndContinue() }
            .addOnFailureListener {
                binding.btnLogin.isEnabled = true
                Toast.makeText(this, it.message ?: "Login failed", Toast.LENGTH_LONG).show()
            }
    }

    private fun checkRoleAndContinue() {
        val uid = auth.currentUser!!.uid
        FirebaseDatabase.getInstance().getReference("users").child(uid).child("role")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val storedRole = snapshot.value?.toString()
                    binding.btnLogin.isEnabled = true
                    if (storedRole == role) {
                        goToHome()
                    } else {
                        auth.signOut()
                        val correct = if (storedRole == Roles.SELLER) "Seller" else "Buyer"
                        Toast.makeText(
                            this@LoginActivity,
                            "This account is registered as a $correct. Please log in from the $correct option.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    binding.btnLogin.isEnabled = true
                    Toast.makeText(this@LoginActivity, error.message, Toast.LENGTH_LONG).show()
                }
            })
    }

    private fun forgotPassword() {
        val email = binding.etEmail.text.toString().trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.error = "Enter your email above first"
            return
        }
        auth.sendPasswordResetEmail(email)
            .addOnSuccessListener {
                Toast.makeText(this, "Password reset email sent to $email", Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, it.message ?: "Couldn't send reset email", Toast.LENGTH_LONG).show()
            }
    }

    private fun goToHome() {
        val target = if (role == Roles.SELLER) SellerHomeActivity::class.java else BuyerHomeActivity::class.java
        startActivity(
            Intent(this, target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }
}