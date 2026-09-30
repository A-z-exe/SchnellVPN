package com.schnellvpn.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
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
        private const val STATS_INTERVAL_MS = 1000L
    }

    private var tunPfd: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null
    private var statsJob: Job? = null

    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private val disconnectRequested = AtomicBoolean(false)
    private val isCleaning = AtomicBoolean(false)

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                // The activity uses startForegroundService(): we MUST call startForeground()
                // within ~5s or Android kills the app (ForegroundServiceDidNotStartInTimeException).
                enterForeground("در حال اتصال…")
                val link = intent.getStringExtra(EXTRA_LINK)
                if (!link.isNullOrEmpty()) {
                    startVpn(link)
                } else {
                    Log.e(TAG, "Link is empty")
                    VpnStatus.setLastError("لینک سرور خالی است")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_DISCONNECT -> stopVpn()
            else -> if (!isConnected.get() && !isConnecting.get()) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Best-effort synchronous release in case the system destroys us mid-connection.
        releaseResources()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN revoked by system")
        stopVpn()
        super.onRevoke()
    }

    private fun startVpn(link: String) {
        if (isConnected.get() || !isConnecting.compareAndSet(false, true)) {
            Log.w(TAG, "Already connected/connecting")
            return
        }
        disconnectRequested.set(false)

        serviceScope.launch {
            try {
                Log.d(TAG, "========== VPN CONNECT START ==========")

                // 1. Xray config (throws IllegalArgumentException with a readable message)
                val config = XrayConfigBuilder.buildConfig(link, SOCKS_PORT)

                // 2. Xray environment
                try {
                    Libv2ray.initCoreEnv(filesDir.absolutePath, "")
                } catch (e: Exception) {
                    Log.w(TAG, "initCoreEnv warning: ${e.message}")
                }

                // 3. TUN interface.
                // IMPORTANT: exclude this app from the VPN. Xray's outbound sockets live in this
                // process; without this they would be routed back into the TUN (routing loop).
                val tun = withContext(Dispatchers.Main) {
                    Builder()
                        .setSession("SchnellVPN")
                        .setMtu(TUN_MTU)
                        .addAddress(TUN_IPV4, 32)
                        .addAddress(TUN_IPV6, 128)
                        .addRoute("0.0.0.0", 0)
                        .addRoute("::", 0)
                        .addDnsServer("1.1.1.1")
                        .addDnsServer("8.8.8.8")
                        .apply {
                            try {
                                addDisallowedApplication(packageName)
                            } catch (e: Exception) {
                                Log.e(TAG, "addDisallowedApplication failed: ${e.message}")
                            }
                        }
                        .establish()
                } ?: throw IllegalStateException("ساخت TUN ناموفق بود — مجوز VPN داده نشده")
                tunPfd = tun // keep a reference immediately so cleanup can always close it

                // 4. Xray-core: local SOCKS5 inbound only. tunFd = 0 → Xray does NOT touch the TUN
                //    (hev-socks5-tunnel owns it; passing the same fd to both would conflict).
                val controller = CoreController(this@SchnellVpnService)
                coreController = controller
                controller.startLoop(config, 0)

                // 5. hev-socks5-tunnel: TUN -> 127.0.0.1:SOCKS_PORT
                val hevConf = HevBridge.writeConfig(filesDir, SOCKS_PORT, TUN_MTU, TUN_IPV4, TUN_IPV6)
                if (!HevBridge.startService(hevConf.absolutePath, tun.fd)) {
                    throw IllegalStateException("راه‌اندازی hev-socks5-tunnel ناموفق بود")
                }

                if (disconnectRequested.get()) throw CancellationException("Disconnected during connect")

                isConnected.set(true)
                VpnStatus.setConnected(true)
                VpnStatus.setConnectStartMillis(System.currentTimeMillis())
                withContext(Dispatchers.Main) { updateNotification("🟢 متصل شدید", true) }
                Log.d(TAG, "========== VPN CONNECTED ==========")

                startStatsCollection()

            } catch (e: CancellationException) {
                Log.i(TAG, "قطع حین اتصال")
                cleanupResources()
            } catch (e: Exception) {
                Log.e(TAG, "VPN error: ${e.message}", e)
                VpnStatus.setLastError(e.message ?: "Unknown error")
                cleanupResources()
            } finally {
                isConnecting.set(false)
                if (disconnectRequested.get() && isConnected.get()) cleanupResources()
            }
        }
    }

    private fun stopVpn() {
        disconnectRequested.set(true)
        // If a connect is in flight, its catch/finally path performs the cleanup.
        if (isConnecting.get()) return
        serviceScope.launch { cleanupResources() }
    }

    /** Blocking, idempotent release of native resources (safe from any thread). */
    @Synchronized
    private fun releaseResources() {
        statsJob?.cancel(); statsJob = null
        try { HevBridge.stopService() } catch (e: Exception) { Log.w(TAG, "hev stop: ${e.message}") }
        try { coreController?.stopLoop() } catch (e: Exception) { Log.w(TAG, "Xray stop: ${e.message}") }
        coreController = null
        try { tunPfd?.close() } catch (e: Exception) { Log.w(TAG, "TUN close: ${e.message}") }
        tunPfd = null
    }

    private suspend fun cleanupResources() {
        if (!isCleaning.compareAndSet(false, true)) return
        try {
            releaseResources()
            isConnected.set(false)
            VpnStatus.reset()
            withContext(NonCancellable + Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            Log.d(TAG, "========== VPN STOPPED ==========")
        } finally {
            isCleaning.set(false)
        }
    }

    private fun startStatsCollection() {
        statsJob = serviceScope.launch {
            while (isActive && isConnected.get()) {
                // hev: [txPackets, txBytes, rxPackets, rxBytes] (cumulative since start)
                val s = HevBridge.getStats()
                if (s != null && s.size >= 4) VpnStatus.setTxRx(s[1], s[3])
                delay(STATS_INTERVAL_MS)
            }
        }
    }

    // ========== CoreCallbackHandler ==========
    override fun startup(): Long { Log.d(TAG, "Xray callback: startup"); return 0 }
    override fun shutdown(): Long { Log.d(TAG, "Xray callback: shutdown"); return 0 }
    override fun onEmitStatus(code: Long, message: String?): Long {
        Log.d(TAG, "Xray status [$code]: $message"); return 0
    }

    // ========== Notification ==========
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SchnellVPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun enterForeground(text: String) {
        val notification = buildNotification(text, true)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}", e)
        }
    }

    private fun buildNotification(text: String, ongoing: Boolean): android.app.Notification {
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
        } catch (e: Exception) {
            Log.w(TAG, "Notif error: ${e.message}")
        }
    }
}
