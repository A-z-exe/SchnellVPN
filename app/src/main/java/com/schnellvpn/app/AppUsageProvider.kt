package com.schnellvpn.app

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.graphics.drawable.toBitmap

class ResolvedApp(val packageName: String, val label: String, val icon: Bitmap?)

/**
 * Per-app data usage from Android's own statistics (NetworkStatsManager).
 * Needs the special "Usage access" permission, which the user grants in system settings.
 */
object AppUsageProvider {

    @Suppress("DEPRECATION")
    fun hasPermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** uid -> rx+tx bytes during the last 24 hours (Wi-Fi + mobile). Empty map on any failure. */
    @Suppress("DEPRECATION")
    fun queryLast24h(context: Context): Map<Int, Long> {
        val manager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
            ?: return emptyMap()

        val end = System.currentTimeMillis()
        val start = end - 24L * 60L * 60L * 1000L

        val totals = HashMap<Int, Long>()
        // Mobile data with a null subscriberId is only reported on Android 10+; Wi-Fi works everywhere.
        for (type in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
            try {
                val stats = manager.querySummary(type, null, start, end)
                try {
                    val bucket = NetworkStats.Bucket()
                    while (stats.hasNextBucket()) {
                        stats.getNextBucket(bucket)
                        totals[bucket.uid] = (totals[bucket.uid] ?: 0L) + bucket.rxBytes + bucket.txBytes
                    }
                } finally {
                    stats.close()
                }
            } catch (e: Exception) {
                // no permission / no data for this transport → ignore
            }
        }
        return totals
    }

    /** Name + icon of the app that owns [uid]; null if it can't be resolved. */
    @Suppress("DEPRECATION")
    fun describe(context: Context, uid: Int): ResolvedApp? {
        val pm = context.packageManager
        val packages: Array<String>? = try {
            pm.getPackagesForUid(uid)
        } catch (e: Exception) {
            null
        }
        if (packages == null || packages.isEmpty()) return null

        val pkg = packages.firstOrNull { pm.getLaunchIntentForPackage(it) != null } ?: packages[0]
        return try {
            val info = pm.getApplicationInfo(pkg, 0)
            val label = pm.getApplicationLabel(info).toString()
            val icon: Bitmap? = try {
                pm.getApplicationIcon(info).toBitmap(96, 96)
            } catch (e: Exception) {
                null
            }
            ResolvedApp(pkg, label, icon)
        } catch (e: Exception) {
            null
        }
    }
}
