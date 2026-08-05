package com.droid

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey val pubkey: String,   // compressed public key (66 hex) – matches column name
    val name: String,
    val addedAt: Long
) {
    // Helper to get the x‑only (64‑hex) version – not stored in DB
    val xOnlyPubkeyHex: String
        get() = if (pubkey.length == 66) pubkey.drop(2) else pubkey
}