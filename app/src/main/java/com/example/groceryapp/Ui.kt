package com.example.groceryapp

import androidx.core.widget.doAfterTextChanged
import com.google.android.material.textfield.TextInputLayout

/** Clears the field's error as soon as the user edits it. */
fun TextInputLayout.clearErrorOnType() {
    editText?.doAfterTextChanged { error = null }
}
