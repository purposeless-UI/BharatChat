package com.droid.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.droid.Contact
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: Contact)

    @Query("SELECT * FROM contacts WHERE pubkey = :pubkey")
    suspend fun getContact(pubkey: String): Contact?

    @Query("SELECT * FROM contacts ORDER BY name ASC")
    fun getAllContacts(): Flow<List<Contact>>

    @Query("DELETE FROM contacts WHERE pubkey = :pubkey")
    suspend fun delete(pubkey: String)

    @Query("UPDATE contacts SET name = :name WHERE pubkey = :pubkey")
    suspend fun rename(pubkey: String, name: String)
}