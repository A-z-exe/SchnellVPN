package com.schnellvpn.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class SchnellVpnService : VpnService(), CoreCallbackHandler {

    companion object {
        const val ACTION_CONNECT = "com.schnellvpn.app.CONNECT"
        const val ACTION_DISCONNECT = "com.schnellvpn.app.DISCONNECT"
        const val EXTRA_LINK = "extra_link"
        
        private const val TAG = "SchnellVPN"
        private const val CHANNEL_ID = "schnellvpn_service"
        private const val NOTIF_ID = 1
        private const val SOCKS_PORT = 10808
        private const val TUN_IPV4 = "10.0.0.2"
        private const val TUN_IPV6 = "fd00::2"
        private const val TUN_MTU = 1500
        private val DNS_SERVERS = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")
        private const val STARTUP_DELAY_MS = 1500L
        private const val STATS_INTERVAL_MS = 1000L
        private const val RECONNECT_DELAY_MS = 3000L
    }

    private var tunPfd: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null
    private var statsJob: Job? = null
    private var reconnectJob: Job? = null
    private var isConnected = AtomicBoolean(false)
    private var isStarting = AtomicBoolean(false)
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val link = intent.getStringExtra(EXTRA_LINK)
                if (!link.isNullOrEmpty()) {
                    startVpn(link)
                } else {
                    Log.e(TAG, "Link is empty")
                    stopSelf()
                }
            }
            ACTION_DISCONNECT -> stopVpn()
            else -> Log.w(TAG, "Unknown action: ${intent?.action}")
        }
        return START_STICKY
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN revoked by system")
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroying")
        stopVpn()
        serviceScope.cancel()
        mainScope.cancel()
        super.onDestroy()
    }

    private fun startVpn(link: String) {
        if (isStarting.getAndSet(true)) {
            Log.w(TAG, "VPN is already starting")
            return
        }

        Log.d(TAG, "========== STARTING VPN ==========")
        Log.d(TAG, "Link: ${link.take(50)}...")

        startForeground(NOTIF_ID, buildNotification("در حال اتصال...", true))
        VpnStatus.reset()

        serviceScope.launch {
            try {
                val config = withContext(Dispatchers.IO) {
                    XrayConfigBuilder.buildConfig(link, SOCKS_PORT)
                }
                Log.d(TAG, "✅ Config built (${config.length} chars)")

                withContext(Dispatchers.IO) {
                    try {
                        Libv2ray.initCoreEnv(filesDir.absolutePath, "")
                        Log.d(TAG, "✅ Xray-core environment initialized")
                    } catch (e: Exception) {
                        Log.e(TAG, "initCoreEnv error: ${e.message}")
                        throw e
                    }
                }

                val builder = Builder()
                    .setSession("SchnellVPN")
                    .setMtu(TUN_MTU)
                    .addAddress(TUN_IPV4, 32)
                    .addAddress(TUN_IPV6, 64)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .setBlocking(true)
                    .setUnderlyingNetworks(null)

                DNS_SERVERS.forEach { dns ->
                    builder.addDnsServer(dns)
                }

                tunPfd = withContext(Dispatchers.IO) {
                    try {
                        builder.establish()
                    } catch (e: SecurityException) {
                        Log.e(TAG, "VPN permission denied: ${e.message}")
                        throw IllegalStateException("لطفاً مجوز VPN را بدهید", e)
                    }
                }

                if (tunPfd == null) {
                    throw IllegalStateException("TUN establish failed - returned null")
                }

                val tunFd = tunPfd!!.fd
                Log.d(TAG, "✅ TUN interface created (fd=$tunFd)")

                coreController = CoreController(this@SchnellVpnService)
                val startResult = withContext(Dispatchers.IO) {
                    try {
                        coreController!!.startLoop(config, -1)
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "Xray start error: ${e.message}")
                        false
                    }
                }

                if (!startResult) {
                    throw IllegalStateException("Xray-core failed to start")
                }
                Log.d(TAG, "✅ Xray-core started")

                delay(STARTUP_DELAY_MS)
                Log.d(TAG, "✅ Xray-core ready")

                val hevConfig = createHevConfig()
                Log.d(TAG, "Hev config: ${hevConfig.absolutePath}")

                val hevStarted = withContext(Dispatchers.IO) {
                    try {
                        HevBridge.startService(hevConfig.absolutePath, tunFd)
                    } catch (e: Exception) {
                        Log.e(TAG, "HevBridge error: ${e.message}")
                        false
                    }
                }

                if (!hevStarted) {
                    throw IllegalStateException("HevBridge failed to start")
                }
                Log.d(TAG, "✅ HevBridge started")

                isConnected.set(true)
                VpnStatus.setConnected(true)
                VpnStatus.setConnectStartMillis(System.currentTimeMillis())
                
                withContext(Dispatchers.Main) {
                    updateNotification("🟢 متصل شدید", true)
                }

                Log.d(TAG, "========== VPN CONNECTED ✅ ==========")

                startStatsCollection()
                startConnectionMonitor()

            } catch (e: Exception) {
                Log.e(TAG, "❌ VPN start error: ${e.message}", e)
                VpnStatus.setLastError(e.message ?: "Unknown error")
                
                withContext(Dispatchers.Main) {
                    updateNotification("❌ خطا: ${e.message}", false)
                }

                delay(RECONNECT_DELAY_MS)
                if (isConnected.get()) {
                    Log.d(TAG, "Attempting reconnect...")
                    startReconnect(link)
                }
            } finally {
                isStarting.set(false)
            }
        }
    }

    private fun stopVpn() {
        if (!isConnected.getAndSet(false)) {
            Log.d(TAG, "VPN already stopped")
            return
        }

        Log.d(TAG, "========== STOPPING VPN ==========")

        serviceScope.launch {
            try {
                HevBridge.stopService()
                Log.d(TAG, "✅ HevBridge stopped")
            } catch (e: Exception) {
                Log.w(TAG, "HevBridge stop error: ${e.message}")
            }

            try {
                coreController?.stopLoop()
                Log.d(TAG, "✅ Xray-core stopped")
            } catch (e: Exception) {
                Log.w(TAG, "Xray stop error: ${e.message}")
            }

            try {
                tunPfd?.close()
                Log.d(TAG, "✅ TUN closed")
            } catch (e: Exception) {
                Log.w(TAG, "TUN close error: ${e.message}")
            }

            tunPfd = null
            coreController = null
            statsJob?.cancel()
            statsJob = null
            reconnectJob?.cancel()
            reconnectJob = null

            VpnStatus.setConnected(false)
            VpnStatus.reset()
            isConnected.set(false)

            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            Log.d(TAG, "========== VPN STOPPED ==========")
        }
    }

    private fun startReconnect(link: String) {
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            delay(RECONNECT_DELAY_MS)
            if (!isConnected.get()) {
                Log.d(TAG, "🔄 Reconnecting...")
                startVpn(link)
            }
        }
    }

    private fun startStatsCollection() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            delay(STATS_INTERVAL_MS)
            while (isActive && isConnected.get()) {
                try {
                    val stats = HevBridge.getStats()
                    if (stats != null && stats.size >= 4) {
                        VpnStatus.setTxRx(stats[1], stats[3])
                    }
                } catch (e: Exception) {
                    // ignore
                }
                delay(STATS_INTERVAL_MS)
            }
        }
    }

    private fun startConnectionMonitor() {
        serviceScope.launch {
            var consecutiveFailures = 0
            while (isActive && isConnected.get()) {
                delay(5000L)
                try {
                    val stats = HevBridge.getStats()
                    if (stats == null) {
                        consecutiveFailures++
                        if (consecutiveFailures > 3) {
                            Log.w(TAG, "⚠️ HevBridge seems dead, reconnecting...")
                            withContext(Dispatchers.Main) {
                                updateNotification("🔄 تلاش مجدد...", true)
                            }
                            stopVpn()
                        }
                    } else {
                        consecutiveFailures = 0
                    }
                } catch (e: Exception) {
                    consecutiveFailures++
                }
            }
        }
    }

    private fun createHevConfig(): File {
        val configFile = File(cacheDir, "hev_config.yml")
        configFile.writeText(
            """
            |misc:
            |  task-stack-size: 20480
            |  worker-threads: 4
            |
            |tunnel:
            |  mtu: $TUN_MTU
            |  ipv4: $TUN_IPV4
            |  ipv6: $TUN_IPV6
            |
            |socks5:
            |  port: $SOCKS_PORT
            |  address: '127.0.0.1'
            |  udp: 'udp'
            |
            |logging:
            |  level: info
            |  output: /sdcard/hev.log
            """.trimMargin()
        )
        return configFile
    }

    private fun buildNotification(text: String, ongoing: Boolean): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SchnellVPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "وضعیت اتصال VPN"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingOpenIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, SchnellVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val pendingDisconnectIntent = PendingIntent.getService(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SchnellVPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(ongoing)
            .setContentIntent(pendingOpenIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "قطع",
                pendingDisconnectIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String, ongoing: Boolean) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification(text, ongoing))
        } catch (e: Exception) {
            Log.w(TAG, "Notification update error: ${e.message}")
        }
    }

    override fun startup(): Long {
        Log.d(TAG, "Xray callback: startup")
        return 0
    }

    override fun shutdown(): Long {
        Log.d(TAG, "Xray callback: shutdown")
        return 0
    }

    override fun onEmitStatus(code: Long, message: String?): Long {
        if (code != 0L) {
            Log.w(TAG, "Xray status [$code]: $message")
        }
        return 0
    }
}
