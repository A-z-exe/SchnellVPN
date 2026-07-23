package com.schnellvpn.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File
import java.net.Socket

class SchnellVpnService : VpnService(), CoreCallbackHandler {

    companion object {
        const val ACTION_CONNECT    = "com.schnellvpn.app.CONNECT"
        const val ACTION_DISCONNECT = "com.schnellvpn.app.DISCONNECT"
        const val EXTRA_LINK        = "extra_link"
        private const val TAG        = "SchnellVPN"
        private const val CHANNEL_ID = "schnellvpn_ch"
        private const val NOTIF_ID   = 1
        private const val SOCKS_PORT = 10808
        private const val TUN_ADDR   = "10.10.14.1"
    }

    private var tunPfd: ParcelFileDescriptor? = null
    private var coreCtrl: CoreController? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT    -> intent.getStringExtra(EXTRA_LINK)?.let { startVpn(it) } ?: stopSelf()
            ACTION_DISCONNECT -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn(link: String) {
        startForeground(NOTIF_ID, buildNotif("در حال اتصال..."))
        VpnStatus.reset()

        scope.launch {
            try {
                val config = XrayConfigBuilder.buildConfig(link, socksPort = SOCKS_PORT)
                Log.d(TAG, "Config built (${config.length} chars)")

                Libv2ray.initCoreEnv(filesDir.absolutePath, "")
                Log.d(TAG, "Xray env initialized")

                val builder = Builder()
                    .setSession("SchnellVPN")
                    .addAddress(TUN_ADDR, 30)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("1.1.1.1")
                    .addRoute("0.0.0.0", 0)
                    .setMtu(1500)
                tunPfd = builder.establish()
                    ?: throw IllegalStateException("TUN establish() returned null")
                val tunFd = tunPfd!!.fd
                Log.d(TAG, "TUN created fd=$tunFd")

                coreCtrl = CoreController(this@SchnellVpnService)
                val xrayErr = coreCtrl!!.startLoop(config, tunFd)
                Log.d(TAG, "Xray startLoop result=$xrayErr")
                if (xrayErr != 0) throw IllegalStateException("Xray error: $xrayErr")

                delay(2000)

                // Check SOCKS5 port
                var portOpen = false
                repeat(10) {
                    if (!portOpen) try {
                        Socket("127.0.0.1", SOCKS_PORT).close()
                        portOpen = true
                        Log.d(TAG, "SOCKS5 port $SOCKS_PORT is OPEN")
                    } catch (e: Exception) { delay(500) }
                }
                if (!portOpen) Log.w(TAG, "SOCKS5 port $SOCKS_PORT still CLOSED after 5s")

                // Write hev config
                val hevFile = File(cacheDir, "hev.yml")
                hevFile.writeText("misc:\n  task-stack-size: 20480\ntunnel:\n  mtu: 1500\n  ipv4: $TUN_ADDR\nsocks5:\n  port: $SOCKS_PORT\n  address: '127.0.0.1'\n  udp: 'udp'\n")

                // Load and start HevBridge
                val libLoaded = HevBridge.load()
                Log.d(TAG, "HevBridge.load() = $libLoaded")
                if (libLoaded) {
                    val hevOk = HevBridge.startService(hevFile.absolutePath, tunFd)
                    Log.d(TAG, "HevBridge.startService() = $hevOk")
                } else {
                    Log.e(TAG, "HevBridge NOT loaded - libhev-socks5-tunnel.so missing from APK!")
                }

                VpnStatus.isConnected.value = true
                VpnStatus.connectStartMillis.value = System.currentTimeMillis()
                withContext(Dispatchers.Main) { updateNotif("متصل شدید") }
                Log.d(TAG, "VPN CONNECTED")

                delay(3000)
                while (isActive && VpnStatus.isConnected.value) {
                    HevBridge.getStats()?.takeIf { it.size >= 4 }?.let {
                        VpnStatus.txBytes.value = it[1]
                        VpnStatus.rxBytes.value = it[3]
                    }
                    delay(1000)
                }
            } catch (e: Exception) {
                Log.e(TAG, "VPN error: ${e.message}", e)
                VpnStatus.lastError.value = e.message
                withContext(Dispatchers.Main) { updateNotif("خطا: ${e.message}") }
                delay(2000)
                stopVpn()
            }
        }
    }

    private fun stopVpn() {
        Log.d(TAG, "STOPPING VPN")
        VpnStatus.isConnected.value = false
        scope.launch {
            HevBridge.stopService()
            try { coreCtrl?.stopLoop() } catch (e: Exception) { Log.w(TAG, "stop: ${e.message}") }
            try { tunPfd?.close() } catch (e: Exception) { }
            tunPfd = null; coreCtrl = null
            VpnStatus.reset()
            withContext(Dispatchers.Main) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            Log.d(TAG, "VPN STOPPED")
        }
    }

    override fun startup(): Long { Log.d(TAG, "Xray callback: startup"); return 0 }
    override fun shutdown(): Long = 0
    override fun onEmitStatus(code: Long, msg: String?): Long { Log.d(TAG, "Xray status[$code]: $msg"); return 0 }
    override fun onRevoke() { stopVpn(); super.onRevoke() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun buildNotif(text: String): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "SchnellVPN", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SchnellVPN").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock).setOngoing(true).build()
    }
    private fun updateNotif(t: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(t))
}                withContext(Dispatchers.IO) {
                    val err = try {
                        controller.startLoop(config, tunFd)
                        null
                    } catch (e: Exception) {
                        e.message
                    }
                    if (err != null) throw IllegalStateException("Xray-core error: $err")
                }
                coreController = controller
                Log.d(TAG, "✅ Xray-core started with TUN fd=$tunFd")

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

    // ========== CoreCallbackHandler ==========
    override fun startup(): Long {
        Log.d(TAG, "✅ Xray callback: startup")
        return 0
    }

    override fun shutdown(): Long {
        Log.d(TAG, "Xray callback: shutdown")
        return 0
    }

    override fun onEmitStatus(code: Long, message: String?): Long {
        Log.d(TAG, "Xray status [$code]: $message")
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
