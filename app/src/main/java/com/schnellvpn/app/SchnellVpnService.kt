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

class SchnellVpnService : VpnService(), CoreCallbackHandler {

    companion object {
        const val ACTION_CONNECT    = "com.schnellvpn.app.CONNECT"
        const val ACTION_DISCONNECT = "com.schnellvpn.app.DISCONNECT"
        const val EXTRA_LINK        = "extra_link"
        private const val TAG        = "SchnellVPN"
        private const val CHANNEL_ID = "schnellvpn_service"
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
        startForeground(NOTIF_ID, buildNotif("در حال اتصال…"))
        VpnStatus.reset()

        scope.launch {
            try {
                // 1) لینک -> کانفیگ JSON
                val config = XrayConfigBuilder.buildConfig(link, socksPort = SOCKS_PORT)
                Log.d(TAG, "Config built OK")

                // 2) آماده‌سازی Xray
                try {
                    Libv2ray.initCoreEnv(filesDir.absolutePath, "")
                } catch (e: Exception) {
                    Log.e(TAG, "initCoreEnv failed: ${e.message}")
                }

                // 3) ساخت TUN
                val builder = Builder()
                    .setSession("SchnellVPN")
                    .addAddress(TUN_ADDR, 30)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .addRoute("0.0.0.0", 0)
                    .setMtu(1500)

                tunPfd = builder.establish()
                    ?: throw IllegalStateException("TUN establish failed — اجازه‌ی VPN داده نشده")
                val tunFd = tunPfd!!.fd
                Log.d(TAG, "TUN fd=$tunFd")

                // 4) Xray-core به عنوان SOCKS5 proxy
                coreCtrl = CoreController(this@SchnellVpnService)
                coreCtrl!!.startLoop(config, -1)
                Log.d(TAG, "Xray-core started")

                // 5) صبر تا SOCKS5 آماده بشه
                delay(2000)

                // 6) کانفیگ hev-socks5-tunnel
                val hevConf = File(cacheDir, "hev.yml")
                hevConf.writeText("""
                    misc:
                      task-stack-size: 20480
                    tunnel:
                      mtu: 1500
                      ipv4: $TUN_ADDR
                    socks5:
                      port: $SOCKS_PORT
                      address: '127.0.0.1'
                      udp: 'udp'
                """.trimIndent())

                // 7) tun2socks — safe start
                val hevOk = HevBridge.startService(hevConf.absolutePath, tunFd)
                Log.d(TAG, "HevBridge.startService=$hevOk")

                VpnStatus.isConnected.value = true
                VpnStatus.connectStartMillis.value = System.currentTimeMillis()
                withContext(Dispatchers.Main) { updateNotif("متصل شدید") }

                // 8) polling آمار — با delay اول
                delay(3000)
                while (isActive && VpnStatus.isConnected.value) {
                    val s = HevBridge.getStats()
                    if (s != null && s.size >= 4) {
                        VpnStatus.txBytes.value = s[1]
                        VpnStatus.rxBytes.value = s[3]
                    }
                    delay(1000)
                }

            } catch (e: Exception) {
                Log.e(TAG, "startVpn error: ${e.message}", e)
                VpnStatus.lastError.value = e.message
                withContext(Dispatchers.Main) { updateNotif("خطا: ${e.message}") }
                delay(2000)
                stopVpn()
            }
        }
    }

    private fun stopVpn() {
        scope.launch {
            VpnStatus.isConnected.value = false
            HevBridge.stopService()
            try { coreCtrl?.stopLoop() } catch (e: Exception) { Log.w(TAG, "Xray stop: ${e.message}") }
            try { tunPfd?.close() }       catch (e: Exception) { Log.w(TAG, "TUN close: ${e.message}") }
            tunPfd = null; coreCtrl = null
            VpnStatus.reset()
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onRevoke() { stopVpn(); super.onRevoke() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun startup(): Long = 0
    override fun shutdown(): Long = 0
    override fun onEmitStatus(code: Long, message: String?): Long = 0

    private fun buildNotif(text: String): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "SchnellVPN", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SchnellVPN").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true).build()
    }

    private fun updateNotif(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(text))
}
