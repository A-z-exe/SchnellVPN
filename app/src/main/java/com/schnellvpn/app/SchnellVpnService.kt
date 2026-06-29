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
 * این سرویس کاری که توی نمودار به این صورت نشون داده می‌شد رو انجام می‌ده:
 * VpnService (TUN می‌سازه) -> Xray-core (پروتکل رو پیاده‌سازی می‌کنه)
 * تن‌تو‌سوکس به‌صورت خودکار داخل خودِ libv2ray.aar انجام می‌شه، نیازی به کتابخونه‌ی جدا نیست.
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
                // 1) ConfigParser: لینک رو به JSON تبدیل می‌کنیم
                val config = XrayConfigBuilder.buildConfig(link)

                // 2) آماده‌سازی محیط هسته (یک‌بار کافیه)
                Libv2ray.initCoreEnv(filesDir.absolutePath, "")

                // 3) ساخت TUN interface (اینجا VpnService ترافیک گوشی رو می‌گیره)
                val builder = Builder()
                    .setSession("SchnellVPN")
                    .addAddress("10.10.14.1", 30)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .setMtu(1500)
                    .addDisallowedApplication("com.android.vending") // Play Store
                    .addDisallowedApplication(packageName)           // خود اپ
                tunInterface = builder.establish()
                val fd = tunInterface?.fd ?: throw IllegalStateException("TUN ساخته نشد")

                // 4) روشن کردن هسته‌ی Xray با همین فایل‌دیسکریپتور TUN
                coreController = CoreController(this@SchnellVpnService)
                coreController?.startLoop(config, fd)

                withContext(Dispatchers.Main) { updateNotification("متصل شدید") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { updateNotification("اتصال ناموفق بود: ${e.message}") }
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

    // ---- این سه تا متد رو خودِ کتابخونه libv2ray صدا می‌زنه، لازم نیست خودت صداش کنی ----
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
