package com.droid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.droid.mesh.BluetoothMeshService

class MainActivity : AppCompatActivity() {
    private lateinit var meshService: BluetoothMeshService
    private lateinit var statusTextView: TextView

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        meshService = BluetoothMeshService(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        statusTextView = TextView(this).apply {
            text = "Welcome to BharatChat\nOffline Mesh Status: Idle"
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
                val intent = Intent(this@MainActivity, ChatActivity::class.java)
                startActivity(intent)
            }
        }
        layout.addView(chatButton)

        setContentView(layout)
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
        statusTextView.text = "Welcome to BharatChat\nOffline Mesh Status: Active (Scanning & Advertising)"
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

    override fun onDestroy() {
        super.onDestroy()
        meshService.stopAll()
    }
}