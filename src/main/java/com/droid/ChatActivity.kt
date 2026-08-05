package com.droid

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.droid.ble.BleConstants
import com.droid.ble.peerIdFromPubkey
import com.droid.crypto.Secp256k1Signer
import com.droid.crypto.hexToBytes
import com.droid.storage.OutboxRetryScheduler
import com.droid.voice.VoiceMessageSender
import com.droid.voice.VoicePlayer
import com.droid.voice.VoiceRecorder
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import java.util.Locale

@Suppress("SetTextI18n", "SpellCheckingInspection", "DEPRECATION")
class ChatActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var inputEditText: TextInputEditText
    private lateinit var statusTextView: TextView
    private lateinit var sendButton: ImageView
    private lateinit var micButton: ImageView

    private lateinit var contactPubkey: String
    private lateinit var contactName: String
    private lateinit var contactPeerId: String
    private lateinit var myIdentity: Identity
    private lateinit var db: AppDatabase

    private val listenerKey = "chat-${System.currentTimeMillis()}"
    private val tag = "ChatActivity"

    private val handler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (isFinishing) return
            updateConnectionStatus()
            handler.postDelayed(this, 2000)
        }
    }

    private val messages = mutableListOf<ChatMessage>()
    // Key: packetId (String), Value: index in messages
    private val pendingMessages = mutableMapOf<String, Int>()

    // Multi‑select state
    private var multiSelectMode = false
    private val selectedPositions = mutableSetOf<Int>()
    private var actionMode: ActionMode? = null

    // Voice components
    private lateinit var voiceRecorder: VoiceRecorder
    private val voicePlayer = VoicePlayer()
    private val voiceSender = VoiceMessageSender(
        retryScheduler = MeshServiceHolder.getRetryScheduler()
    )

    // Timer for recording
    private var timerUpdateRunnable: Runnable? = null

    private var voiceDialog: AlertDialog? = null
    private var voiceStatusText: TextView? = null

    // Outbox retry scheduler
    private val retryScheduler: OutboxRetryScheduler? by lazy { MeshServiceHolder.getRetryScheduler() }

    private val requestRecordPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showVoiceRecordingDialog()
        else Toast.makeText(this, "Microphone permission is required to send voice messages.", Toast.LENGTH_LONG).show()
    }

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.chat_multi_select, menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.action_delete_selected -> {
                    deleteSelectedMessages()
                    mode.finish()
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            multiSelectMode = false
            selectedPositions.clear()
            messageAdapter.clearSelection()
            actionMode = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        // Initialize voiceRecorder now that context is available
        voiceRecorder = VoiceRecorder(cacheDir)

        // Set up toolbar
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        // Find views
        statusTextView = findViewById(R.id.statusTextView)
        recyclerView = findViewById(R.id.recyclerView)
        inputEditText = findViewById(R.id.inputEditText)
        sendButton = findViewById(R.id.sendButton)
        micButton = findViewById(R.id.micButton)

        sendButton.setOnClickListener { sendMessage() }
        micButton.setOnClickListener { checkMicrophonePermissionAndRecord() }

        // Set up RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messageAdapter = MessageAdapter()
        recyclerView.adapter = messageAdapter

        // Load contact info and messages
        try {
            val rawPubkey = intent.getStringExtra("contactPubkey") ?: run {
                Toast.makeText(this, "Missing contact public key", Toast.LENGTH_SHORT).show()
                Log.e(tag, "contactPubkey extra is null")
                finish()
                return
            }
            contactName = intent.getStringExtra("contactName") ?: "Contact"

            contactPubkey = try {
                Secp256k1Signer.normalizePublicKeyHex(rawPubkey)
            } catch (e: Exception) {
                Log.e(tag, "Failed to normalize public key: $rawPubkey", e)
                Toast.makeText(this, "Invalid public key format", Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            if (contactPubkey.length != 66 || !contactPubkey.matches(Regex("^[a-fA-F0-9]{66}$"))) {
                Toast.makeText(this, "Invalid public key format after normalisation", Toast.LENGTH_SHORT).show()
                Log.e(tag, "Invalid normalized pubkey: $contactPubkey")
                finish()
                return
            }
            if (!contactPubkey.startsWith("02") && !contactPubkey.startsWith("03")) {
                Toast.makeText(this, "Public key must be compressed (02/03)", Toast.LENGTH_SHORT).show()
                Log.e(tag, "Not compressed: $contactPubkey")
                finish()
                return
            }

            Log.d(tag, "Normalized contact pubkey: $contactPubkey (length ${contactPubkey.length})")

            contactPeerId = try {
                peerIdFromPubkey(contactPubkey)
            } catch (e: Exception) {
                Log.e(tag, "peerIdFromPubkey failed", e)
                Toast.makeText(this, "Invalid public key", Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            // Register this peer so the outbox scheduler can send queued messages
            MeshServiceHolder.registerPeer(contactPeerId, contactPubkey)
            ChatActivityState.currentContactPeerId = contactPeerId

            myIdentity = IdentityStore.loadOrCreate(this)
            db = AppDatabase.getInstance(this)

            title = contactName
            loadMessagesFromDb()
            setupListeners()
        } catch (e: Exception) {
            Log.e(tag, "onCreate error", e)
            Toast.makeText(this, "Error opening chat: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun setupListeners() {
        // ACK listener – handles both Bluetooth and Nostr ACKs
        MeshServiceHolder.addAckListener(listenerKey) { packetId, ackType ->
            Log.d(tag, "🔄 ACK received: packetId=$packetId, ackType=$ackType")
            Log.d(tag, "🔍 pendingMessages keys: ${pendingMessages.keys}")
            runOnUiThread {
                // Try to find the message index from the pending map first
                var index = pendingMessages[packetId]

                // If not found, scan the entire message list (fallback for edge cases)
                if (index == null) {
                    index = messages.indexOfFirst { it.messageId == packetId }
                    if (index != -1) {
                        Log.d(tag, "🔍 Found message by scanning list at index $index")
                    }
                }

                // index is now non‑null (either from map or from scan, could be -1)
                if (index != null && index >= 0 && index < messages.size) {
                    val msg = messages[index]
                    val newStatus = when (ackType) {
                        // Bluetooth ACK types
                        BleConstants.TYPE_DELIVERY_ACK -> MessageStatus.DELIVERED
                        BleConstants.TYPE_READ_ACK -> MessageStatus.READ
                        // Nostr ACK types
                        1000 -> MessageStatus.DELIVERED   // Nostr delivery ACK
                        1001 -> MessageStatus.READ        // Nostr read ACK
                        else -> msg.status
                    }
                    if (newStatus != msg.status) {
                        messages[index] = msg.copy(status = newStatus)
                        messageAdapter.notifyItemChanged(index)
                        lifecycleScope.launch {
                            db.messageDao().updateStatus(packetId, newStatus.ordinal)
                        }
                    }
                } else {
                    Log.w(tag, "⚠️ index lookup failed for $packetId (index=$index, size=${messages.size})")
                }
            }
        }
    }

    @Suppress("NotifyDataSetChanged")
    private fun loadMessagesFromDb() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                db.messageDao().getMessagesForContact(contactPubkey).collect { entities ->
                    messages.clear()
                    pendingMessages.clear()
                    entities.forEach { entity ->
                        val status = MessageStatus.entries[entity.status]
                        val msg = ChatMessage(
                            id = entity.id,
                            text = entity.text,
                            fromMe = entity.fromMe,
                            timestamp = entity.timestamp,
                            status = status,
                            messageId = entity.messageId, // nullable
                            type = entity.type,
                            voiceDuration = entity.voiceDuration,
                            voiceFilePath = entity.voiceFilePath
                        )
                        messages.add(msg)
                        // Only track outgoing messages that have a non‑null packetId
                        if (entity.fromMe && entity.messageId != null) {
                            pendingMessages[entity.messageId] = messages.size - 1
                        }
                    }
                    messageAdapter.notifyDataSetChanged()
                    recyclerView.scrollToPosition(messages.size - 1)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateConnectionStatus()
        handler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(pollRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        ChatActivityState.currentContactPeerId = null
        handler.removeCallbacks(pollRunnable)
        MeshServiceHolder.removeMessageListener(listenerKey)
        MeshServiceHolder.removeAckListener(listenerKey)
        voiceRecorder.cancelRecording()
        voicePlayer.stop()
        voiceDialog = null
        voiceStatusText = null
    }

    /**
     * Update the connection status display with clear indicators for:
     * - Direct Bluetooth connection
     * - Mesh relay via Bluetooth
     * - Internet relay via Nostr (works even with Bluetooth OFF)
     * - No connection
     */
    private fun updateConnectionStatus() {
        val directlyConnected = MeshServiceHolder.isDirectlyConnectedToPeer(contactPeerId)
        val hasBluetoothPeers = MeshServiceHolder.getConnectedPeerCount() > 0
        val nostrAvailable = MeshServiceHolder.isNostrAvailable()
        val bluetoothAvailable = MeshServiceHolder.isBluetoothAvailable()

        statusTextView.text = when {
            directlyConnected -> "● Connected directly to $contactName via Bluetooth"
            hasBluetoothPeers -> "○ $contactName not directly in range — messages relayed through mesh"
            nostrAvailable && !bluetoothAvailable -> "🌐 Connected via Internet relay (Bluetooth OFF)"
            nostrAvailable -> "🌐 Connected via Internet relay"
            else -> "○ $contactName not directly in range — messages relayed through mesh"
        }
    }

    /**
     * Send a text message with automatic fallback:
     * 1. Try Bluetooth direct/mesh
     * 2. If Bluetooth fails, use Nostr internet relay
     * 3. If both fail, queue for later delivery
     */
    private fun sendMessage() {
        val text = inputEditText.text?.toString().orEmpty().trim()
        if (text.isEmpty()) return
        inputEditText.text?.clear()

        if (contactPubkey.length != 66 || !contactPubkey.matches(Regex("^[a-fA-F0-9]{66}$"))) {
            Toast.makeText(this, "Invalid contact public key", Toast.LENGTH_SHORT).show()
            Log.e(tag, "Invalid contactPubkey in sendMessage: $contactPubkey")
            return
        }

        val recipientBytes = contactPubkey.hexToBytes()

        try {
            val packetId = MeshServiceHolder.sendMessage(contactPeerId, recipientBytes, text)

            if (packetId != null) {
                // Message was sent via Bluetooth OR Nostr
                val msg = ChatMessage(
                    text = text,
                    fromMe = true,
                    timestamp = System.currentTimeMillis(),
                    status = MessageStatus.SENT,
                    messageId = packetId
                )
                addMessage(msg)
                pendingMessages[packetId] = messages.size - 1
                Log.d(tag, "📤 Outgoing message ID $packetId added to pending at index ${messages.size - 1}")

                // Save to database
                lifecycleScope.launch {
                    try {
                        db.contactDao().insert(Contact(contactPubkey, contactName, System.currentTimeMillis()))

                        db.messageDao().insert(
                            MessageEntity(
                                contactPubkey = contactPubkey,
                                text = text,
                                fromMe = true,
                                timestamp = msg.timestamp,
                                status = msg.status.ordinal,
                                messageId = packetId
                            )
                        )
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to save sent message to DB", e)
                        Toast.makeText(this@ChatActivity, "Message saved locally but not to database", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                // Both Bluetooth AND Nostr failed - queue for later
                // ✅ Get sequence number NOW on main thread
                val sequenceNumber = retryScheduler?.getNextSequence() ?: System.currentTimeMillis()

                lifecycleScope.launch {
                    try {
                        // Insert the message without a packetId
                        val entity = MessageEntity(
                            contactPubkey = contactPubkey,
                            text = text,
                            fromMe = true,
                            timestamp = System.currentTimeMillis(),
                            status = MessageStatus.SENT.ordinal,
                            messageId = null   // no packetId yet
                        )
                        // Insert and get the generated ID
                        val dbMessageId = db.messageDao().insertAndGetId(entity)
                        // Add to UI immediately (shows as SENT, will be updated later)
                        val msg = ChatMessage(
                            text = text,
                            fromMe = true,
                            timestamp = entity.timestamp,
                            status = MessageStatus.SENT,
                            messageId = null
                        )
                        addMessage(msg)
                        // Enqueue with the DB ID and the pre‑retrieved sequenceNumber
                        retryScheduler?.enqueueMessage(contactPeerId, text, dbMessageId, sequenceNumber)
                        Toast.makeText(this@ChatActivity, "Message queued for later delivery", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to queue message", e)
                        Toast.makeText(this@ChatActivity, "Failed to save message: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Send failed", e)
            Toast.makeText(this, "Failed to send message", Toast.LENGTH_SHORT).show()
        }
    }

    // ----- Voice Recording & Sending -----
    private fun checkMicrophonePermissionAndRecord() {
        if (MeshServiceHolder.getConnectedPeerCount() == 0) {
            // Check if Nostr is available as fallback
            if (MeshServiceHolder.isNostrAvailable()) {
                Toast.makeText(this, "No Bluetooth peers, but voice messages can be sent via Internet relay", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "No peers connected. Please wait for a connection before recording.", Toast.LENGTH_LONG).show()
                return
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            showVoiceRecordingDialog()
        } else {
            requestRecordPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun showVoiceRecordingDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_voice_recording, null)
        val statusText = dialogView.findViewById<TextView>(R.id.statusText)
        val recordButton = dialogView.findViewById<ImageView>(R.id.recordButton)
        val cancelButton = dialogView.findViewById<TextView>(R.id.cancelButton)

        voiceStatusText = statusText

        recordButton.setOnClickListener {
            if (!voiceRecorder.isRecording) {
                startRecording(statusText, recordButton)
            } else {
                stopRecording(recordButton)
            }
        }

        cancelButton.setOnClickListener {
            if (voiceRecorder.isRecording) {
                stopRecording(recordButton, false)
            }
            voiceDialog?.dismiss()
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Voice Message")
            .setView(dialogView)
            .setNegativeButton("") { _, _ -> }
            .create()

        voiceDialog = dialog

        dialog.setOnDismissListener {
            if (voiceRecorder.isRecording) {
                voiceRecorder.cancelRecording()
            }
            voiceDialog = null
            voiceStatusText = null
        }

        dialog.show()
    }

    private fun startRecording(statusText: TextView, button: View) {
        val file = voiceRecorder.startRecording()
        if (file == null) {
            Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show()
            return
        }

        statusText.text = "🔴 Recording 00:00"
        button.setBackgroundResource(R.drawable.ic_stop)

        val startTime = System.currentTimeMillis()
        timerUpdateRunnable = object : Runnable {
            override fun run() {
                if (voiceRecorder.isRecording) {
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000
                    val seconds = elapsed % 60
                    val minutes = elapsed / 60
                    statusText.text = "🔴 Recording ${String.format(Locale.US, "%02d:%02d", minutes, seconds)}"
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.post(timerUpdateRunnable!!)
    }

    // =============== UPDATED stopRecording ===============
    private fun stopRecording(button: View, send: Boolean = true) {
        val (file, duration) = voiceRecorder.stopRecording()
        timerUpdateRunnable?.let { handler.removeCallbacks(it) }
        timerUpdateRunnable = null

        voiceStatusText?.text = "Tap 🎤 to start recording"
        button.setBackgroundResource(R.drawable.ic_mic)

        if (send && file != null && file.exists() && file.length() > 0) {
            // 1. Create the message object and add it to the UI immediately
            val msg = ChatMessage(
                text = "[Voice Message]",
                fromMe = true,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.SENT,
                messageId = null,
                type = 1,
                voiceDuration = duration,
                voiceFilePath = file.absolutePath
            )
            addMessage(msg)

            // ✅ Get sequence number NOW on main thread before coroutine
            val sequenceNumber = retryScheduler?.getNextSequence() ?: System.currentTimeMillis()

            // 2. Insert into database and get the row ID
            lifecycleScope.launch {
                try {
                    val entity = MessageEntity(
                        contactPubkey = contactPubkey,
                        text = "[Voice Message]",
                        fromMe = true,
                        timestamp = msg.timestamp,
                        status = msg.status.ordinal,
                        messageId = null,
                        type = 1,
                        voiceDuration = duration,
                        voiceFilePath = file.absolutePath
                    )
                    val dbMessageId = db.messageDao().insertAndGetId(entity)

                    // 3. Attempt to send the voice message, passing the DB ID and sequence number
                    val packetId = voiceSender.sendVoiceMessage(
                        file = file,
                        duration = duration,
                        recipientPeerId = contactPeerId,
                        recipientPubkey = contactPubkey.hexToBytes(),
                        dbMessageId = dbMessageId,
                        sequenceNumber = sequenceNumber
                    )

                    if (packetId != null) {
                        // Sent immediately – update the DB record with the packetId
                        db.messageDao().updatePacketId(dbMessageId, packetId)
                        // Also update the local message's messageId so that ACKs can be tracked
                        val lastIndex = messages.size - 1
                        if (lastIndex >= 0 && messages[lastIndex].timestamp == msg.timestamp) {
                            messages[lastIndex] = messages[lastIndex].copy(messageId = packetId)
                            messageAdapter.notifyItemChanged(lastIndex)
                            pendingMessages[packetId] = lastIndex
                        }
                        Toast.makeText(this@ChatActivity, "Voice message sent", Toast.LENGTH_SHORT).show()
                    } else {
                        // Sending failed – it has been queued via the outbox (thanks to dbMessageId and sequenceNumber)
                        Toast.makeText(this@ChatActivity, "Voice message queued for later", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Failed to process voice message", e)
                    Toast.makeText(this@ChatActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    voiceDialog?.dismiss()
                }
            }
        } else {
            if (file != null && file.exists()) file.delete()
            Toast.makeText(this, if (file != null && file.exists()) "Recording cancelled" else "Recording empty, not sent", Toast.LENGTH_SHORT).show()
        }
    }
    // =============== End of updated stopRecording ===============

    // ----- UI Helpers -----
    private fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        messageAdapter.notifyItemInserted(messages.size - 1)
        recyclerView.scrollToPosition(messages.size - 1)
    }

    private fun deleteSelectedMessages() {
        val positions = messageAdapter.getSelectedPositions().sortedDescending()
        val idsToDelete = positions.map { messages[it].id }

        positions.forEach { pos ->
            messages.removeAt(pos)
            messageAdapter.notifyItemRemoved(pos)
        }

        lifecycleScope.launch {
            idsToDelete.forEach { id ->
                db.messageDao().delete(id)
            }
        }

        selectedPositions.clear()
        multiSelectMode = false
        actionMode?.finish()
    }

    // ----- Playback -----
    private fun playAudio(filePath: String) {
        voicePlayer.play(filePath)
    }

    // ----- MessageAdapter with two view types -----
    inner class MessageAdapter :
        RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

        private val viewTypeIncoming = 0
        private val viewTypeOutgoing = 1

        private val selectedPositions = mutableSetOf<Int>()

        fun toggleSelection(position: Int) {
            if (selectedPositions.contains(position)) selectedPositions.remove(position)
            else selectedPositions.add(position)
            notifyItemChanged(position)
        }

        fun getSelectedPositions(): Set<Int> = selectedPositions

        @Suppress("NotifyDataSetChanged")
        fun clearSelection() {
            selectedPositions.clear()
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int {
            return if (messages[position].fromMe) viewTypeOutgoing else viewTypeIncoming
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
            val layout = if (viewType == viewTypeOutgoing) {
                R.layout.item_message_outgoing
            } else {
                R.layout.item_message_incoming
            }
            val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            return MessageViewHolder(view)
        }

        override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
            val msg = messages[position]

            // ✅ Use SpannableString to color the status ticks
            val statusSymbol = when (msg.status) {
                MessageStatus.SENT -> " ✓"
                MessageStatus.DELIVERED -> " ✓✓"
                MessageStatus.READ -> " ✓✓"
            }

            val statusColor = when (msg.status) {
                MessageStatus.SENT -> Color.GRAY
                MessageStatus.DELIVERED -> Color.BLUE
                MessageStatus.READ -> Color.GREEN
            }

            val baseText = if (msg.type == 1) {
                "🎵 Voice Message (${msg.voiceDuration}s)"
            } else {
                msg.text
            }

            val fullText = "$baseText$statusSymbol"
            val spannable = android.text.SpannableString(fullText)

            val start = fullText.length - statusSymbol.length
            val end = fullText.length
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(statusColor),
                start, end,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            holder.messageText.text = spannable

            // Selection highlight
            val isSelected = selectedPositions.contains(position)
            holder.itemView.isSelected = isSelected
            holder.itemView.setBackgroundColor(
                if (isSelected) Color.argb(80, 255, 165, 0) else Color.TRANSPARENT
            )

            // Click listeners
            holder.itemView.setOnClickListener {
                if (multiSelectMode) {
                    toggleSelection(position)
                    actionMode?.title = "${selectedPositions.size} selected"
                } else {
                    if (msg.type == 1) playAudio(msg.voiceFilePath)
                }
            }

            holder.itemView.setOnLongClickListener {
                if (!multiSelectMode) {
                    multiSelectMode = true
                    toggleSelection(position)
                    actionMode = startSupportActionMode(actionModeCallback)
                    actionMode?.title = "1 selected"
                } else {
                    toggleSelection(position)
                    actionMode?.title = "${selectedPositions.size} selected"
                }
                true
            }
        }

        override fun getItemCount(): Int = messages.size

        inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val messageText: TextView = itemView.findViewById(R.id.messageText)
        }
    }

    // ----- Data classes -----
    data class ChatMessage(
        val id: Long = 0,
        val text: String,
        val fromMe: Boolean,
        val timestamp: Long,
        val status: MessageStatus = MessageStatus.SENT,
        val messageId: String? = null,
        val type: Int = 0,
        val voiceDuration: Long = 0,
        val voiceFilePath: String = ""
    )

    enum class MessageStatus {
        SENT, DELIVERED, READ
    }
}