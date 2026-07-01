package com.schnellvpn.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
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

/**
 * مسیر واقعی داده:
 * VpnService (TUN می‌سازه) -> hev-socks5-tunnel/HevBridge (پکت خام TUN رو به SOCKS5 تبدیل می‌کنه)
 * -> Xray-core (روی 127.0.0.1:10808 گوش می‌ده و پروتکل واقعی VLESS/VMess/Trojan/SS رو پیاده می‌کنه)
 * -> سرور خارجی
 */
class SchnellVpnService : VpnService(), CoreCallbackHandler {

    companion object {
        const val ACTION_CONNECT = "com.schnellvpn.app.CONNECT"
        const val ACTION_DISCONNECT = "com.schnellvpn.app.DISCONNECT"
        const val EXTRA_LINK = "extra_link"
        private const val CHANNEL_ID = "schnellvpn_service"
        private const val NOTIFICATION_ID = 1
        private const val SOCKS_PORT = 10808
        private const val TUN_ADDRESS = "10.10.14.1"
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var statsJob: kotlinx.coroutines.Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val link = intent.getStringExtra(EXTRA_LINK)
                if (link != null) startVpn(link) else stopSelf()
            }
            ACTION_DISCONNECT -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn(link: String) {
        startForeground(NOTIFICATION_ID, buildNotification("در حال اتصال…"))
        VpnStatus.lastError.value = null

        scope.launch {
            try {
                // 1) لینک واقعی کاربر -> کانفیگ JSON واقعی Xray
                val config = XrayConfigBuilder.buildConfig(link, socksPort = SOCKS_PORT)

                // 2) آماده‌سازی محیط هسته (یک‌بار کافیه)
                Libv2ray.initCoreEnv(filesDir.absolutePath, "")

                // 3) ساخت TUN — VpnService ترافیک واقعی گوشی رو می‌گیره
                val builder = Builder()
                    .setSession("SchnellVPN")
                    .addAddress(TUN_ADDRESS, 30)
                    .addDnsServer("1.1.1.1")
                    .addRoute("0.0.0.0", 0)
                    .setMtu(1500)

                tunInterface = builder.establish()
                val fd = tunInterface?.fd ?: throw IllegalStateException("TUN ساخته نشد")

                // 4) روشن کردن Xray-core — fd صفر چون این دیگه خودش TUN رو نمی‌خونه؛
                //    این کار رو لایه‌ی پایین (HevBridge/tun2socks) انجام می‌ده
                coreController = CoreController(this@SchnellVpnService)
                coreController?.startLoop(config, 0)

                // 5) فایل کانفیگ واقعی hev-socks5-tunnel (همون فرمتی که خودِ کتابخونه انتظار داره)
                val tproxyFile = File(cacheDir, "tproxy.conf")
                val hevConfig = """
                    misc:
                      task-stack-size: 20480
                    tunnel:
                      mtu: 1500
                      ipv4: $TUN_ADDRESS
                    socks5:
                      port: $SOCKS_PORT
                      address: '127.0.0.1'
                      udp: 'udp'
                """.trimIndent()
                tproxyFile.writeText(hevConfig)

                // 6) روشن کردن لایه‌ی واقعی tun2socks — این خودش داخلی یک ترد می‌سازه و بلافاصله برمی‌گرده
                HevBridge.TProxyStartService(tproxyFile.absolutePath, fd)

                VpnStatus.isConnected.value = true
                VpnStatus.connectStartMillis.value = System.currentTimeMillis()
                startStatsPolling()

                withContext(Dispatchers.Main) { updateNotification("متصل شدید") }
            } catch (e: Exception) {
                VpnStatus.lastError.value = e.message
                withContext(Dispatchers.Main) { updateNotification("اتصال ناموفق بود: ${e.message}") }
                stopVpn()
            }
        }
    }

    // هر ثانیه آمار واقعی حجم رد شده رو از خودِ hev-socks5-tunnel می‌خونیم
    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive && VpnStatus.isConnected.value) {
                try {
                    val stats = HevBridge.TProxyGetStats() // [txPackets, txBytes, rxPackets, rxBytes]
                    if (stats.size >= 4) {
                        VpnStatus.txBytes.value = stats[1]
                        VpnStatus.rxBytes.value = stats[3]
                    }
                } catch (_: Throwable) { /* اگه سرویس هنوز کامل بالا نیومده، نادیده بگیر */ }
                delay(1000)
            }
        }
    }

    private fun stopVpn() {
        scope.launch {
            statsJob?.cancel()
            try { HevBridge.TProxyStopService() } catch (_: Throwable) { }
            try { coreController?.stopLoop() } catch (_: Exception) { }
            try { tunInterface?.close() } catch (_: Exception) { }
            tunInterface = null
            coreController = null
            VpnStatus.reset()
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun startup(): Long = 0
    override fun shutdown(): Long = 0
    override fun onEmitStatus(code: Long, message: String?): Long = 0

    private fun buildNotification(text: String): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SchnellVPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SchnellVPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }
}
