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
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.droid.ble.peerIdFromPubkey
import com.droid.voice.MessageHandler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@Suppress("SpellCheckingInspection", "UnnecessaryVariable", "RedundantQualifierName")
class MeshForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "bharatchat_mesh"
        private const val NOTIFICATION_ID = 1001
        private const val MESSAGE_CHANNEL_ID = "bharatchat_messages"
        private const val TAG = "MeshForegroundService"
    }

    private var meshStarted = false
    private var nostrOnlyStarted = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var db: AppDatabase

    // Bluetooth state receiver
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (BluetoothAdapter.ACTION_STATE_CHANGED == action) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        Log.d(TAG, "Bluetooth turned ON – starting full mesh")
                        if (nostrOnlyStarted) {
                            stopNostrOnly()
                        }
                        startFullMesh()
                        updateNotification("Relaying mesh messages over Bluetooth + Internet")
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        Log.d(TAG, "Bluetooth turned OFF – switching to Nostr-only mode")
                        if (meshStarted) {
                            MeshServiceHolder.stopBluetoothOnly()
                            meshStarted = false
                            nostrOnlyStarted = true
                            updateNotification("🌐 Connected via Internet relay (Bluetooth OFF)")
                        } else if (!nostrOnlyStarted) {
                            startNostrOnly()
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            db = AppDatabase.getInstance(this)

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

            // Register Bluetooth receiver
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            registerReceiver(bluetoothReceiver, filter)

            val bluetoothManager = getSystemService(BluetoothManager::class.java)
            val adapter = bluetoothManager?.adapter
            if (adapter == null) {
                Log.e(TAG, "Bluetooth adapter is null – starting Nostr-only mode")
                startNostrOnly()
                return
            }

            if (!adapter.isEnabled) {
                Log.w(TAG, "Bluetooth is off – starting Nostr-only mode")
                startNostrOnly()
                return
            }

            startFullMesh()

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
        stopAll()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ============================================================
    //  Mesh control
    // ============================================================

    private fun startFullMesh() {
        if (meshStarted) {
            Log.d(TAG, "Full mesh already started")
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
                    nostrOnlyStarted = false
                    Log.d(TAG, "Full mesh started successfully")
                    updateNotification("Relaying mesh messages over Bluetooth + Internet")

                    registerContacts()
                    setupPersistentListener()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start full mesh", e)
                updateNotification("Mesh error – check logs")
            }
        }
    }

    private fun startNostrOnly() {
        if (nostrOnlyStarted) {
            Log.d(TAG, "Nostr-only already started")
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
                    MeshServiceHolder.startNostrOnly(
                        this@MeshForegroundService,
                        identity.secretKey,
                        peerId
                    )
                    nostrOnlyStarted = true
                    meshStarted = false
                    Log.d(TAG, "Nostr-only mode started successfully")
                    updateNotification("🌐 Connected via Internet relay (Bluetooth OFF)")

                    registerContacts()
                    setupPersistentListener()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Nostr-only mode", e)
                updateNotification("Nostr error – check logs")
            }
        }
    }

    private fun stopNostrOnly() {
        if (!nostrOnlyStarted) return
        MeshServiceHolder.stop()
        nostrOnlyStarted = false
        Log.d(TAG, "Nostr-only mode stopped")
    }

    private fun stopAll() {
        if (!meshStarted && !nostrOnlyStarted) return
        MeshServiceHolder.stop()
        meshStarted = false
        nostrOnlyStarted = false
        Log.d(TAG, "All services stopped")
    }

    private fun registerContacts() {
        serviceScope.launch {
            try {
                val contacts = withContext(Dispatchers.IO) {
                    db.contactDao().getAllContacts().first()
                }
                for (contact in contacts) {
                    val cPeerId = peerIdFromPubkey(contact.pubkey)
                    MeshServiceHolder.registerPeer(cPeerId, contact.pubkey)
                }
                Log.d(TAG, "Registered ${contacts.size} existing contacts with MeshServiceHolder")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register contacts", e)
            }
        }
    }

    /**
     * Sets up the persistent message listener that processes incoming messages from both
     * Bluetooth and Nostr. The packet/event ID is passed through for deduplication.
     */
    private fun setupPersistentListener() {
        MeshServiceHolder.setPersistentMessageListener { fromPeerId, packetId, plaintext ->
            serviceScope.launch {
                try {
                    Log.d(TAG, "Persistent listener received message from $fromPeerId (packetId=$packetId)")

                    val contactsList = withContext(Dispatchers.IO) {
                        db.contactDao().getAllContacts().first()
                    }
                    val contact = contactsList.firstOrNull { contact ->
                        contact.xOnlyPubkeyHex.take(16).equals(fromPeerId, ignoreCase = true)
                    }
                    if (contact != null) {
                        MessageHandler.processIncomingMessage(
                            context = applicationContext,
                            fromPeerId = fromPeerId,
                            packetId = packetId,
                            plaintext = plaintext,
                            contact = contact,
                            onMessageInserted = { entity ->
                                val displayText = if (entity.type == 1) "[Voice Message]" else entity.text
                                showMessageNotification(fromPeerId, contact, displayText)
                            }
                        )
                    } else {
                        Log.w(TAG, "No contact found for peerId $fromPeerId – message not saved")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save persistent message", e)
                }
            }
        }
    }

    // ============================================================
    //  Notification helpers
    // ============================================================

    private fun createNotificationChannels() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "BharatChat mesh",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps mesh relay running"
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(serviceChannel)

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
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    private fun showMessageNotification(fromPeerId: String, contact: Contact, messageText: String) {
        if (fromPeerId == ChatActivityState.currentContactPeerId) {
            Log.d(TAG, "User is already in chat with ${contact.name}, skipping notification")
            return
        }

        val intent = Intent(applicationContext, ChatActivity::class.java).apply {
            putExtra("contactPubkey", contact.pubkey)
            putExtra("contactName", contact.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            contact.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val displayText = if (messageText.length > 60) messageText.take(60) + "…" else messageText

        val notification = NotificationCompat.Builder(applicationContext, MESSAGE_CHANNEL_ID)
            .setContentTitle("${contact.name} sent a message")
            .setContentText(displayText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        getSystemService(NotificationManager::class.java)?.notify(contact.hashCode(), notification)
    }
}