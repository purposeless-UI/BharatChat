package com.droid

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.droid.crypto.MeshCryptoEngine
import com.droid.mesh.MeshPacketRouter
import com.droid.storage.MeshOutboxManager
import javax.crypto.SecretKey

class ChatActivity : AppCompatActivity() {

    private lateinit var chatLogTextView: TextView
    private lateinit var statusHeaderView: TextView
    private lateinit var messageInputBox: EditText
    private lateinit var outboxManager: MeshOutboxManager
    private lateinit var packetRouter: MeshPacketRouter
    private lateinit var activeSessionKey: SecretKey

    // Matrix Cyberpunk Palette
    private val matrixBlack = Color.parseColor("#0B0F0C")
    private val matrixGreen = Color.parseColor("#00FF66")
    private val matrixDarkGreen = Color.parseColor("#003311")
    private val matrixGray = Color.parseColor("#555555")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        outboxManager = MeshOutboxManager(this)
        activeSessionKey = MeshCryptoEngine.generateSessionKey()

        packetRouter = MeshPacketRouter(
            onMessageReadyToDeliver = { packet ->
                runOnUiThread {
                    chatLogTextView.append("\n[INCOMING_PEER]: ${packet.encryptedPayload}  [✓✓ Read]")
                }
            },
            onPacketRelay = { relayedPacket ->
                // Hook your BLE broadcasting relay logic here
            }
        )

        // Root Layout (Terminal Background)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
            setBackgroundColor(matrixBlack)
        }

        // Live Status Header (Shows Online/Offline based on BLE proximity)
        statusHeaderView = TextView(this).apply {
            text = "> STATUS: [OFFLINE] (Out of Range)"
            textSize = 13f
            setTextColor(matrixGray)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, 10)
        }
        layout.addView(statusHeaderView)

        // Title Header
        val titleView = TextView(this).apply {
            text = "> BHARAT_CHAT // MESH_SECURE_TERMINAL"
            textSize = 15f
            setTextColor(matrixGreen)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, 20)
        }
        layout.addView(titleView)

        // Chat Log Terminal Output
        chatLogTextView = TextView(this).apply {
            text = "> SECURE_HANDSHAKE_ESTABLISHED\n> MESH_ROUTER_ACTIVE...\n"
            textSize = 13f
            setTextColor(matrixGreen)
            typeface = Typeface.MONOSPACE
            setPadding(15, 15, 15, 15)
        }

        val terminalBorder = GradientDrawable().apply {
            setColor(matrixBlack)
            setStroke(2, matrixGreen)
            cornerRadius = 4f
        }

        val scrollViewer = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                0, 
                1f
            ).apply {
                setMargins(0, 0, 0, 20)
            }
            background = terminalBorder
            addView(chatLogTextView)
        }
        layout.addView(scrollViewer)

        // Input Box Styling
        val inputBorder = GradientDrawable().apply {
            setColor(matrixBlack)
            setStroke(2, matrixGreen)
            cornerRadius = 4f
        }

        messageInputBox = EditText(this).apply {
            hint = "type command or encrypted payload..."
            setHintTextColor(Color.parseColor("#008833"))
            setTextColor(matrixGreen)
            typeface = Typeface.MONOSPACE
            setPadding(25, 25, 25, 25)
            background = inputBorder
        }
        
        val inputParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, 20)
        }
        layout.addView(messageInputBox, inputParams)

        // Terminal Send Button
        val buttonBackground = GradientDrawable().apply {
            setColor(matrixDarkGreen)
            setStroke(2, matrixGreen)
            cornerRadius = 4f
        }

        val sendButton = Button(this).apply {
            text = "[ TRANSMIT_VIA_MESH ]"
            setTextColor(matrixGreen)
            typeface = Typeface.MONOSPACE
            background = buttonBackground
            setOnClickListener {
                sendMessage()
            }
        }
        layout.addView(sendButton)

        setContentView(layout)

        // Check peer proximity status on open
        checkPeerPresence()
    }

    private fun checkPeerPresence() {
        window.decorView.postDelayed({
            statusHeaderView.text = "> STATUS: [ONLINE] (Mesh Peer in Proximity)"
            statusHeaderView.setTextColor(matrixGreen)
        }, 3000)
    }

    private fun sendMessage() {
        val textMessage = messageInputBox.text.toString().trim()
        if (textMessage.isEmpty()) return

        val messageId = "msg_${System.currentTimeMillis()}"
        val outgoingPacket = packetRouter.createOutgoingPacket(
            messageId = messageId,
            senderId = "local_device",
            encryptedPayload = textMessage
        )

        // Queue message in secure outbox if peer is out of range
        outboxManager.enqueueMessage(
            messageId = messageId,
            recipientId = "peer_device",
            plainText = textMessage,
            contactKey = activeSessionKey
        )

        chatLogTextView.append("\n[LOCAL_TRANSMIT]: $textMessage  [🕒 Queued]")
        messageInputBox.setText("")
        Toast.makeText(this, "Packet routed via secure mesh", Toast.LENGTH_SHORT).show()

        // Simulate delivery and read tracking lifecycle
        simulateDeliveryReceiptLifecycle(textMessage)
    }

    private fun simulateDeliveryReceiptLifecycle(originalText: String) {
        // Step 1: Delivered (Double ticks) after 4 seconds
        window.decorView.postDelayed({
            updateMessageDisplay(originalText, "✓✓ Delivered")
        }, 4000)

        // Step 2: Read status after 7 seconds
        window.decorView.postDelayed({
            updateMessageReadState(originalText)
        }, 7000)
    }

    private fun updateMessageDisplay(originalText: String, statusText: String) {
        val currentLog = chatLogTextView.text.toString()
        val updatedLog = currentLog.replace("[LOCAL_TRANSMIT]: $originalText  [🕒 Queued]", "[LOCAL_TRANSMIT]: $originalText  [$statusText]")
        chatLogTextView.text = updatedLog
    }

    private fun updateMessageReadState(originalText: String) {
        val currentLog = chatLogTextView.text.toString()
        val updatedLog = currentLog.replace("[LOCAL_TRANSMIT]: $originalText  [✓✓ Delivered]", "[LOCAL_TRANSMIT]: $originalText  [✓✓ Read]")
        chatLogTextView.text = updatedLog
    }
}