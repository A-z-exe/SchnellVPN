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
        private val DNS_SERVERS = listOf("1.1.1.1", "8.8.8.8")
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val link = intent.getStringExtra(EXTRA_LINK)
                if (!link.isNullOrEmpty()) startVpn(link)
                else { Log.e(TAG, "Link is empty"); stopSelf() }
            }
            ACTION_DISCONNECT -> stopVpn()
            else -> Log.w(TAG, "Unknown action: ${intent?.action}")
        }
        return START_STICKY
    }

    override fun onRevoke() { stopVpn(); super.onRevoke() }
    override fun onDestroy() { stopVpn(); serviceScope.cancel(); super.onDestroy() }

    private fun startVpn(link: String) {
        if (isStarting.getAndSet(true)) return
        Log.d(TAG, "========== STARTING VPN ==========")

        startForeground(NOTIF_ID, buildNotification("در حال اتصال...", true))
        VpnStatus.reset()

        serviceScope.launch {
            try {
                // ۱. ساخت config
                val config = withContext(Dispatchers.IO) {
                    XrayConfigBuilder.buildConfig(link, SOCKS_PORT)
                }
                Log.d(TAG, "✅ Config built (${config.length} chars)")

                // ۲. init Xray env
                withContext(Dispatchers.IO) {
                    try {
                        Libv2ray.initCoreEnv(filesDir.absolutePath, "")
                        Log.d(TAG, "✅ Xray env initialized")
                    } catch (e: Exception) {
                        Log.w(TAG, "initCoreEnv: ${e.message}")
                    }
                }

                // ۳. ساخت TUN
                val builder = Builder()
                    .setSession("SchnellVPN")
                    .setMtu(TUN_MTU)
                    .addAddress(TUN_IPV4, 32)
                    .addAddress(TUN_IPV6, 64)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .setBlocking(true)

                DNS_SERVERS.forEach { builder.addDnsServer(it) }

                tunPfd = builder.establish()
                    ?: throw IllegalStateException("TUN establish failed")
                val tunFd = tunPfd!!.fd
                Log.d(TAG, "✅ TUN created (fd=$tunFd)")

                // ۴. شروع Xray-core
                val controller = CoreController(this@SchnellVpnService)
                val started = withContext(Dispatchers.IO) {
                    try {
                        controller.startLoop(config, -1)
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "Xray startLoop error: ${e.message}")
                        false
                    }
                }
                if (!started) throw IllegalStateException("Xray-core failed to start")
                coreController = controller
                Log.d(TAG, "✅ Xray-core started")

                delay(STARTUP_DELAY_MS)

                // ۵. شروع HevBridge
                val hevConfig = createHevConfig()
                val hevStarted = withContext(Dispatchers.IO) {
                    HevBridge.startService(hevConfig.absolutePath, tunFd)
                }
                if (!hevStarted) throw IllegalStateException("HevBridge failed to start")
                Log.d(TAG, "✅ HevBridge started")

                isConnected.set(true)
                VpnStatus.setConnected(true)
                VpnStatus.setConnectStartMillis(System.currentTimeMillis())

                withContext(Dispatchers.Main) {
                    updateNotification("🟢 متصل شدید", true)
                }
                Log.d(TAG, "========== VPN CONNECTED ✅ ==========")

                startStatsCollection()

            } catch (e: Exception) {
                Log.e(TAG, "❌ VPN error: ${e.message}", e)
                VpnStatus.setLastError(e.message ?: "Unknown error")
                withContext(Dispatchers.Main) {
                    updateNotification("❌ خطا: ${e.message}", false)
                }
                cleanupResources()
            } finally {
                isStarting.set(false)
            }
        }
    }

    private fun stopVpn() {
        if (!isConnected.getAndSet(false)) return
        Log.d(TAG, "========== STOPPING VPN ==========")
        serviceScope.launch { cleanupResources() }
    }

    private suspend fun cleanupResources() {
        statsJob?.cancel(); statsJob = null
        reconnectJob?.cancel(); reconnectJob = null

        try { HevBridge.stopService() } catch (e: Exception) { Log.w(TAG, "Hev stop: ${e.message}") }
        try { coreController?.stopLoop(); coreController = null } catch (e: Exception) { Log.w(TAG, "Xray stop: ${e.message}") }
        try { tunPfd?.close(); tunPfd = null } catch (e: Exception) { Log.w(TAG, "TUN close: ${e.message}") }

        VpnStatus.setConnected(false)
        VpnStatus.reset()
        isConnected.set(false)

        withContext(Dispatchers.Main) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        Log.d(TAG, "========== VPN STOPPED ==========")
    }

    private fun startStatsCollection() {
        statsJob = serviceScope.launch {
            while (isActive && isConnected.get()) {
                try {
                    val tx = coreController?.queryStats("proxy", "uplink") ?: 0L
                    val rx = coreController?.queryStats("proxy", "downlink") ?: 0L
                    VpnStatus.setTxRx(tx, rx)
                } catch (_: Exception) {}
                delay(STATS_INTERVAL_MS)
            }
        }
    }

    private fun createHevConfig(): File {
        val configFile = File(cacheDir, "hev_config.yml")
        configFile.writeText("""
            |misc:
            |  task-stack-size: 20480
            |  worker-threads: 4
            |tunnel:
            |  mtu: $TUN_MTU
            |  ipv4: $TUN_IPV4
            |  ipv6: $TUN_IPV6
            |socks5:
            |  port: $SOCKS_PORT
            |  address: '127.0.0.1'
            |  udp: 'udp'
            |logging:
            |  level: warning
            |  output: /sdcard/hev.log
        """.trimMargin())
        return configFile
    }

    // ========== CoreCallbackHandler ==========
    override fun startup(): Long {
        Log.d(TAG, "Xray callback: startup")
        return 0
    }

    override fun shutdown(): Long {
        Log.d(TAG, "Xray callback: shutdown")
        return 0
    }

    override fun onEmitStatus(code: Long, message: String?): Long {
        if (code != 0L) Log.w(TAG, "Xray status [$code]: $message")
        return 0
    }

    private fun buildNotification(text: String, ongoing: Boolean): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SchnellVPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectPi = PendingIntent.getService(
            this, 1,
            Intent(this, SchnellVpnService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SchnellVPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(ongoing)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "قطع", disconnectPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String, ongoing: Boolean) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(text, ongoing))
        } catch (e: Exception) { Log.w(TAG, "Notif error: ${e.message}") }
    }
}
