package com.example.groceryapp

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.groceryapp.databinding.ActivityLoginBinding
import com.google.firebase.auth.FirebaseAuth

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val auth = FirebaseAuth.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        listOf(binding.tilEmail, binding.tilPassword).forEach { it.clearErrorOnType() }

        binding.btnLogin.setOnClickListener { login() }
        binding.tvGoSignUp.setOnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
        }
        binding.tvForgotPassword.setOnClickListener { forgotPassword() }
    }

    override fun onStart() {
        super.onStart()
        if (auth.currentUser != null) goToProfile()
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
            .addOnSuccessListener { goToProfile() }
            .addOnFailureListener {
                binding.btnLogin.isEnabled = true
                Toast.makeText(this, it.message ?: "Login failed", Toast.LENGTH_LONG).show()
            }
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

    private fun goToProfile() {
        startActivity(
            Intent(this, WelcomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }
}