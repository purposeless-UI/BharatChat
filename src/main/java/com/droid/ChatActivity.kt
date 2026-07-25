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
import com.droid.bharatchat.UserProfileManager
import com.droid.crypto.MeshCryptoEngine
import com.droid.mesh.BluetoothMeshService
import com.droid.mesh.MeshPacket
import com.droid.mesh.MeshPacketRouter
import com.droid.storage.ContactStorageManager
import com.droid.storage.MeshOutboxManager
import javax.crypto.SecretKey

class ChatActivity : AppCompatActivity() {

    private lateinit var chatLogTextView: TextView
    private lateinit var statusHeaderView: TextView
    private lateinit var messageInputBox: EditText
    private lateinit var outboxManager: MeshOutboxManager
    private lateinit var packetRouter: MeshPacketRouter
    private lateinit var meshService: BluetoothMeshService
    private lateinit var contactStorageManager: ContactStorageManager
    private lateinit var activeSessionKey: SecretKey
    
    private var targetPeerName: String = "Unknown_Peer"
    private lateinit var myUsername: String

    // Matrix Cyberpunk Palette
    private val matrixBlack = Color.parseColor("#0B0F0C")
    private val matrixGreen = Color.parseColor("#00FF66")
    private val matrixDarkGreen = Color.parseColor("#003311")
    private val matrixRed = Color.parseColor("#FF3333")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        targetPeerName = intent.getStringExtra("TARGET_PEER") ?: "Unknown_Peer"
        myUsername = UserProfileManager.getMyUsername(this)

        outboxManager = MeshOutboxManager(this)
        contactStorageManager = ContactStorageManager(this)
        activeSessionKey = MeshCryptoEngine.generateSessionKey()

        // Use the single app-wide mesh service and packet router (owned by
        // BharatChatApp) instead of creating new ones here. Previously ChatActivity
        // instantiated its own BluetoothMeshService, which meant:
        //  1. It ran a second, simultaneous BLE advertisement alongside the one
        //     already running in the background service, which many phones can't
        //     do at the same time - so sends silently failed even though the UI
        //     showed "Sent".
        //  2. Packets received by the background service while this screen wasn't
        //     open never reached this router, and vice versa.
        meshService = BharatChatApp.instance.globalBluetoothMeshService
        packetRouter = BharatChatApp.instance.globalPacketRouter

