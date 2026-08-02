package com.droid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.droid.ble.BlePermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

@Suppress(
    "SetTextI18n",
    "SameParameterValue",
    "SpellCheckingInspection",
    "UseSetterMethod",
    "KTX",
    "Deprecation"
)
class MainActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var meshStatusTextView: TextView
    private val tag = "MainActivity"
    private lateinit var db: AppDatabase   // ✅ Added for database operations

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

        IdentityStore.loadOrCreate(this)
        db = AppDatabase.getInstance(this)   // ✅ Initialize database

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            setBackgroundColor(Color.DKGRAY)
        }

        meshStatusTextView = TextView(this).apply {
            text = "Mesh: initialising…"
            textSize = 14f
            setPadding(0, 0, 0, 16)
            setTextColor(Color.rgb(255, 165, 0))
        }
        root.addView(meshStatusTextView)

        root.addView(TextView(this).apply {
            text = "► BharatChat"
            textSize = 26f
            setPadding(0, 0, 0, 24)
            setTextColor(Color.rgb(255, 165, 0))
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.DKGRAY)
        }
        scroll.addView(listContainer)
        root.addView(scroll)

        root.addView(Button(this).apply {
            text = "+ PAIR CONTACT"
            setTextColor(Color.rgb(255, 165, 0))
            setBackgroundColor(Color.DKGRAY)
            val drawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.DKGRAY)
                setStroke(2, Color.rgb(255, 165, 0))
                setCornerRadius(8f)
            }
            background = drawable
            setOnClickListener { startActivity(Intent(this@MainActivity, PairingActivity::class.java)) }
        })

        setContentView(root)

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
        // polling coroutine will be cancelled automatically by lifecycleScope
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
            val contacts = withContext(Dispatchers.IO) {
                try {
                    ContactsStore.list(this@MainActivity)
                } catch (e: Exception) {
                    Log.e(tag, "Error loading contacts", e)
                    emptyList()
                }
            }
            updateContactList(contacts)
        }
    }

    private fun updateContactList(contacts: List<Contact>) {
        listContainer.removeAllViews()
        if (contacts.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "╰┈➤ No contacts yet.\nPair with someone to start."
                gravity = Gravity.CENTER
                setPadding(0, 48, 0, 0)
                setTextColor(Color.rgb(255, 165, 0))
            })
            return
        }
        contacts.sortedByDescending { it.addedAt }.forEach { contact ->
            val row = TextView(this).apply {
                text = "${contact.name}\n${contact.xOnlyPubkeyHex.take(12)}..."
                textSize = 16f
                setPadding(16, 24, 16, 24)
                setTextColor(Color.rgb(255, 165, 0))
                setBackgroundColor(Color.DKGRAY)
                setOnClickListener {
                    val intent = Intent(this@MainActivity, ChatActivity::class.java)
                    intent.putExtra("contactPubkey", contact.pubkeyHex)
                    intent.putExtra("contactName", contact.name)
                    startActivity(intent)
                }
                // ✅ Long‑click now shows options (Rename / Delete Contact)
                setOnLongClickListener {
                    showContactOptionsDialog(contact)
                    true
                }
            }
            listContainer.addView(row)

            val divider = android.view.View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1
                )
                setBackgroundColor(Color.rgb(255, 165, 0))
            }
            listContainer.addView(divider)
        }
    }

    // ✅ New: Contact options (Rename / Delete)
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

    // ✅ Existing rename dialog (unchanged)
    private fun showRenameDialog(contact: Contact) {
        val editText = EditText(this).apply {
            setText(contact.name)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename contact")
            .setView(editText)
            .setPositiveButton("Rename") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    lifecycleScope.launch {
                        try {
                            ContactsStore.rename(this@MainActivity, contact.pubkeyHex, newName)
                            renderContacts() // refresh the list
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

    // ✅ New: Delete contact and all chat history
    private fun confirmDeleteContact(contact: Contact) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${contact.name}?")
            .setMessage("This will remove the contact and delete all chat history.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        // 1. Remove from ContactsStore
                        ContactsStore.remove(this@MainActivity, contact.pubkeyHex)
                        // 2. Delete all messages for this contact
                        db.messageDao().deleteAllForContact(contact.pubkeyHex)
                        // 3. Refresh the list
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

    private fun updateMeshStatus() {
        val manager = MeshServiceHolder.current()
        val status = if (manager != null) {
            try {
                if (manager.isRunning()) {
                    val peerCount = manager.connectedPeerCount()
                    "Mesh: Running  ●  $peerCount peer${if (peerCount != 1) "s" else ""} connected"
                } else {
                    "Mesh: Not running (isRunning false)"
                }
            } catch (_: Exception) {
                "Mesh: Error"
            }
        } else {
            "Mesh: Not running (manager null)"
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