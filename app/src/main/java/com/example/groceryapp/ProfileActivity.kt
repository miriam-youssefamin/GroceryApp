package com.example.groceryapp

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.groceryapp.databinding.ActivityProfileBinding
import com.example.groceryapp.databinding.DialogConfirmPasswordBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var userRef: DatabaseReference

    // Fires once with the stored data, then again on every change (real-time).
    private val profileListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val name = snapshot.child("name").value?.toString() ?: ""
            val address = snapshot.child("address").value?.toString() ?: ""
            val phone = snapshot.child("phone").value?.toString() ?: ""

            binding.etName.setIfIdle(name)
            binding.etAddress.setIfIdle(address)
            binding.etPhone.setIfIdle(phone)
        }
        override fun onCancelled(error: DatabaseError) {
            Toast.makeText(this@ProfileActivity, error.message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {           // not signed in -> back to login
            goToLogin()
            return
        }

        binding.tvEmail.text = user.email
        userRef = FirebaseDatabase.getInstance().getReference("users").child(user.uid)
        userRef.addValueEventListener(profileListener)

        binding.tilName.clearErrorOnType()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { saveProfile() }
        binding.btnLogout.setOnClickListener {
            FirebaseAuth.getInstance().signOut()
            goToLogin()
        }
        binding.btnDelete.setOnClickListener { confirmDelete() }
    }

    private fun saveProfile() {
        val name = binding.etName.text.toString().trim()
        if (name.isEmpty()) {
            binding.tilName.error = "Name cannot be empty"
            return
        }
        val updates = mapOf(
            "name" to name,
            "address" to binding.etAddress.text.toString().trim(),
            "phone" to binding.etPhone.text.toString().trim()
        )
        userRef.updateChildren(updates)
            .addOnSuccessListener {
                currentFocus?.clearFocus()
                Toast.makeText(this, "Profile updated", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, it.message ?: "Update failed", Toast.LENGTH_LONG).show()
            }
    }

    // ---- Delete account -------------------------------------------------------------------

    private fun confirmDelete() {
        val dialogBinding = DialogConfirmPasswordBinding.inflate(layoutInflater)
        dialogBinding.tilPassword.clearErrorOnType()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Delete account?")
            .setMessage("This permanently deletes your account and all your data. Enter your password to confirm.")
            .setView(dialogBinding.root)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", null)   // real click handler set below so the dialog stays open on error
            .create()

        dialog.setOnShowListener {
            val deleteButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            deleteButton.setTextColor(getColor(R.color.danger))
            deleteButton.setOnClickListener {
                val password = dialogBinding.etPassword.text.toString()
                if (password.isEmpty()) {
                    dialogBinding.tilPassword.error = "Enter your password"
                    return@setOnClickListener
                }
                dialog.dismiss()
                deleteAccount(password)
            }
        }
        dialog.show()
    }

    /**
     * Firebase only allows deleting an account after a recent sign-in, so we re-authenticate first.
     * Order matters: the database rules need us signed in to remove the data, so data goes first,
     * then the auth account.
     */
    private fun deleteAccount(password: String) {
        val user = FirebaseAuth.getInstance().currentUser
        val email = user?.email
        if (user == null || email == null) {
            goToLogin()
            return
        }

        setBusy(true)
        user.reauthenticate(EmailAuthProvider.getCredential(email, password))
            .addOnSuccessListener {
                // Stop listening first, otherwise the listener reports a permission error once we're signed out.
                userRef.removeEventListener(profileListener)
                userRef.removeValue()
                    .addOnSuccessListener {
                        user.delete()
                            .addOnSuccessListener {
                                Toast.makeText(this, "Account deleted", Toast.LENGTH_SHORT).show()
                                goToLogin()
                            }
                            .addOnFailureListener { failDelete(it.message ?: "Couldn't delete your account") }
                    }
                    .addOnFailureListener {
                        userRef.addValueEventListener(profileListener)
                        failDelete(it.message ?: "Couldn't delete your data")
                    }
            }
            .addOnFailureListener {
                failDelete(
                    if (it is FirebaseAuthInvalidCredentialsException) "Incorrect password"
                    else it.message ?: "Couldn't verify your password"
                )
            }
    }

    private fun failDelete(message: String) {
        setBusy(false)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun setBusy(busy: Boolean) {
        binding.btnSave.isEnabled = !busy
        binding.btnLogout.isEnabled = !busy
        binding.btnDelete.isEnabled = !busy
        binding.btnBack.isEnabled = !busy
    }

    // ---------------------------------------------------------------------------------------

    // Don't overwrite a field while the user is typing in it.
    private fun EditText.setIfIdle(value: String) {
        if (!hasFocus() && text.toString() != value) setText(value)
    }

    private fun goToLogin() {
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::userRef.isInitialized) userRef.removeEventListener(profileListener)
    }
}
