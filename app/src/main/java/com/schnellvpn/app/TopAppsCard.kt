package com.schnellvpn.app

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class AppUsageItem(val label: String, val icon: Bitmap?, val totalBytes: Long, val bytesPerSec: Long)

private const val SHOWN_APPS = 3
private const val REFRESH_MS = 4000L

/** Home-screen card: the 3 apps that used the most data today, with live speed. */
@Composable
fun TopAppsCard(colors: AppColors, glass: Boolean, isDark: Boolean) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(AppUsageProvider.hasPermission(context)) }
    var items by remember { mutableStateOf<List<AppUsageItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    // Runs only while the Home tab is on screen; also notices the permission being granted in Settings.
    LaunchedEffect(Unit) {
        val cache = HashMap<Int, ResolvedApp?>()
        val myUid = context.applicationInfo.uid
        var previous: Map<Int, Long>? = null
        var previousAt = 0L

        while (true) {
            val ok = AppUsageProvider.hasPermission(context)
            granted = ok
            if (ok) {
                val now = System.currentTimeMillis()
                val before = previous
                val elapsed = now - previousAt
                val result = withContext(Dispatchers.IO) {
                    val totals = AppUsageProvider.queryTodayTotals(context)
                    val ranked = UsageMath.rank(totals, before, elapsed, myUid)
                    val shown = ArrayList<AppUsageItem>()
                    for (usage in ranked) {
                        if (shown.size >= SHOWN_APPS) break
                        val app = cache.getOrPut(usage.uid) { AppUsageProvider.describe(context, usage.uid) }
                        if (app == null) continue
                        shown.add(AppUsageItem(app.label, app.icon, usage.totalBytes, usage.bytesPerSec))
                    }
                    Pair(totals, shown)
                }
                previous = result.first
                previousAt = now
                items = result.second
                loaded = true
            }
            delay(REFRESH_MS)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .panel(colors, glass, isDark, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("پرمصرف‌ترین برنامه‌ها", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("امروز", color = colors.textDim, fontSize = 11.sp)
        }
        Spacer(Modifier.height(12.dp))

        when {
            !granted -> {
                Text(
                    "برای دیدن مصرف هر برنامه، دسترسی «Usage access» رو برای SchnellVPN روشن کن.",
                    color = colors.textDim,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .panel(colors, glass, isDark, RoundedCornerShape(14.dp), solid = colors.surface2)
                        .clickable {
                            try {
                                context.startActivity(AppUsageProvider.settingsIntent())
                            } catch (e: ActivityNotFoundException) {
                                // این گوشی صفحه‌ی Usage access نداره
                            }
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("اعطای دسترسی", color = colors.amber, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            !loaded -> Text("در حال خواندن آمار…", color = colors.textDim, fontSize = 12.sp)
            items.isEmpty() -> Text("هنوز مصرفی ثبت نشده", color = colors.textDim, fontSize = 12.sp)
            else -> {
                val maxBytes = items.maxOf { it.totalBytes }.coerceAtLeast(1L)
                items.forEachIndexed { index, item ->
                    if (index > 0) Spacer(Modifier.height(12.dp))
                    AppUsageRow(colors, item, maxBytes)
                }
            }
        }
    }
}

@Composable
private fun AppUsageRow(colors: AppColors, item: AppUsageItem, maxBytes: Long) {
    val icon = remember(item.icon) { item.icon?.asImageBitmap() }
    val fraction = (item.totalBytes.toFloat() / maxBytes.toFloat()).coerceIn(0.04f, 1f)

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
            )
        } else {
            Box(Modifier.size(36.dp).clip(CircleShape).background(colors.surface2))
        }
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                item.label,
                color = colors.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.14f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.teal)
                )
            }
        }

        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(formatBytes(item.totalBytes), color = colors.text, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            if (item.bytesPerSec > 0) {
                Text("${formatBytes(item.bytesPerSec)}/s", color = colors.teal, fontSize = 10.5.sp)
            }
        }
    }
}
