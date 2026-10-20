package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.NetworkManager
import com.example.domain.model.CallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground Service that keeps the Wi-Fi stack, multicast locks, and sockets active.
 * Displays persistent notification and handles fullscreen incoming call alerts.
 */
class LocalChatService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var networkManager: NetworkManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate() {
        super.onCreate()
        networkManager = NetworkManager.getInstance(this)
        createNotificationChannel()

        val notification = buildForegroundNotification()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val callActive = networkManager.callState.value == CallState.ACTIVE
                    if (callActive && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    }
                }
                try {
                    startForeground(1, notification, type)
                } catch (e: Exception) {
                    Log.e("LocalChatService", "Failed to start foreground with mic", e)
                    startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                }
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.e("LocalChatService", "Failed to start foreground", e)
        }

        acquireSystemLocks()
        observeCallState()
    }

    private fun acquireSystemLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalChatService::WakeLock").apply {
                // VULN-14 FIX: Explicit 10-minute timeout to prevent permanent battery drain
                acquire(10 * 60 * 1000L)
            }

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "LocalChatService::WifiLock"
            ).apply { acquire() }

            multicastLock = wifiManager.createMulticastLock("LocalChatService::MulticastLock").apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            Log.e("LocalChatService", "Failed to acquire locks", e)
        }
    }

    private fun observeCallState() {
        serviceScope.launch {
            networkManager.callState.collectLatest { state ->
                if (state == CallState.INCOMING) {
                    showIncomingCallNotification()
                } else if (state == CallState.IDLE || state == CallState.ACTIVE) {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.cancel(2)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    if (state == CallState.ACTIVE && ContextCompat.checkSelfPermission(this@LocalChatService, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    }
                    try {
                        startForeground(1, buildForegroundNotification(), type)
                    } catch (e: Exception) {
                        Log.e("LocalChatService", "Failed to update foreground service type", e)
                        startForeground(1, buildForegroundNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = buildForegroundNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (networkManager.callState.value == CallState.ACTIVE && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    }
                }
                try {
                    startForeground(1, notification, type)
                } catch (e: Exception) {
                    Log.e("LocalChatService", "Failed to start foreground with mic", e)
                    startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                }
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.e("LocalChatService", "Failed to update foreground", e)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            // VULN-31 FIX: Persistent service notification uses IMPORTANCE_LOW (silent/non-intrusive)
            val serviceChannel = NotificationChannel(
                "local_chat_channel",
                "Local Chat Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows ongoing walkie-talkie background status"
            }

            // High-priority channel specifically for incoming ringing calls
            val callChannel = NotificationChannel(
                "local_chat_call_channel",
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Ringing alerts for incoming peer voice calls"
                enableVibration(true)
            }

            nm.createNotificationChannel(serviceChannel)
            nm.createNotificationChannel(callChannel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, "local_chat_channel")
            .setContentTitle("Walkie-Talkie Active")
            .setContentText("Ready for incoming messages/calls")
            .setSmallIcon(android.R.drawable.stat_sys_speakerphone)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showIncomingCallNotification() {
        val fullscreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            fullscreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Incoming call alerts use the HIGH-importance call channel
        val notification = NotificationCompat.Builder(this, "local_chat_call_channel")
            .setContentTitle("LocalChat Call")
            .setContentText("Incoming call from ${networkManager.connectedPeer.value?.name ?: "Peer"}")
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(2, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (e: Exception) {
            Log.e("LocalChatService", "Failed to release locks", e)
        }
    }
}
