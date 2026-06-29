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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

/**
 * سرویس اصلی VPN - اتصال TUN + Xray Core
 */
class SchnellVpnService : VpnService(), CoreCallbackHandler {

    companion object {
        const val ACTION_CONNECT = "com.schnellvpn.app.CONNECT"
        const val ACTION_DISCONNECT = "com.schnellvpn.app.DISCONNECT"
        const val EXTRA_LINK = "extra_link"
        private const val CHANNEL_ID = "schnellvpn_service"
        private const val NOTIFICATION_ID = 1
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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

        scope.launch {
            try {
                // 1) ساخت کانفیگ Xray
                val config = XrayConfigBuilder.buildConfig(link)

                // 2) آماده‌سازی محیط Xray
                Libv2ray.initCoreEnv(filesDir.absolutePath, "")

                // 3) ساخت TUN Interface (بهینه‌شده)
                val builder = Builder()
                    .setSession("SchnellVPN")
                    .setMtu(1500)
                    .addAddress("10.10.14.1", 30)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .addRoute("0.0.0.0", 0)      // تمام IPv4
                    .addRoute("::", 0)            // تمام IPv6
                    .addDisallowedApplication(packageName)           // خود اپ
                    .addDisallowedApplication("com.android.vending") // Play Store

                tunInterface = builder.establish()
                val fd = tunInterface?.fd ?: throw IllegalStateException("TUN ساخته نشد")

                // 4) شروع هسته Xray
                coreController = CoreController(this@SchnellVpnService)
                coreController?.startLoop(config, fd)

                withContext(Dispatchers.Main) {
                    updateNotification("متصل شدید ✓")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    updateNotification("خطا: ${e.message}")
                }
                stopVpn()
            }
        }
    }

    private fun stopVpn() {
        scope.launch {
            try { coreController?.stopLoop() } catch (_: Exception) { }
            try { tunInterface?.close() } catch (_: Exception) { }
            
            tunInterface = null
            coreController = null

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

    // Callbackهای Core
    override fun startup(): Long = 0
    override fun shutdown(): Long = 0
    override fun onEmitStatus(code: Long, message: String?): Long = 0

    // Notification
    private fun buildNotification(text: String): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SchnellVPN Service",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SchnellVPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