        // Root Layout (Terminal Background)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
            setBackgroundColor(matrixBlack)
        }

        // Live Status Header
        statusHeaderView = TextView(this).apply {
            text = "> STATUS: [CONNECTED TO $targetPeerName]"
            textSize = 13f
            setTextColor(matrixGreen)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, 10)
        }
        layout.addView(statusHeaderView)

        // Title Header & Clear History Button Row
        val topControlLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 0, 0, 20)
        }

        val titleView = TextView(this).apply {
            text = "> BHARAT_CHAT // SECURE_PEER: $targetPeerName"
            textSize = 14f
            setTextColor(matrixGreen)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topControlLayout.addView(titleView)

        val clearButtonBackground = GradientDrawable().apply {
            setColor(Color.parseColor("#330000"))
            setStroke(1, matrixRed)
            cornerRadius = 4f
        }

        val clearHistoryButton = Button(this).apply {
            text = "[ CLEAR_PANEL ]"
            textSize = 11f
            setTextColor(matrixRed)
            typeface = Typeface.MONOSPACE
            background = clearButtonBackground
            setOnClickListener {
                clearCurrentChatHistory()
            }
        }
        topControlLayout.addView(clearHistoryButton)
        layout.addView(topControlLayout)

        // Chat Log Terminal Output
        chatLogTextView = TextView(this).apply {
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
            hint = "type message to $targetPeerName..."
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
            text = "[ TRANSMIT_VIA_BLUETOOTH ]"
            setTextColor(matrixGreen)
            typeface = Typeface.MONOSPACE
            background = buttonBackground
            setOnClickListener {
                sendMessage()
            }
        }
        layout.addView(sendButton)

        setContentView(layout)

        // Load saved chat history specific to this user panel on start
        loadPeerChatHistory()

        // Scanning/advertising already runs continuously in the shared background
        // service (BharatChatApp), so there's no need to start it again here.
    }

    override fun onResume() {
        super.onResume()
        // Become the live listener for this peer while this screen is visible, so
        // incoming messages appear immediately instead of only after re-opening the
        // chat. Anything that arrives while we're NOT the active listener is still
        // saved to storage by BharatChatApp and picked up below by reloading history.
        BharatChatApp.instance.activeMessageListener = { packet ->
            if (packet.senderId == targetPeerName) {
                runOnUiThread {
                    val logEntry = "[${packet.senderId}]: ${packet.encryptedPayload}  [\u2713\u2713 Received]"
                    chatLogTextView.append("\n$logEntry")
                }
            }
        }
        loadPeerChatHistory()
    }

    override fun onPause() {
        super.onPause()
        if (BharatChatApp.instance.activeMessageListener != null) {
            BharatChatApp.instance.activeMessageListener = null
        }
    }

    private fun loadPeerChatHistory() {
        val history = contactStorageManager.getPeerChatHistory(targetPeerName)
        if (history.isEmpty()) {
            chatLogTextView.text = "> SECURE_SESSION_INITIALIZED ($myUsername -> $targetPeerName)\n> NO_PRIOR_HISTORY_FOUND...\n"
        } else {
            val sb = StringBuilder("> SECURE_SESSION_LOADED ($myUsername -> $targetPeerName)\n")
            for (msg in history) {
                sb.append("\n$msg")
            }
            chatLogTextView.text = sb.toString()
        }
    }

    private fun clearCurrentChatHistory() {
        contactStorageManager.clearPeerChatHistory(targetPeerName)
        chatLogTextView.text = "> CHAT_PANEL_CLEARED_FOR $targetPeerName\n"
        Toast.makeText(this, "Chat history deleted for $targetPeerName", Toast.LENGTH_SHORT).show()
    }

    private fun sendMessage() {
        val textMessage = messageInputBox.text.toString().trim()
        if (textMessage.isEmpty()) return

        val messageId = "msg_${System.currentTimeMillis()}"
        
        // Construct structured multi-hop MeshPacket using router
        val outgoingPacket = packetRouter.createOutgoingPacket(
            messageId = messageId,
            senderId = myUsername,
            recipientId = targetPeerName,
            encryptedPayload = textMessage
        )

        // Show a pending state immediately, then correct it once we know the real
        // outcome. Previously transmitPacket()'s return value was always `true`
        // regardless of whether the BLE radio actually accepted the broadcast, so
        // the chat log always claimed "Sent" even when nothing went out.
        val pendingEntry = "[$myUsername]: $textMessage  [\u23F3 Sending...]"
        chatLogTextView.append("\n$pendingEntry")
        messageInputBox.setText("")

        meshService.transmitPacket(outgoingPacket.serialize()) { isSuccessful ->
            runOnUiThread {
                val logEntry: String
                if (isSuccessful) {
                    logEntry = "[$myUsername]: $textMessage  [\u2713\u2713 Sent (BLE Mesh)]"
                } else {
                    // Queue in outbox if transmission failed so it can be retried later
                    outboxManager.enqueueMessage(
                        messageId = messageId,
                        recipientId = targetPeerName,
                        plainText = textMessage,
                        contactKey = activeSessionKey
                    )
                    logEntry = "[$myUsername]: $textMessage  [\uD83D\uDD52 Queued (Out of Range)]"
                    Toast.makeText(this, "Device out of direct range. Saved to outbox queue.", Toast.LENGTH_LONG).show()
                }
                // Replace the pending line with the final status
                val current = chatLogTextView.text.toString()
                chatLogTextView.text = current.removeSuffix(pendingEntry) + logEntry
                contactStorageManager.saveMessageToPeerHistory(targetPeerName, logEntry)
            }
        }
    }

    // No onDestroy() override: meshService is the shared app-wide instance and must
    // keep running after this screen closes. Calling stopAll() here (as before) would
    // kill BLE scanning/advertising for the entire app the moment you left a chat.
}