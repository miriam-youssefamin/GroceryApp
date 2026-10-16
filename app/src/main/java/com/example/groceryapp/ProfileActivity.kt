package com.example.groceryapp

import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.net.Uri
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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

    /** A photo the user picked but hasn't saved yet; uploaded to S3 when they tap "Save changes". */
    private var pendingPhoto: Uri? = null
    private var shownPhotoUrl: String? = null

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            pendingPhoto = uri
            ImageUtils.decodeBitmap(this, uri, 512)?.let { showPhoto(it) }
                ?: Toast.makeText(this, "Couldn't open that image", Toast.LENGTH_SHORT).show()
        }
    }

    // Fires once with the stored data, then again on every change (real-time).
    private val profileListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val name = snapshot.child("name").value?.toString() ?: ""
            val address = snapshot.child("address").value?.toString() ?: ""
            val phone = snapshot.child("phone").value?.toString() ?: ""

            binding.etName.setIfIdle(name)
            binding.etAddress.setIfIdle(address)
            binding.etPhone.setIfIdle(phone)

            // Fetch the profile photo from S3 (only when the saved URL changed and nothing new is pending).
            val photoUrl = snapshot.child("photoUrl").value?.toString() ?: ""
            if (photoUrl != shownPhotoUrl && pendingPhoto == null) {
                shownPhotoUrl = photoUrl
                if (photoUrl.isBlank() || !S3Helper.isConfigured()) showPlaceholder() else loadPhoto(photoUrl)
            }
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

        val choosePhoto = View.OnClickListener {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.ivPhoto.setOnClickListener(choosePhoto)
        binding.btnChangePhoto.setOnClickListener(choosePhoto)

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
        val updates = mutableMapOf<String, Any>(
            "name" to name,
            "address" to binding.etAddress.text.toString().trim(),
            "phone" to binding.etPhone.text.toString().trim()
        )

        val photo = pendingPhoto
        if (photo == null) {
            commitProfile(updates)
            return
        }

        if (!S3Helper.isConfigured()) {
            Toast.makeText(this, "AWS credentials are missing (fill in aws.properties)", Toast.LENGTH_LONG).show()
            return
        }
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        setPhotoBusy(true)
        S3Helper.uploadProfilePhoto(this, photo, uid) { result ->
            setPhotoBusy(false)
            result.onSuccess { url ->
                updates["photoUrl"] = url      // the S3 URL is what we keep in Firebase
                pendingPhoto = null
                shownPhotoUrl = url
                commitProfile(updates)
            }.onFailure {
                commitProfile(updates)
                Toast.makeText(this, "Details saved, but photo upload failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun commitProfile(updates: Map<String, Any>) {
        userRef.updateChildren(updates)
            .addOnSuccessListener {
                currentFocus?.clearFocus()
                Toast.makeText(this, "Profile updated", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, it.message ?: "Update failed", Toast.LENGTH_LONG).show()
            }
    }

    // ---- Profile photo --------------------------------------------------------------------

    private fun loadPhoto(url: String) {
        binding.progressPhoto.visibility = View.VISIBLE
        S3Helper.downloadImage(url) { result ->
            if (isDestroyed) return@downloadImage
            binding.progressPhoto.visibility = View.GONE
            result.onSuccess { showPhoto(it) }
                .onFailure { showPlaceholder() }
        }
    }

    private fun showPhoto(bitmap: Bitmap) {
        binding.ivPhoto.setPadding(0, 0, 0, 0)
        binding.ivPhoto.imageTintList = null
        binding.ivPhoto.setImageBitmap(bitmap)
    }

    private fun showPlaceholder() {
        val pad = (26 * resources.displayMetrics.density).toInt()
        binding.ivPhoto.setPadding(pad, pad, pad, pad)
        binding.ivPhoto.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.green_primary))
        binding.ivPhoto.setImageResource(R.drawable.ic_person)
    }

    private fun setPhotoBusy(busy: Boolean) {
        binding.progressPhoto.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnSave.isEnabled = !busy
        binding.btnChangePhoto.isEnabled = !busy
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
                removeUserData(user.uid) { error ->
                    if (error != null) {
                        userRef.addValueEventListener(profileListener)
                        failDelete(error)
                        return@removeUserData
                    }
                    user.delete()
                        .addOnSuccessListener {
                            Toast.makeText(this, "Account deleted", Toast.LENGTH_SHORT).show()
                            goToLogin()
                        }
                        .addOnFailureListener { failDelete(it.message ?: "Couldn't delete your account") }
                }
            }
            .addOnFailureListener {
                failDelete(
                    if (it is FirebaseAuthInvalidCredentialsException) "Incorrect password"
                    else it.message ?: "Couldn't verify your password"
                )
            }
    }

    /**
     * Deletes users/{uid} together with any products the user listed as a seller, in one atomic update.
     * If the products lookup fails, falls back to deleting only the user node.
     */
    private fun removeUserData(uid: String, done: (error: String?) -> Unit) {
        val db = FirebaseDatabase.getInstance()
        fun commit(updates: Map<String, Any?>) {
            db.reference.updateChildren(updates)
                .addOnSuccessListener { done(null) }
                .addOnFailureListener { done(it.message ?: "Couldn't delete your data") }
        }
        db.getReference("products").orderByChild("sellerId").equalTo(uid)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val updates = HashMap<String, Any?>()
                    snapshot.children.forEach { updates["products/${it.key}"] = null }
                    updates["users/$uid"] = null
                    updates["shops/$uid"] = null
                    commit(updates)
                }

                override fun onCancelled(error: DatabaseError) {
                    commit(mapOf("users/$uid" to null, "shops/$uid" to null))
                }
            })
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
            Intent(this, RoleSelectionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::userRef.isInitialized) userRef.removeEventListener(profileListener)
    }
}