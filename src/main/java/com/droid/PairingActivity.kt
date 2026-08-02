package com.droid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Suppress(
    "SetTextI18n",
    "SameParameterValue",
    "SpellCheckingInspection",
    "UseSetterMethod",
    "KTX"
)
class PairingActivity : AppCompatActivity() {

    private lateinit var codeInput: EditText
    private lateinit var scannerContainer: LinearLayout
    private lateinit var previewView: PreviewView
    private var cameraExecutor: ExecutorService? = null
    private var hasHandledScan = false
    private val tag = "PairingActivity"

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.d(tag, "Camera permission granted")
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required.", Toast.LENGTH_LONG).show()
            Log.w(tag, "Camera permission denied")
            scannerContainer.visibility = android.view.View.GONE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(tag, "onCreate")

        val identity = IdentityStore.loadOrCreate(this)
        val fullCode = IdentityStore.pairingCode(identity)
        val shortCode = fullCode.removePrefix("bharatchat://").takeLast(6).uppercase()
        Log.d(tag, "Full code: $fullCode, short: $shortCode")

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.DKGRAY)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            setBackgroundColor(Color.DKGRAY)
        }

        root.addView(TextView(this).apply {
            text = "◈ YOUR PAIRING CODE"
            textSize = 18f
            setPadding(0, 0, 0, 4)
            setTextColor(Color.rgb(255, 165, 0))
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = shortCode
            textSize = 42f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 16)
            setTextIsSelectable(true)
            setTextColor(Color.rgb(255, 165, 0))
        })

        val qrBitmap = generateQrBitmap(fullCode, 400)
        root.addView(ImageView(this).apply {
            setImageBitmap(qrBitmap)
            layoutParams = LinearLayout.LayoutParams(400, 400).apply { gravity = android.view.Gravity.CENTER }
        })

        root.addView(TextView(this).apply {
            text = "SCAN QR CODE OF OTHER DEVICE"
            textSize = 14f
            setPadding(0, 24, 0, 8)
            setTextColor(Color.rgb(255, 165, 0))
        })

        root.addView(TextView(this).apply {
            text = "Note: 6‑digit code works only for already‑paired contacts."
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 16)
        })

        codeInput = EditText(this).apply {
            hint = "Paste full key, URL, or 6-digit code"
            setPadding(24, 24, 24, 24)
            setTextColor(Color.rgb(255, 165, 0))
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.DKGRAY)
            val drawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.DKGRAY)
                setStroke(2, Color.rgb(255, 165, 0))
                setCornerRadius(8f)
            }
            background = drawable
        }
        root.addView(codeInput)

        root.addView(Button(this).apply {
            text = "PAIR NOW"
            setTextColor(Color.rgb(255, 165, 0))
            setBackgroundColor(Color.DKGRAY)
            val drawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.DKGRAY)
                setStroke(2, Color.rgb(255, 165, 0))
                setCornerRadius(8f)
            }
            background = drawable
            setOnClickListener { processConnection(codeInput.text.toString().trim()) }
        })

        root.addView(Button(this).apply {
            text = "SCAN QR CODE"
            setTextColor(Color.rgb(255, 165, 0))
            setBackgroundColor(Color.DKGRAY)
            val drawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.DKGRAY)
                setStroke(2, Color.rgb(255, 165, 0))
                setCornerRadius(8f)
            }
            background = drawable
            setOnClickListener { toggleScanner() }
        })

        scannerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 500)
            visibility = android.view.View.GONE
        }
        previewView = PreviewView(this)
        scannerContainer.addView(previewView)
        root.addView(scannerContainer)

        scrollView.addView(root)
        setContentView(scrollView)
        Log.d(tag, "UI setup complete")
    }

    // ===== UPDATED: toggle scanner with release =====
    private fun toggleScanner() {
        // If scanner is already visible, hide it and release camera
        if (scannerContainer.visibility == android.view.View.VISIBLE) {
            scannerContainer.visibility = android.view.View.GONE
            releaseCamera()
            return
        }

        Log.d(tag, "toggleScanner called – showing scanner")
        scannerContainer.visibility = android.view.View.VISIBLE
        hasHandledScan = false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            Log.d(tag, "Camera permission already granted, starting camera")
            startCamera()
        } else {
            Log.d(tag, "Requesting camera permission")
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        Log.d(tag, "startCamera called")
        val providerFuture = ProcessCameraProvider.getInstance(this)
        cameraExecutor?.shutdown()
        cameraExecutor = Executors.newSingleThreadExecutor()

        providerFuture.addListener({
            val provider = providerFuture.get()
            Log.d(tag, "Camera provider obtained")

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
                Log.d(tag, "Preview surface provider set")
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    val analyzer = QRCodeAnalyzer { scannedText ->
                        Log.d(tag, "QRCodeAnalyzer callback invoked with text: '$scannedText'")
                        handleScannedCode(scannedText)
                    }
                    it.setAnalyzer(cameraExecutor!!, analyzer)
                    Log.d(tag, "ImageAnalysis built and analyzer set")
                }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                Log.d(tag, "Camera bound to lifecycle")
            } catch (e: Exception) {
                Log.e(tag, "Failed to bind camera", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ===== NEW: release camera =====
    private fun releaseCamera() {
        try {
            cameraExecutor?.shutdown()
            cameraExecutor = null
            val providerFuture = ProcessCameraProvider.getInstance(this)
            providerFuture.addListener({
                val provider = providerFuture.get()
                provider.unbindAll()
                Log.d(tag, "Camera unbound successfully")
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            Log.e(tag, "Error releasing camera", e)
        }
    }

    private fun handleScannedCode(text: String) {
        if (hasHandledScan) {
            Log.d(tag, "Scan already handled, ignoring")
            return
        }
        hasHandledScan = true
        Log.d(tag, "handleScannedCode: '$text'")
        runOnUiThread {
            val cleanText = if (text.startsWith("bharatchat://")) {
                text.removePrefix("bharatchat://")
            } else text
            codeInput.setText(cleanText)
            scannerContainer.visibility = android.view.View.GONE
            releaseCamera() // ✅ Release camera after scanning
            processConnection(text.trim())
        }
    }

    // ===== UPDATED: extract 130‑hex (uncompressed) and 66‑hex (compressed) =====
    private fun processConnection(rawInput: String) {
        val clean = rawInput.trim()
        Log.d(tag, "processConnection: rawInput='$rawInput', clean='$clean'")
        if (clean.isEmpty()) {
            Toast.makeText(this, "Please enter or scan a code.", Toast.LENGTH_SHORT).show()
            hasHandledScan = false
            return
        }

        // 1. Check for 6‑digit short code
        if (clean.length == 6) {
            lifecycleScope.launch {
                try {
                    val contacts = ContactsStore.list(this@PairingActivity)
                    val match = contacts.firstOrNull {
                        it.xOnlyPubkeyHex.takeLast(6).equals(clean, ignoreCase = true) ||
                                it.pubkeyHex.takeLast(6).equals(clean, ignoreCase = true)
                    }
                    if (match != null) {
                        processContact(match.pubkeyHex)
                    } else {
                        Toast.makeText(
                            this@PairingActivity,
                            "Short code not found locally. Please scan the full QR or paste the full public key.",
                            Toast.LENGTH_LONG
                        ).show()
                        hasHandledScan = false
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@PairingActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    hasHandledScan = false
                }
            }
            return
        }

        // 2. Try to extract the public key from the input
        var extractedKey: String? = null

        // Strip any known scheme
        var content = clean
        val schemePattern = Regex("""^[a-zA-Z]+://""")
        if (schemePattern.containsMatchIn(content)) {
            content = schemePattern.replaceFirst(content, "")
            Log.d(tag, "After stripping scheme: '$content'")
        }
        if (content.startsWith("bharatchat:", ignoreCase = true)) {
            content = content.substring("bharatchat:".length).trim()
            Log.d(tag, "After stripping 'bharatchat:': '$content'")
        }

        // Look for 130‑hex (uncompressed) – full key
        val hex130 = Regex("""[0-9a-fA-F]{130}""").find(content)?.value
        if (hex130 != null) {
            val lower = hex130.lowercase()
            if (lower.startsWith("04")) {
                extractedKey = lower
                Log.d(tag, "Found 130‑hex uncompressed key: '$extractedKey'")
            } else {
                Log.w(tag, "Found 130‑hex but invalid prefix: $lower")
                Toast.makeText(this, "Found a 130‑hex string but it doesn't start with 04. Please use the QR code from this app.", Toast.LENGTH_LONG).show()
                hasHandledScan = false
                return
            }
        } else {
            // If no 130‑hex, look for 66‑hex (compressed)
            val hex66 = Regex("""[0-9a-fA-F]{66}""").find(content)?.value
            if (hex66 != null) {
                val lower = hex66.lowercase()
                if (lower.startsWith("02") || lower.startsWith("03")) {
                    extractedKey = lower
                    Log.d(tag, "Found 66‑hex compressed key: '$extractedKey'")
                } else if (lower.startsWith("04")) {
                    // 66‑hex with 04 is invalid – truncated uncompressed key
                    Log.w(tag, "Found 66‑hex with 04 prefix – this is an invalid truncated key: $lower")
                    Toast.makeText(
                        this,
                        "This QR contains an uncompressed key (should be 130 hex), but only 66 hex were extracted. Please scan the full QR code.",
                        Toast.LENGTH_LONG
                    ).show()
                    hasHandledScan = false
                    return
                } else {
                    Log.w(tag, "Found 66‑hex but invalid prefix: $lower")
                    Toast.makeText(
                        this,
                        "Found a 66‑hex string but it doesn't start with 02, 03, or 04. Please use the QR code from this app.",
                        Toast.LENGTH_LONG
                    ).show()
                    hasHandledScan = false
                    return
                }
            }
        }

        // If not found, look for 64‑hex (x‑only) – reject
        if (extractedKey == null) {
            val hex64 = Regex("""[0-9a-fA-F]{64}""").find(content)?.value
            if (hex64 != null) {
                Log.w(tag, "Found 64‑hex (x‑only): $hex64")
                Toast.makeText(
                    this,
                    "X‑only key provided (64 hex). Please provide the full compressed key (66 hex) from the QR.",
                    Toast.LENGTH_LONG
                ).show()
                hasHandledScan = false
                return
            }
        }

        // If still null, try IdentityStore (if it starts with bharatchat://)
        if (extractedKey == null && clean.startsWith("bharatchat://", ignoreCase = true)) {
            try {
                extractedKey = IdentityStore.pubkeyFromPairingCode(clean)
                Log.d(tag, "IdentityStore extracted: '$extractedKey'")
            } catch (e: Exception) {
                Log.e(tag, "IdentityStore extraction failed", e)
            }
        }

        if (extractedKey == null) {
            Log.e(tag, "Failed to extract any public key from input")
            Toast.makeText(this, "Invalid code format. Could not extract a public key.", Toast.LENGTH_SHORT).show()
            hasHandledScan = false
            return
        }

        // Now we have either 130‑hex (uncompressed) or 66‑hex (compressed).
        // processContact will call ContactsStore.add, which normalises.
        processContact(extractedKey)
    }

    // ===== UPDATED: show dialog to ask for a name =====
    private fun processContact(pubkey: String) {
        Log.d(tag, "processContact: $pubkey")
        val myIdentity = IdentityStore.loadOrCreate(this)
        val myXOnly = myIdentity.xOnlyPublicKeyHex
        val myFull = myIdentity.compressedPublicKeyHex
        if (pubkey.equals(myXOnly, ignoreCase = true) || pubkey.equals(myFull, ignoreCase = true)) {
            Log.w(tag, "Attempted to pair with own key")
            Toast.makeText(this, "Cannot pair with your own code.", Toast.LENGTH_SHORT).show()
            hasHandledScan = false
            return
        }

        // Show dialog to get a custom name
        val editText = EditText(this).apply {
            hint = "Enter name (optional)"
        }
        AlertDialog.Builder(this)
            .setTitle("Name this contact")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val customName = editText.text.toString().trim()
                val finalName = if (customName.isNotEmpty()) customName else "Peer_${pubkey.take(6).uppercase()}"
                saveContact(pubkey, finalName)
            }
            .setNegativeButton("Skip") { _, _ ->
                saveContact(pubkey, "Peer_${pubkey.take(6).uppercase()}")
            }
            .show()
    }

    // Helper to save contact and open ChatActivity
    private fun saveContact(pubkey: String, name: String) {
        lifecycleScope.launch {
            try {
                ContactsStore.add(this@PairingActivity, pubkey, name)
                Log.d(tag, "Contact saved: $name")
                Toast.makeText(this@PairingActivity, "Successfully paired with $name!", Toast.LENGTH_LONG).show()
                val intent = Intent(this@PairingActivity, ChatActivity::class.java)
                intent.putExtra("contactPubkey", pubkey)
                intent.putExtra("contactName", name)
                startActivity(intent)
                finish()
            } catch (e: Exception) {
                Log.e(tag, "Failed to save contact", e)
                Toast.makeText(this@PairingActivity, "Failed to save contact: ${e.message}", Toast.LENGTH_LONG).show()
                hasHandledScan = false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseCamera() // ✅ Use the unified release method
        Log.d(tag, "onDestroy")
    }

    private fun generateQrBitmap(data: String, sizePx: Int): Bitmap {
        val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.DKGRAY else Color.WHITE)
            }
        }
        return bitmap
    }
}

// QR Code Analyzer (unchanged)
private class QRCodeAnalyzer(private val onCode: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val tag = "QRCodeAnalyzer"
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    )
    private var frameCount = 0

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        frameCount++
        if (frameCount % 30 == 0) {
            Log.d(tag, "analyze called, frame #$frameCount")
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            Log.w(tag, "mediaImage is null, closing")
            imageProxy.close()
            return
        }

        Log.d(tag, "Processing frame #$frameCount, image: ${mediaImage.width}x${mediaImage.height}")
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                Log.d(tag, "Barcodes found: ${barcodes.size}")
                if (barcodes.isNotEmpty()) {
                    val text = barcodes.firstOrNull()?.rawValue
                    if (!text.isNullOrBlank()) {
                        Log.d(tag, "Found barcode: '$text'")
                        onCode(text)
                    } else {
                        Log.w(tag, "Barcode text is null or blank")
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(tag, "Barcode scanning failed", e)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }
}