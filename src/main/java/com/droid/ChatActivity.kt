package com.droid

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
import com.droid.ble.BlePacket
import com.droid.ble.peerIdFromPubkey
import com.droid.crypto.Secp256k1Signer
import com.droid.crypto.hexToBytes
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Suppress(
    "SetTextI18n",
    "UseSetterMethod",
    "RedundantQualifierName",
    "NotifyDataSetChanged",
    "SpellCheckingInspection",
    "DEPRECATION"
)
class ChatActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var inputEditText: EditText
    private lateinit var statusTextView: TextView

    private lateinit var contactPubkey: String
    private lateinit var contactName: String
    private lateinit var contactPeerId: String
    private lateinit var myIdentity: Identity
    private lateinit var db: AppDatabase

    private val listenerKey = "chat-" + System.currentTimeMillis()
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
    private val pendingMessages = mutableMapOf<String, Int>()

    // ----- Multi-Select State -----
    private var multiSelectMode = false
    private val selectedPositions = mutableSetOf<Int>()
    private var actionMode: ActionMode? = null

    // ----- Voice Recording -----
    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var isRecording = false
    private var recordingStartTime: Long = 0
    private var timerUpdateRunnable: Runnable? = null

    // ----- Voice Playback -----
    private var mediaPlayer: MediaPlayer? = null

    // ----- Voice dialog reference -----
    private var voiceDialog: AlertDialog? = null

    // Store the status text view so we can reset it
    private var voiceStatusText: TextView? = null

    // ----- Permission launcher for microphone -----
    private val requestRecordPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showVoiceRecordingDialog()
        } else {
            Toast.makeText(this, "Microphone permission is required to send voice messages.", Toast.LENGTH_LONG).show()
        }
    }

    // ----- ActionMode Callback for multi-select (AppCompat) -----
    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.chat_multi_select, menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

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

            ChatActivityState.currentContactPeerId = contactPeerId

            myIdentity = IdentityStore.loadOrCreate(this)
            db = AppDatabase.getInstance(this)

            title = contactName
            setupUI()

            loadMessagesFromDb()

            MeshServiceHolder.addMessageListener(listenerKey) { fromPeerId, plaintext ->
                if (fromPeerId == contactPeerId) {
                    runOnUiThread {
                        val trimmed = plaintext.trim()
                        Log.d(tag, "📩 Incoming message (raw): $trimmed")
                        try {
                            val json = JSONObject(trimmed)
                            val type = json.optString("type", "text")
                            Log.d(tag, "📩 Message type: $type")
                            if (type == "voice") {
                                val audioBase64 = json.getString("audio")
                                val duration = json.optInt("duration", 0)
                                Log.d(tag, "📩 Voice duration: $duration, base64 length: ${audioBase64.length}")

                                val audioBytes = Base64.decode(audioBase64, Base64.NO_WRAP)
                                val file = saveAudioFile(audioBytes)

                                val msg = ChatMessage(
                                    text = "[Voice Message]",
                                    fromMe = false,
                                    timestamp = System.currentTimeMillis(),
                                    status = MessageStatus.DELIVERED,
                                    messageId = "",
                                    type = 1,
                                    voiceDuration = duration.toLong(),
                                    voiceFilePath = file.absolutePath
                                )
                                addMessage(msg)
                                lifecycleScope.launch {
                                    db.messageDao().insert(
                                        MessageEntity(
                                            contactPubkey = contactPubkey,
                                            text = msg.text,
                                            fromMe = false,
                                            timestamp = msg.timestamp,
                                            status = msg.status.ordinal,
                                            messageId = msg.messageId,
                                            type = msg.type,
                                            voiceDuration = msg.voiceDuration,
                                            voiceFilePath = msg.voiceFilePath
                                        )
                                    )
                                }
                            } else {
                                val msg = ChatMessage(
                                    text = trimmed,
                                    fromMe = false,
                                    timestamp = System.currentTimeMillis(),
                                    status = MessageStatus.DELIVERED
                                )
                                addMessage(msg)
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "❌ JSON parse error: ${e.message}", e)
                            Log.e(tag, "Failed JSON string: $trimmed")
                            val msg = ChatMessage(
                                text = trimmed,
                                fromMe = false,
                                timestamp = System.currentTimeMillis(),
                                status = MessageStatus.DELIVERED
                            )
                            addMessage(msg)
                        }
                    }
                }
            }

            MeshServiceHolder.addAckListener(listenerKey) { packetId, ackType ->
                Log.d(tag, "🔄 ACK received: packetId=$packetId, ackType=$ackType")
                Log.d(tag, "🔍 pendingMessages keys: ${pendingMessages.keys}")
                runOnUiThread {
                    val index = pendingMessages[packetId]
                    Log.d(tag, "🔍 index for $packetId = $index")
                    if (index != null && index < messages.size) {
                        val msg = messages[index]
                        val newStatus = when (ackType) {
                            BleConstants.TYPE_DELIVERY_ACK -> MessageStatus.DELIVERED
                            BleConstants.TYPE_READ_ACK -> MessageStatus.READ
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

            Log.d(tag, "ChatActivity initialized successfully")
        } catch (e: Exception) {
            Log.e(tag, "onCreate error", e)
            Toast.makeText(this, "Error opening chat: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

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
                            messageId = entity.messageId,
                            type = entity.type,
                            voiceDuration = entity.voiceDuration,
                            voiceFilePath = entity.voiceFilePath
                        )
                        messages.add(msg)
                        if (entity.fromMe && entity.messageId.isNotBlank()) {
                            pendingMessages[entity.messageId] = messages.size - 1
                        }
                    }
                    @Suppress("NotifyDataSetChanged")
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
        mediaRecorder?.release()
        mediaRecorder = null
        mediaPlayer?.release()
        mediaPlayer = null
        timerUpdateRunnable?.let { handler.removeCallbacks(it) }
        timerUpdateRunnable = null
        voiceDialog = null
        voiceStatusText = null
    }

    private fun setupUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.DKGRAY)
        }

        statusTextView = TextView(this).apply {
            setPadding(16, 12, 16, 12)
            textSize = 12f
            text = "Checking connection…"
            setTextColor(Color.rgb(255, 165, 0))
        }
        root.addView(statusTextView)

        recyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            layoutManager = LinearLayoutManager(this@ChatActivity).apply {
                stackFromEnd = true
            }
            setBackgroundColor(Color.DKGRAY)
        }
        messageAdapter = MessageAdapter(messages, contactName, this::showDeleteDialog)
        recyclerView.adapter = messageAdapter
        root.addView(recyclerView)

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.DKGRAY)
        }
        inputEditText = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            hint = "Message $contactName"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundResource(android.R.color.transparent)
        }
        inputRow.addView(inputEditText)

        val micButton = Button(this).apply {
            text = "🎤"
            textSize = 24f
            setTextColor(Color.rgb(255, 165, 0))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { checkMicrophonePermissionAndRecord() }
        }
        inputRow.addView(micButton)

        val sendButton = Button(this).apply {
            text = "Send"
            setTextColor(Color.rgb(255, 165, 0))
            setBackgroundColor(Color.DKGRAY)
            val drawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.DKGRAY)
                setStroke(2, Color.rgb(255, 165, 0))
                setCornerRadius(8f)
            }
            background = drawable
            setOnClickListener { sendMessage() }
        }
        inputRow.addView(sendButton)
        root.addView(inputRow)

        setContentView(root)
    }

    private fun updateConnectionStatus() {
        val directlyConnected = MeshServiceHolder.current()?.isDirectlyConnectedToPeer(contactPeerId) ?: false
        statusTextView.text = if (directlyConnected) {
            "● Connected directly to $contactName via Bluetooth"
        } else {
            "○ $contactName not directly in range — messages relayed through mesh"
        }
    }

    private fun sendMessage() {
        val text = inputEditText.text.toString().trim()
        if (text.isEmpty()) return
        inputEditText.text.clear()

        if (contactPubkey.length != 66 || !contactPubkey.matches(Regex("^[a-fA-F0-9]{66}$"))) {
            Toast.makeText(this, "Invalid contact public key", Toast.LENGTH_SHORT).show()
            Log.e(tag, "Invalid contactPubkey in sendMessage: $contactPubkey")
            return
        }

        val mesh = MeshServiceHolder.current()
        if (mesh == null) {
            Toast.makeText(this, "Mesh service is not available", Toast.LENGTH_SHORT).show()
            return
        }

        val messageId = BlePacket.newPacketId()
        val recipientBytes = contactPubkey.hexToBytes()
        val sent = mesh.sendMessage(contactPeerId, recipientBytes, text)

        if (sent) {
            val msg = ChatMessage(
                text = text,
                fromMe = true,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.SENT,
                messageId = messageId
            )
            addMessage(msg)
            pendingMessages[messageId] = messages.size - 1
            Log.d(tag, "📤 Outgoing message ID $messageId added to pending at index ${messages.size - 1}")
            lifecycleScope.launch {
                db.messageDao().insert(
                    MessageEntity(
                        contactPubkey = contactPubkey,
                        text = text,
                        fromMe = true,
                        timestamp = msg.timestamp,
                        status = msg.status.ordinal,
                        messageId = messageId
                    )
                )
            }
        } else {
            Toast.makeText(this, "No route to $contactName right now", Toast.LENGTH_SHORT).show()
        }
    }

    // ----- Voice Recording & Sending (Updated UI) -----
    private fun checkMicrophonePermissionAndRecord() {
        // Check connection before opening the dialog
        val mesh = MeshServiceHolder.current()
        if (mesh == null || mesh.connectedPeerCount() == 0) {
            Toast.makeText(this, "No peers connected. Please wait for a connection before recording.", Toast.LENGTH_LONG).show()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            showVoiceRecordingDialog()
        } else {
            requestRecordPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun showVoiceRecordingDialog() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            gravity = Gravity.CENTER
        }

        val statusText = TextView(this).apply {
            text = "Tap 🎤 to start recording"
            textSize = 18f
            setTextColor(Color.rgb(255, 165, 0))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        dialogView.addView(statusText)

        // store reference so we can reset it later
        voiceStatusText = statusText

        val recordButton = Button(this).apply {
            text = "🎤"
            textSize = 48f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.DKGRAY)
            val drawable = GradientDrawable().apply {
                setColor(Color.DKGRAY)
                setStroke(2, Color.rgb(255, 165, 0))
                setCornerRadius(100f)
            }
            background = drawable
            layoutParams = LinearLayout.LayoutParams(120, 120).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener {
                if (!isRecording) {
                    startRecording(statusText, this)
                } else {
                    stopRecording(this)
                }
            }
        }
        dialogView.addView(recordButton)

        val cancelButton = Button(this).apply {
            text = "Cancel"
            setTextColor(Color.GRAY)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                if (isRecording) {
                    stopRecording(recordButton, false)
                }
                voiceDialog?.dismiss()
            }
        }
        dialogView.addView(cancelButton)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Voice Message")
            .setView(dialogView)
            .setNegativeButton("") { _, _ -> }
            .create()

        voiceDialog = dialog

        dialog.setOnDismissListener {
            if (isRecording) {
                try {
                    mediaRecorder?.apply {
                        stop()
                        release()
                    }
                    mediaRecorder = null
                    isRecording = false
                    recordingFile?.delete()
                    recordingFile = null
                } catch (_: Exception) {}
                timerUpdateRunnable?.let { handler.removeCallbacks(it) }
                timerUpdateRunnable = null
            }
            voiceDialog = null
            voiceStatusText = null
        }

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.visibility = View.GONE
    }

    private fun startRecording(statusText: TextView, button: Button) {
        try {
            val file = File(cacheDir, "voice_${System.currentTimeMillis()}.3gp")
            recordingFile = file

            // Reduced audio quality → smaller file, faster send
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(8000)          // was 16000 → half the size
                setAudioEncodingBitRate(32000)      // was 64000 → half the size
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            statusText.text = "🔴 Recording 00:00"
            button.text = "⏹"
            button.setTextColor(Color.RED)
            button.setBackgroundColor(Color.DKGRAY)

            // Timer updates every second
            timerUpdateRunnable = object : Runnable {
                override fun run() {
                    if (isRecording) {
                        val elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000
                        val seconds = elapsed % 60
                        val minutes = elapsed / 60
                        statusText.text = "🔴 Recording ${String.format(Locale.US, "%02d:%02d", minutes, seconds)}"
                        handler.postDelayed(this, 1000)
                    }
                }
            }
            handler.post(timerUpdateRunnable!!)

        } catch (e: Exception) {
            Log.e(tag, "Recording start failed", e)
            Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording(button: Button, send: Boolean = true) {
        if (!isRecording) return

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false
            timerUpdateRunnable?.let { handler.removeCallbacks(it) }
            timerUpdateRunnable = null

            val file = recordingFile
            recordingFile = null

            val duration = if (file != null && file.exists() && file.length() > 0) {
                (System.currentTimeMillis() - recordingStartTime) / 1000
            } else 0L

            // Reset the timer text
            voiceStatusText?.text = "Tap 🎤 to start recording"

            if (send && file != null && file.exists() && file.length() > 0) {
                // Try to send, retry once if it fails
                var success = sendVoiceMessage(file, duration)
                if (!success) {
                    // Retry after 1 second
                    Toast.makeText(this, "Retrying send...", Toast.LENGTH_SHORT).show()
                    handler.postDelayed({
                        val retrySuccess = sendVoiceMessage(file, duration)
                        if (retrySuccess) {
                            Toast.makeText(this, "Voice message sent (retry)", Toast.LENGTH_SHORT).show()
                            voiceDialog?.dismiss()
                        } else {
                            Toast.makeText(this, "Still no connection. Please try again.", Toast.LENGTH_LONG).show()
                            button.text = "🎤"
                            button.setTextColor(Color.WHITE)
                            file.delete()
                        }
                    }, 1000)
                } else {
                    Toast.makeText(this, "Voice message sent", Toast.LENGTH_SHORT).show()
                    voiceDialog?.dismiss()
                }
            } else {
                if (file != null && file.exists()) {
                    file.delete()
                    Toast.makeText(this, "Recording cancelled", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Recording empty, not sent", Toast.LENGTH_SHORT).show()
                }
                button.text = "🎤"
                button.setTextColor(Color.WHITE)
            }
        } catch (e: Exception) {
            Log.e(tag, "Stop recording failed", e)
            Toast.makeText(this, "Error stopping recording", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendVoiceMessage(file: File, duration: Long): Boolean {
        try {
            val audioBytes = file.readBytes()
            val audioBase64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

            Log.d(tag, "📤 Voice message size: ${audioBytes.size} bytes, duration: ${duration}s")

            val payloadJson = JSONObject().apply {
                put("type", "voice")
                put("audio", audioBase64)
                put("duration", duration)
            }
            val payload = payloadJson.toString()

            val mesh = MeshServiceHolder.current()
            if (mesh == null) {
                Toast.makeText(this, "Mesh not available", Toast.LENGTH_SHORT).show()
                return false
            }
            // Check if there are any connected peers
            if (mesh.connectedPeerCount() == 0) {
                Toast.makeText(this, "No peers connected. Please try again later.", Toast.LENGTH_LONG).show()
                return false
            }

            val messageId = BlePacket.newPacketId()
            val recipientBytes = contactPubkey.hexToBytes()
            val sent = mesh.sendMessage(contactPeerId, recipientBytes, payload)

            if (sent) {
                val msg = ChatMessage(
                    text = "[Voice Message]",
                    fromMe = true,
                    timestamp = System.currentTimeMillis(),
                    status = MessageStatus.SENT,
                    messageId = messageId,
                    type = 1,
                    voiceDuration = duration,
                    voiceFilePath = file.absolutePath
                )
                addMessage(msg)
                pendingMessages[messageId] = messages.size - 1
                lifecycleScope.launch {
                    db.messageDao().insert(
                        MessageEntity(
                            contactPubkey = contactPubkey,
                            text = msg.text,
                            fromMe = true,
                            timestamp = msg.timestamp,
                            status = msg.status.ordinal,
                            messageId = messageId,
                            type = msg.type,
                            voiceDuration = msg.voiceDuration,
                            voiceFilePath = msg.voiceFilePath
                        )
                    )
                }
                return true
            } else {
                Toast.makeText(this, "Failed to send voice message", Toast.LENGTH_SHORT).show()
                return false
            }
        } catch (e: Exception) {
            Log.e(tag, "sendVoiceMessage failed", e)
            Toast.makeText(this, "Failed to send voice: ${e.message}", Toast.LENGTH_SHORT).show()
            return false
        }
    }

    // ----- Helper: Save received audio file -----
    private fun saveAudioFile(bytes: ByteArray): File {
        val file = File(cacheDir, "voice_${System.currentTimeMillis()}.3gp")
        file.outputStream().use { it.write(bytes) }
        Log.d(tag, "💾 Audio saved: ${file.absolutePath}, size=${file.length()}")
        return file
    }

    // ----- Play Voice Message -----
    private fun playAudio(filePath: String) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                prepare()
                start()
                setOnCompletionListener {
                    release()
                    mediaPlayer = null
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Play failed", e)
            Toast.makeText(this, "Failed to play voice", Toast.LENGTH_SHORT).show()
        }
    }

    // ----- Multi-Select Deletion -----
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

    // ----- UI Helpers -----
    private fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        messageAdapter.notifyItemInserted(messages.size - 1)
        recyclerView.scrollToPosition(messages.size - 1)
    }

    private fun showDeleteDialog(position: Int) {
        val msg = messages[position]
        AlertDialog.Builder(this)
            .setTitle("Delete message")
            .setMessage("Delete this message?")
            .setPositiveButton("Delete") { _, _ ->
                messages.removeAt(position)
                messageAdapter.notifyItemRemoved(position)
                lifecycleScope.launch {
                    db.messageDao().delete(msg.id)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    data class ChatMessage(
        val id: Long = 0,
        val text: String,
        val fromMe: Boolean,
        val timestamp: Long,
        val status: MessageStatus = MessageStatus.SENT,
        val messageId: String = "",
        val type: Int = 0,
        val voiceDuration: Long = 0,
        val voiceFilePath: String = ""
    )

    enum class MessageStatus {
        SENT, DELIVERED, READ
    }

    inner class MessageAdapter(
        private val items: List<ChatMessage>,
        private val contactName: String,
        @Suppress("unused") private val onDelete: (Int) -> Unit
    ) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

        private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val selectedPositions = mutableSetOf<Int>()

        fun toggleSelection(position: Int) {
            if (selectedPositions.contains(position)) {
                selectedPositions.remove(position)
            } else {
                selectedPositions.add(position)
            }
            notifyItemChanged(position)
        }

        fun getSelectedPositions(): Set<Int> = selectedPositions

        fun clearSelection() {
            selectedPositions.clear()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
            val container = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                setPadding(4, 4, 4, 4)
            }
            val textView = TextView(parent.context).apply {
                setPadding(24, 16, 24, 16)
                textSize = 16f
                setTextColor(Color.rgb(255, 165, 0))
                val drawable = GradientDrawable().apply {
                    setColor(Color.DKGRAY)
                    setStroke(2, Color.rgb(255, 165, 0))
                    setCornerRadius(8f)
                }
                background = drawable

                // Limit width to 80% of screen
                val displayMetrics = parent.context.resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val maxWidth = (screenWidth * 0.8).toInt()
                this.maxWidth = maxWidth

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                isLongClickable = true
            }
            container.addView(textView)
            return MessageViewHolder(container, textView)
        }

        override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
            val msg = items[position]
            val time = dateFormat.format(Date(msg.timestamp))

            val prefix = if (msg.fromMe) "Me" else contactName
            val statusIcon = when (msg.status) {
                MessageStatus.SENT -> " ✓"
                MessageStatus.DELIVERED -> " ✓✓"
                MessageStatus.READ -> " ✓✓"
            }

            val displayText = if (msg.type == 1) {
                "🎵 $prefix ($time): Voice Message (${msg.voiceDuration}s)$statusIcon"
            } else {
                "$prefix ($time): ${msg.text}$statusIcon"
            }

            holder.textView.text = displayText

            when {
                msg.fromMe && msg.status == MessageStatus.READ -> {
                    holder.textView.setTextColor(Color.rgb(255, 165, 0))
                }
                msg.fromMe && msg.status == MessageStatus.DELIVERED -> {
                    holder.textView.setTextColor(Color.rgb(255, 165, 0))
                }
                msg.fromMe && msg.status == MessageStatus.SENT -> {
                    holder.textView.setTextColor(Color.WHITE)
                }
                else -> {
                    holder.textView.setTextColor(Color.rgb(255, 165, 0))
                }
            }

            holder.container.gravity = if (msg.fromMe) Gravity.END else Gravity.START

            val isSelected = selectedPositions.contains(position)
            holder.container.setBackgroundColor(
                if (isSelected) Color.argb(80, 255, 165, 0) else Color.TRANSPARENT
            )

            holder.container.setOnClickListener {
                if (multiSelectMode) {
                    toggleSelection(position)
                    actionMode?.title = "${selectedPositions.size} selected"
                } else {
                    if (msg.type == 1) {
                        playAudio(msg.voiceFilePath)
                    }
                }
            }

            holder.textView.setOnLongClickListener {
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

        override fun getItemCount(): Int = items.size

        inner class MessageViewHolder(
            val container: LinearLayout,
            val textView: TextView
        ) : RecyclerView.ViewHolder(container)
    }
}