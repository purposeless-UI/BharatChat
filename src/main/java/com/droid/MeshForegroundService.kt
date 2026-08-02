package com.droid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.droid.ble.peerIdFromPubkey
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

@Suppress("SpellCheckingInspection")
class MeshForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "bharatchat_mesh"
        private const val NOTIFICATION_ID = 1001
        private const val MESSAGE_CHANNEL_ID = "bharatchat_messages"
        private const val TAG = "MeshForegroundService"
        private const val STATUS_DELIVERED = 1
    }

    private var meshStarted = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Bluetooth state receiver
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (BluetoothAdapter.ACTION_STATE_CHANGED == action) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        Log.d(TAG, "Bluetooth turned ON – starting mesh")
                        startMesh()
                        updateNotification("Relaying mesh messages over Bluetooth")
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        Log.d(TAG, "Bluetooth turned OFF – stopping mesh")
                        stopMesh()
                        updateNotification("Bluetooth is off – mesh paused")
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannels()
            val notification = buildNotification("Initialising mesh...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "Foreground service started")

            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            registerReceiver(bluetoothReceiver, filter)

            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter
            if (adapter == null) {
                Log.e(TAG, "Bluetooth adapter is null – cannot run mesh")
                updateNotification("Bluetooth adapter not available")
                return
            }

            if (!adapter.isEnabled) {
                Log.w(TAG, "Bluetooth is off – waiting for it to turn on")
                updateNotification("Bluetooth is off – mesh paused")
                return
            }

            startMesh()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mesh service", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: stopping mesh service")
        serviceScope.cancel()
        try { unregisterReceiver(bluetoothReceiver) } catch (_: Exception) {}
        stopMesh()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ============================================================
    //  Mesh control
    // ============================================================

    private fun startMesh() {
        if (meshStarted) {
            Log.d(TAG, "Mesh already started")
            return
        }

        serviceScope.launch {
            try {
                val identity = IdentityStore.loadOrCreate(this@MeshForegroundService)
                val compressedKey = identity.compressedPublicKeyHex
                Log.d(TAG, "Using public key: ${compressedKey.take(12)}...")

                val peerId = withContext(Dispatchers.IO) {
                    peerIdFromPubkey(compressedKey)
                }

                withContext(Dispatchers.Main) {
                    MeshServiceHolder.start(this@MeshForegroundService, identity.secretKey, peerId)
                    meshStarted = true
                    Log.d(TAG, "MeshServiceHolder started successfully")
                    updateNotification("Relaying mesh messages over Bluetooth")

                    // ✅ Persistent listener: detect voice, save to DB, show notification
                    MeshServiceHolder.setPersistentMessageListener { fromPeerId, plaintext ->
                        serviceScope.launch {
                            try {
                                val trimmed = plaintext.trim()  // remove accidental whitespace
                                val contacts = withContext(Dispatchers.IO) {
                                    ContactsStore.list(this@MeshForegroundService)
                                }
                                val contact = contacts.firstOrNull {
                                    it.xOnlyPubkeyHex.take(16).equals(fromPeerId, ignoreCase = true)
                                }
                                if (contact != null) {
                                    val db = AppDatabase.getInstance(this@MeshForegroundService)

                                    var voiceFilePath = ""
                                    var voiceDuration = 0L
                                    var isVoice = false
                                    var displayText = trimmed

                                    // Try to parse JSON to detect voice message
                                    try {
                                        val json = JSONObject(trimmed)
                                        val type = json.optString("type", "text")
                                        if (type == "voice") {
                                            isVoice = true
                                            val audioBase64 = json.getString("audio")
                                            voiceDuration = json.optInt("duration", 0).toLong()
                                            val audioBytes = Base64.decode(audioBase64, Base64.NO_WRAP)
                                            val file = File(cacheDir, "voice_${System.currentTimeMillis()}.3gp")
                                            file.outputStream().use { it.write(audioBytes) }
                                            voiceFilePath = file.absolutePath
                                            displayText = "[Voice Message]"
                                            Log.d(TAG, "Voice file saved for ${contact.name} at $voiceFilePath, size=${audioBytes.size}")
                                        }
                                    } catch (_: Exception) {
                                        // Not JSON – keep as text
                                    }

                                    db.messageDao().insert(
                                        MessageEntity(
                                            contactPubkey = contact.pubkeyHex,
                                            text = displayText,
                                            fromMe = false,
                                            timestamp = System.currentTimeMillis(),
                                            status = STATUS_DELIVERED,
                                            messageId = "",
                                            type = if (isVoice) 1 else 0,
                                            voiceDuration = voiceDuration,
                                            voiceFilePath = voiceFilePath
                                        )
                                    )
                                    Log.d(TAG, "Persistent message saved for ${contact.name} (type=${if (isVoice) "voice" else "text"})")

                                    // Show notification (silent if already in this chat)
                                    showMessageNotification(fromPeerId, contact, displayText)
                                } else {
                                    Log.w(TAG, "No contact found for peerId $fromPeerId – message not saved")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to save persistent message", e)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start mesh", e)
                updateNotification("Mesh error – check logs")
            }
        }
    }

    private fun stopMesh() {
        if (!meshStarted) return
        MeshServiceHolder.stop()
        meshStarted = false
        Log.d(TAG, "Mesh stopped")
    }

    // ============================================================
    //  Notification helpers
    // ============================================================

    private fun createNotificationChannels() {
        // Service channel (low importance – silent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "BharatChat mesh",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Bluetooth mesh relay running"
                setSound(null, null)        // silent
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(serviceChannel)
        }

        // Message notification channel (HIGH importance – sound, vibration, heads-up)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val messageChannel = NotificationChannel(
                MESSAGE_CHANNEL_ID,
                "New messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for new messages"
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), null)
                enableVibration(true)
                enableLights(true)
                lightColor = Color.rgb(255, 165, 0)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(messageChannel)
            Log.d(TAG, "Message notification channel created")
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        val iconRes = android.R.drawable.ic_menu_compass
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BharatChat is running")
            .setContentText(contentText)
            .setSmallIcon(iconRes)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = buildNotification(contentText)
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, notification)
    }

    // ============================================================
    //  Show incoming message notification
    // ============================================================

    private suspend fun showMessageNotification(fromPeerId: String, contact: Contact, messageText: String) {
        // Skip if the user is already in this chat
        if (fromPeerId == ChatActivityState.currentContactPeerId) {
            Log.d(TAG, "User is already in chat with ${contact.name}, skipping notification")
            return
        }

        // Build intent to open ChatActivity
        val intent = Intent(applicationContext, ChatActivity::class.java).apply {
            putExtra("contactPubkey", contact.pubkeyHex)
            putExtra("contactName", contact.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            contact.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Truncate message if too long
        val displayText = if (messageText.length > 60) messageText.take(60) + "…" else messageText

        val notification = NotificationCompat.Builder(applicationContext, MESSAGE_CHANNEL_ID)
            .setContentTitle("${contact.name} sent a message")
            .setContentText(displayText)
            .setSmallIcon(android.R.drawable.ic_menu_compass) // replace with your own icon later
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(contact.hashCode(), notification)
    }
}