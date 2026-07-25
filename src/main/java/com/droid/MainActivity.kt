package com.droid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.droid.bharatchat.UserProfileManager
import com.droid.mesh.BluetoothMeshService

class MainActivity : AppCompatActivity() {
    private lateinit var meshService: BluetoothMeshService
    private lateinit var statusTextView: TextView

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Use the single app-wide mesh service (owned by BharatChatApp) instead of a
        // private instance. A private instance here had its own empty, never-shared
        // discovered-peer list, so "Launch Matrix Chat" would say "No Active Peers
        // Found" even after a peer had already been discovered/paired elsewhere.
        meshService = BharatChatApp.instance.globalBluetoothMeshService

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        // Display current local username on dashboard
        val myName = UserProfileManager.getMyUsername(this)
        statusTextView = TextView(this).apply {
            text = "Welcome to BharatChat ($myName)\nOffline Mesh Status: Idle"
            textSize = 18f
            setPadding(0, 0, 0, 40)
        }
        layout.addView(statusTextView)

        val startButton = Button(this).apply {
            text = "Start Mesh Node"
            setOnClickListener {
                checkPermissionsAndStart()
            }
        }
        layout.addView(startButton)

        val pairButton = Button(this).apply {
            text = "Open Pairing QR Hub"
            setOnClickListener {
                val intent = Intent(this@MainActivity, PairingActivity::class.java)
                startActivity(intent)
            }
        }
        layout.addView(pairButton)

        val chatButton = Button(this).apply {
            text = "Launch Matrix Chat"
            setOnClickListener {
                showPeerSelectionDialog()
            }
        }
        layout.addView(chatButton)

        setContentView(layout)
    }

    private fun showPeerSelectionDialog() {
        // Fetch active peers discovered by the mesh service scan engine
        val activePeers = meshService.getDiscoveredPeerNames()

        if (activePeers.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No Active Peers Found")
                .setMessage("Make sure the mesh node is started, or use 'Open Pairing QR Hub' to connect with a nearby device first.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val peersArray = activePeers.toTypedArray<CharSequence>()
        AlertDialog.Builder(this)
            .setTitle("Select Peer to Chat")
            .setItems(peersArray) { _, which ->
                val selectedPeer = peersArray[which].toString()
                openChatWithUser(selectedPeer)
            }
            .show()
    }

    private fun openChatWithUser(peerName: String) {
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("TARGET_PEER", peerName)
        }
        startActivity(intent)
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            startMeshOperations()
        }
    }

    private fun startMeshOperations() {
        meshService.startAdvertising()
        meshService.startScanning()
        val myName = UserProfileManager.getMyUsername(this)
        statusTextView.text = "Welcome to BharatChat ($myName)\nOffline Mesh Status: Active (Scanning & Advertising)"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startMeshOperations()
            } else {
                statusTextView.text = "Mesh Status: Permissions Required for BLE"
            }
        }
    }

    // Note: no onDestroy() override here. meshService is the shared app-wide instance
    // (owned by BharatChatApp), so it must keep running in the background even after
    // this screen is destroyed - e.g. navigating to Pairing or Chat used to call
    // stopAll() on a private copy of the service when this Activity was destroyed,
    // which had no real effect on the others, but doing it on the SHARED instance
    // would have silently killed scanning/advertising for the whole app.
}