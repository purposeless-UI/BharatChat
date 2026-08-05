package com.droid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.droid.ble.BlePermissions
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

class MainActivity : AppCompatActivity() {

    private lateinit var contactAdapter: ContactAdapter
    private lateinit var meshStatusTextView: TextView
    private val tag = "MainActivity"
    private lateinit var db: AppDatabase

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (!allGranted) {
            val deniedPermissions = results.filter { !it.value }.keys
            Toast.makeText(
                this,
                "Some permissions were denied: $deniedPermissions. Please grant them in Settings.",
                Toast.LENGTH_LONG
            ).show()
        }
        startMeshService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        IdentityStore.loadOrCreate(this)
        db = AppDatabase.getInstance(this)

        // Toolbar
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "BharatChat"

        // Status text
        meshStatusTextView = findViewById(R.id.statusTextView)

        // RecyclerView
        val recyclerView = findViewById<RecyclerView>(R.id.contactRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        contactAdapter = ContactAdapter(emptyList())
        recyclerView.adapter = contactAdapter

        // FAB
        val fab = findViewById<FloatingActionButton>(R.id.fabPair)
        fab.setOnClickListener {
            startActivity(Intent(this, PairingActivity::class.java))
        }

        requestBlePermissionsThenStart()
    }

    override fun onResume() {
        super.onResume()
        renderContacts()
        startMeshStatusPolling()
        if (checkAllPermissionsGranted()) {
            startMeshService()
        }
    }

    override fun onPause() {
        super.onPause()
        // polling coroutine will be canceled automatically by lifecycleScope
    }

    private fun checkAllPermissionsGranted(): Boolean {
        val needed = BlePermissions.required().toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return needed.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBlePermissionsThenStart() {
        val needed = BlePermissions.required().toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val stillMissing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (stillMissing.isEmpty()) {
            startMeshService()
        } else {
            requestPermissions.launch(stillMissing.toTypedArray())
        }
    }

    private fun startMeshService() {
        try {
            if (!checkAllPermissionsGranted()) {
                Log.w(tag, "Permissions missing – not starting service")
                return
            }
            val intent = Intent(this, MeshForegroundService::class.java)
            ContextCompat.startForegroundService(this, intent)
            Log.d(tag, "startMeshService called")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start service", e)
        }
    }

    private fun renderContacts() {
        lifecycleScope.launch {
            db.contactDao().getAllContacts().collect { contacts ->
                contactAdapter.updateContacts(contacts)
            }
        }
    }

    // ----- Contact Adapter (inner class) -----
    inner class ContactAdapter(private var contacts: List<Contact>) :
        RecyclerView.Adapter<ContactAdapter.ContactViewHolder>() {

        @Suppress("NotifyDataSetChanged")
        fun updateContacts(newContacts: List<Contact>) {
            contacts = newContacts.sortedByDescending { it.addedAt }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_contact, parent, false)
            return ContactViewHolder(view)
        }

        override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
            val contact = contacts[position]
            holder.nameTextView.text = contact.name
            holder.subtitleTextView.text = contact.xOnlyPubkeyHex.take(12) + "…"

            holder.itemView.setOnClickListener {
                val intent = Intent(this@MainActivity, ChatActivity::class.java)
                intent.putExtra("contactPubkey", contact.pubkey)
                intent.putExtra("contactName", contact.name)
                startActivity(intent)
            }
            holder.itemView.setOnLongClickListener {
                showContactOptionsDialog(contact)
                true
            }
        }

        override fun getItemCount(): Int = contacts.size

        inner class ContactViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val nameTextView: TextView = itemView.findViewById(R.id.contactName)
            val subtitleTextView: TextView = itemView.findViewById(R.id.contactSubtitle)
        }
    }

    // ----- Contact options (rename / delete) -----
    private fun showContactOptionsDialog(contact: Contact) {
        val options = arrayOf("Rename", "Delete Contact")
        AlertDialog.Builder(this)
            .setTitle(contact.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDialog(contact)
                    1 -> confirmDeleteContact(contact)
                }
            }
            .show()
    }

    private fun showRenameDialog(contact: Contact) {
        val editText = TextInputEditText(this)
        editText.setText(contact.name)
        AlertDialog.Builder(this)
            .setTitle("Rename contact")
            .setView(editText)
            .setPositiveButton("Rename") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    lifecycleScope.launch {
                        try {
                            db.contactDao().rename(contact.pubkey, newName)
                            renderContacts()
                        } catch (e: Exception) {
                            Log.e(tag, "Rename failed", e)
                            Toast.makeText(this@MainActivity, "Failed to rename", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteContact(contact: Contact) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${contact.name}?")
            .setMessage("This will remove the contact and delete all chat history.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        db.contactDao().delete(contact.pubkey)
                        db.messageDao().deleteAllForContact(contact.pubkey)
                        renderContacts()
                        Toast.makeText(this@MainActivity, "Contact deleted", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e(tag, "Delete contact failed", e)
                        Toast.makeText(this@MainActivity, "Failed to delete contact", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startMeshStatusPolling() {
        lifecycleScope.launch {
            while (isActive) {
                updateMeshStatus()
                delay(3.seconds)
            }
        }
    }

    @Suppress("SetTextI18n")
    private fun updateMeshStatus() {
        val bluetoothAvailable = MeshServiceHolder.isBluetoothAvailable()
        val nostrAvailable = MeshServiceHolder.isNostrAvailable()
        val peerCount = MeshServiceHolder.getConnectedPeerCount()

        val status = when {
            // Bluetooth is running and has peers connected
            bluetoothAvailable && peerCount > 0 -> {
                if (nostrAvailable) {
                    "🔷 Mesh + Internet relay • $peerCount peer${if (peerCount != 1) "s" else ""} connected"
                } else {
                    "🔷 Mesh: Running • $peerCount peer${if (peerCount != 1) "s" else ""} connected"
                }
            }
            // Nostr-only mode (Bluetooth OFF, but Nostr working)
            nostrAvailable && !bluetoothAvailable -> {
                "🌐 Internet relay active (Bluetooth OFF)"
            }
            // Nothing running
            else -> {
                "Mesh: Not running"
            }
        }
        meshStatusTextView.text = status
    }

    @Suppress("unused")
    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = "package:$packageName".toUri()
        startActivity(intent)
    }
}