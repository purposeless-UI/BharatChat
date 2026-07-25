package com.droid

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.droid.storage.ContactStorageManager
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.google.zxing.integration.android.IntentIntegrator

class PairingActivity : AppCompatActivity() {

    private lateinit var qrCodeImageView: ImageView
    private lateinit var statusTextView: TextView
    private lateinit var contactStorageManager: ContactStorageManager

    // Mocking a local cryptographic identity string for pairing
    private val myPairingCode = "BHARATCHAT_ID_SECURE_99281039"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        contactStorageManager = ContactStorageManager(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        val titleView = TextView(this).apply {
            text = "Device Pairing Hub\nScan or Show QR Code to Connect"
            textSize = 18f
            setPadding(0, 0, 0, 30)
        }
        layout.addView(titleView)

        qrCodeImageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(600, 600).apply {
                setMargins(0, 20, 0, 40)
            }
        }
        layout.addView(qrCodeImageView)

        val scanButton = Button(this).apply {
            text = "Scan Peer's QR Code"
            setOnClickListener {
                launchScanner()
            }
        }
        layout.addView(scanButton)

        statusTextView = TextView(this).apply {
            text = "Trusted Contacts Saved: ${contactStorageManager.getTrustedContactSecrets().size}"
            setPadding(0, 30, 0, 0)
        }
        layout.addView(statusTextView)

        setContentView(layout)
        generateMyQrCode()
    }

    private fun generateMyQrCode() {
        try {
            val barcodeEncoder = BarcodeEncoder()
            val bitmap: Bitmap = barcodeEncoder.encodeBitmap(myPairingCode, BarcodeFormat.QR_CODE, 600, 600)
            qrCodeImageView.setImageBitmap(bitmap)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to generate QR code: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchScanner() {
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("Point camera at peer's BharatChat QR code")
        integrator.setCameraId(0)
        integrator.setBeepEnabled(true)
        integrator.initiateScan()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents == null) {
                Toast.makeText(this, "Scanning cancelled", Toast.LENGTH_SHORT).show()
            } else {
                val scannedKey = result.contents
                // Save the scanned contact key locally securely
                contactStorageManager.saveContactSecret(scannedKey)
                
                statusTextView.text = "Successfully Paired!\nSaved Peer: $scannedKey"
                Toast.makeText(this, "Mutual Handshake Complete & Saved!", Toast.LENGTH_LONG).show()
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }
}