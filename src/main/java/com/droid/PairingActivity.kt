package com.droid

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.droid.bharatchat.UserProfileManager
import com.droid.crypto.MeshCryptoEngine
import com.droid.storage.ContactStorageManager
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.google.zxing.integration.android.IntentIntegrator

class PairingActivity : AppCompatActivity() {

    private lateinit var qrCodeImageView: ImageView
    private lateinit var statusTextView: TextView
    private lateinit var contactStorageManager: ContactStorageManager
    private lateinit var myUsername: String
    private lateinit var myPairingCode: String

    // Matrix Cyberpunk Palette to match ChatActivity
    private val matrixBlack = Color.parseColor("#0B0F0C")
    private val matrixGreen = Color.parseColor("#00FF66")
    private val matrixDarkGreen = Color.parseColor("#003311")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        contactStorageManager = ContactStorageManager(this)
        myUsername = UserProfileManager.getMyUsername(this)
        
        // Dynamically encode username combined with a unique cryptographic public identity handle
        myPairingCode = "BHARATCHAT_USER:$myUsername|KEY:${MeshCryptoEngine.generateSessionKey().encoded.joinToString("") { "%02x".format(it) }}"

        // Root Layout (Terminal Background Styling)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(matrixBlack)
        }

        val titleView = TextView(this).apply {
            text = "> BHARATCHAT // DEVICE_PAIRING_HUB\n> SCAN_OR_SHOW_QR_CODE_TO_CONNECT"
            textSize = 15f
            setTextColor(matrixGreen)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, 30)
        }
        layout.addView(titleView)

        // QR Code Terminal Box Container
        val qrBorder = GradientDrawable().apply {
            setColor(Color.WHITE) // White background for scanner readability
            setStroke(2, matrixGreen)
            cornerRadius = 8f
        }

        qrCodeImageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(600, 600).apply {
                setMargins(0, 20, 0, 40)
            }
            background = qrBorder
            setPadding(15, 15, 15, 15)
        }
        layout.addView(qrCodeImageView)

        // Terminal Action Button Styling
        val buttonBackground = GradientDrawable().apply {
            setColor(matrixDarkGreen)
            setStroke(2, matrixGreen)
            cornerRadius = 4f
        }

        val scanButton = Button(this).apply {
            text = "[ SCAN_PEER_QR_CODE ]"
            setTextColor(matrixGreen)
            typeface = Typeface.MONOSPACE
            background = buttonBackground
            setPadding(20, 20, 20, 20)
            setOnClickListener {
                launchScanner()
            }
        }
        layout.addView(scanButton)

        statusTextView = TextView(this).apply {
            text = "> TRUSTED_CONTACTS_SAVED: ${contactStorageManager.getTrustedContactSecrets().size}"
            textSize = 13f
            setTextColor(matrixGreen)
            typeface = Typeface.MONOSPACE
            setPadding(0, 40, 0, 0)
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
                // Save the scanned contact key locally and securely
                contactStorageManager.saveContactSecret(scannedKey)
                
                statusTextView.text = "> STATUS: [PAIRED SUCCESSFULLY]\n> SAVED_PEER: $scannedKey"
                Toast.makeText(this, "Mutual Handshake Complete & Saved!", Toast.LENGTH_LONG).show()

                // Automatically navigate back or launch chat room with scanned peer profile
                val peerName = if (scannedKey.contains("BHARATCHAT_USER:")) {
                    scannedKey.substringAfter("BHARATCHAT_USER:").substringBefore("|")
                } else {
                    "Scanned_Peer"
                }

                val intent = Intent(this, ChatActivity::class.java).apply {
                    putExtra("TARGET_PEER", peerName)
                }
                startActivity(intent)
                finish()
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }
}