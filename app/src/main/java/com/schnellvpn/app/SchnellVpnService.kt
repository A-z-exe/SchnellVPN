package com.schnellvpn.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.Os
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
        private const val TAG = "SchnellVpnService"
    }

    private var tunInterface: ParcelFileDescriptor? = null
    // اگر fd را detach کردیم، مالکیت به native منتقل شده و این فیلد fd نگهداری می‌شود
    private var tunFd: Int = -1
    private var nativeOwnsTunFd: Boolean = false
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
                Log.i(TAG, "startVpn: building config")
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

                val pfd = builder.establish()
                if (pfd == null) throw IllegalStateException("TUN ساخته نشد")

                synchronized(this@SchnellVpnService) {
                    tunInterface = pfd
                }

                // انتقال مالکیت FD به native (detach) — اگر این کار انجام شود، دیگر نباید این FD را در جاوا ببندیم
                val fdToGive = try {
                    pfd.detachFd()
                } catch (ex: Exception) {
                    Log.w(TAG, "detachFd failed, trying to use raw fd: ${ex.message}")
                    pfd.fd
                }

                synchronized(this@SchnellVpnService) {
                    tunFd = fdToGive
                    nativeOwnsTunFd = true
                    // اگر detach انجام شده، tunInterface دیگر دیگر برای ما معنی ندارد
                    tunInterface = null
                }

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
                try {
                    Log.i(TAG, "Starting TProxy service with fd=$tunFd")
                    HevBridge.TProxyStartService(tproxyFile.absolutePath, tunFd)
                } catch (t: Throwable) {
                    Log.e(TAG, "TProxyStartService failed: ${t.message}", t)
                    // اگر native شروع نشد، سعی کنیم fd را ببندیم و تمیزکاری کنیم
                    synchronized(this@SchnellVpnService) {
                        if (nativeOwnsTunFd && tunFd != -1) {
                            try { Os.close(tunFd) } catch (_: Throwable) {}
                            tunFd = -1
                            nativeOwnsTunFd = false
                        }
                    }
                    VpnStatus.lastError.value = "tproxy start failed: ${t.message}"
                    stopVpn()
                    return@launch
                }

                // همه چیز بالا آمد — وضعیت را علامت‌گذاری کن
                VpnStatus.isConnected.value = true
                VpnStatus.connectStartMillis.value = System.currentTimeMillis()
                startStatsPolling()

                withContext(Dispatchers.Main) { updateNotification("متصل شدید") }
            } catch (e: Exception) {
                Log.e(TAG, "startVpn exception: ${e.message}", e)
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
                } catch (t: Throwable) {
                    Log.w(TAG, "TProxyGetStats exception (possibly not ready yet): ${t.message}")
                }
                delay(1000)
            }
        }
    }

    private fun stopVpn() {
        scope.launch {
            statsJob?.cancel()
            try {
                Log.i(TAG, "Stopping TProxy service")
                HevBridge.TProxyStopService()
            } catch (t: Throwable) {
                Log.w(TAG, "Error stopping TProxy: ${t.message}")
            }

            try {
                coreController?.stopLoop()
            } catch (e: Exception) {
                Log.w(TAG, "coreController.stopLoop failed: ${e.message}")
            }

            // اگر native هنوز مالک FD است، ببندش
            synchronized(this@SchnellVpnService) {
                if (nativeOwnsTunFd && tunFd != -1) {
                    try { Os.close(tunFd) } catch (t: Throwable) { Log.w(TAG, "closing tunFd failed: ${t.message}") }
                    tunFd = -1
                    nativeOwnsTunFd = false
                }
                try { tunInterface?.close() } catch (e: Exception) { /* ignore */ }
                tunInterface = null
            }

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
