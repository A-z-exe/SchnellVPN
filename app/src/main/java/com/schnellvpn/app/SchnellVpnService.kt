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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val link = intent.getStringExtra(EXTRA_LINK)
                if (!link.isNullOrEmpty()) startVpn(link)
                else { Log.e(TAG, "Link is empty"); stopSelf() }
            }
            ACTION_DISCONNECT -> stopVpn()
            // START_NOT_STICKY → مسیر null/unknown نباید رخ بده؛ اگر شد، تمیز ببند
            null -> { stopVpn(); stopSelf() }
            else -> { Log.w(TAG, "Unknown action: ${intent.action}"); stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() { stopVpn(); super.onRevoke() }
    override fun onDestroy() { stopVpn(); serviceScope.cancel(); super.onDestroy() }

    private fun startVpn(link: String) {
        if (!isConnecting.compareAndSet(false, true)) return
        disconnectRequested.set(false)

        startForeground(NOTIF_ID, buildNotification("در حال اتصال...", true))
        VpnStatus.reset()

        serviceScope.launch {
            var controller: CoreController? = null
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
                    } catch (e: Exception) {
                        Log.w(TAG, "initCoreEnv warning: ${e.message}")
                    }
                }

                // ۳. ساخت TUN interface (باید روی Main thread باشه)
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
                        .setBlocking(false)
                        .establish()
                } ?: throw IllegalStateException("TUN establish failed — مجوز VPN داده نشد")

                // ۴. شروع hev-socks5-tunnel: فوروارد TUN → SOCKS محلی Xray
                val hevConf = withContext(Dispatchers.IO) { writeHevConfig() }
                if (!HevBridge.startService(hevConf.absolutePath, tun.fd)) {
                    throw IllegalStateException("hev-socks5-tunnel start failed")
                }

                // ۵. شروع Xray-core
                // توجه: اگر نسخه‌ی libv2ray تو API سیگنچر startLoop(config, tunFd) داره
                // (یعنی خودش TUN رو هندل می‌کنه)، این فراخوانی رو با همون جایگزین کن
                // و مرحله‌ی ۴ رو حذف کن. هر دو نباید هم‌زمان TUN رو هندل کنن!
                controller = CoreController(this@SchnellVpnService)
                withContext(Dispatchers.IO) {
                    try {
                        controller!!.startLoop(config, tunFd)
                    } catch (e: Exception) {
                        throw IllegalStateException("Xray-core error: ${e.message}")
                    }
                }

                tunPfd = tun
                coreController = controller

                // قطع حین اتصال → اینجا cleanup انجام می‌شه
                if (disconnectRequested.get()) throw CancellationException("Disconnected during connect")

                isConnected.set(true)
                VpnStatus.setConnected(true)
                VpnStatus.setConnectStartMillis(System.currentTimeMillis())

                withContext(Dispatchers.Main) { updateNotification("🟢 متصل شدید", true) }
                Log.d(TAG, "========== VPN CONNECTED ✅ ==========")

                startStatsCollection()

            } catch (e: CancellationException) {
                Log.i(TAG, "قطع حین اتصال")
                cleanupResources()
            } catch (e: Exception) {
                Log.e(TAG, "❌ VPN error: ${e.message}", e)
                VpnStatus.setLastError(e.message ?: "Unknown error")
                withContext(Dispatchers.Main) { updateNotification("❌ خطا: ${e.message}", false) }
                cleanupResources()
            } finally {
                isConnecting.set(false)
                // اگر قطع بعد از چک disconnectRequested رخ داده بود، اینجا جبران می‌شه
                if (disconnectRequested.get() && isConnected.get()) cleanupResources()
            }
        }
    }

    private fun stopVpn() {
        disconnectRequested.set(true)
        // اگر اتصال در جریانه، مسیر catch/finally در startVpn خودش cleanup می‌کنه
        if (isConnecting.get()) return
        serviceScope.launch { cleanupResources() }
    }

    private suspend fun cleanupResources() {
        if (!isCleaning.compareAndSet(false, true)) return
        try {
            statsJob?.cancel(); statsJob = null

            try { coreController?.stopLoop() } catch (e: Exception) { Log.w(TAG, "Xray stop: ${e.message}") }
            coreController = null

            HevBridge.stopService()

            try { tunPfd?.close() } catch (e: Exception) { Log.w(TAG, "TUN close: ${e.message}") }
            tunPfd = null

            VpnStatus.setConnected(false)
            VpnStatus.reset()
            isConnected.set(false)

            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            Log.d(TAG, "========== VPN STOPPED ==========")
        } finally {
            isCleaning.set(false)
        }
    }

    private fun writeHevConfig(): File {
        val f = File(filesDir, "hev_tunnel.yml")
        if (!f.exists()) {
            f.writeText(
                """
                tunnel:
                  mtu: 1500
                  ipv4: 10.0.0.2
                  ipv6: fd00::2
                socks5:
                  address: 127.0.0.1
                  port: 10808
                  udp: 'udp'
                misc:
                  log-level: warning
                """.trimIndent()
            )
        }
        return f
    }

    private fun startStatsCollection() {
        statsJob = serviceScope.launch {
            // TProxyGetStats مقادیر «تجمعی» برمی‌گردونه → باید delta حساب بشه، نه جمع مستقیم!
            var lastUp = -1L
            var lastDown = -1L
            var totalTx = 0L
            var totalRx = 0L
            while (isActive && isConnected.get()) {
                try {
                    val stats = HevBridge.getStats()
                    if (stats != null && stats.size >= 3) {
                        val up = stats[1]   // up تجمعی
                        val down = stats[2] // down تجمعی
                        if (lastUp < 0 || up < lastUp || down < lastDown) {
                            lastUp = up; lastDown = down // سشن جدید ریست شده
                        } else {
                            totalTx += up - lastUp
                            totalRx += down - lastDown
                            lastUp = up; lastDown = down
                            VpnStatus.setTxRx(totalTx, totalRx)
                        }
                    }
                } catch (_: Exception) {}
                delay(STATS_INTERVAL_MS)
            }
        }
    }

    // ========== CoreCallbackHandler ==========
    override fun startup(): Long { Log.d(TAG, "✅ Xray callback: startup"); return 0 }
    override fun shutdown(): Long { Log.d(TAG, "Xray callback: shutdown"); return 0 }
    override fun onEmitStatus(code: Long, message: String?): Long {
        Log.d(TAG, "Xray status [$code]: $message"); return 0
    }

    // ========== Notification ==========
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
