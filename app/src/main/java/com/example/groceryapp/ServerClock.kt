package com.example.groceryapp

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * The current time according to Firebase's server, not the phone.
 *
 * Firebase keeps the difference between the phone clock and the server clock at
 * .info/serverTimeOffset. Adding it to the phone time gives the real time even if the buyer
 * changed their clock (e.g. to avoid the rush-hour fee). Until the offset arrives, the phone time is used.
 */
object ServerClock {

    @Volatile
    private var offsetMs = 0L
    private var started = false

    /** Starts listening to the offset; safe to call more than once. */
    fun start() {
        if (started) return
        started = true
        FirebaseDatabase.getInstance().getReference(".info/serverTimeOffset")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    offsetMs = (snapshot.value as? Number)?.toLong() ?: 0L
                }

                override fun onCancelled(error: DatabaseError) { /* keep the last known offset */ }
            })
    }

    /** Server time in epoch millis. */
    fun now(): Long = System.currentTimeMillis() + offsetMs
}
