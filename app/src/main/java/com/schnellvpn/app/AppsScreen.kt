package com.schnellvpn.app

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Bitmap?
)

/**
 * صفحه‌ی «اپ‌های خارج از VPN» (Split tunneling):
 * اپ‌هایی که اینجا تیک می‌زنند از VPN عبور نمی‌کنند (مثلاً همراه بانک‌ها).
 */
@Composable
fun AppsScreen(
    colors: AppColors,
    glass: Boolean,
    isDark: Boolean,
    excluded: Set<String>,
    onToggle: (String) -> Unit
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val self = context.packageName
            val result = ArrayList<InstalledApp>()
            val list = try { pm.getInstalledApplications(0) } catch (e: Exception) { emptyList() }
            for (info in list) {
                if (info.packageName == self) continue
                val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                // اپ‌های سیستمی بدون آیکون لانچر را رد کن تا لیست شلوغ نشود
                if (isSystem && pm.getLaunchIntentForPackage(info.packageName) == null) continue
                val label = try { pm.getApplicationLabel(info).toString() } catch (e: Exception) { info.packageName }
                val icon: Bitmap? = try { pm.getApplicationIcon(info).toBitmap(96, 96) } catch (e: Exception) { null }
                result.add(InstalledApp(info.packageName, label, icon))
            }
            result.sortBy { it.label.lowercase() }
            result
        }
        loading = false
    }

    GlassBackground(isDark = isDark, enabled = glass) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text("اپ‌های خارج از VPN", color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "اپ‌هایی که تیک می‌زنی از VPN عبور نمی‌کنند (مثلاً همراه بانک). انتخاب‌شده: ${excluded.size}",
                color = colors.textDim, fontSize = 11.5.sp
            )
            Spacer(Modifier.height(12.dp))
            if (loading) {
                Text("در حال خواندن لیست اپ‌ها…", color = colors.textDim, fontSize = 12.sp)
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(apps, key = { it.packageName }) { app ->
                        AppRow(
                            colors = colors,
                            glass = glass,
                            isDark = isDark,
                            app = app,
                            checked = excluded.contains(app.packageName),
                            onToggle = { onToggle(app.packageName) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    colors: AppColors,
    glass: Boolean,
    isDark: Boolean,
    app: InstalledApp,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .panel(colors, glass, isDark, RoundedCornerShape(14.dp), solid = colors.surface)
            .clickable { onToggle() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = remember(app.icon) { app.icon?.asImageBitmap() }
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
            )
        } else {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(colors.surface2))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.label, color = colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                app.packageName, color = colors.textDim, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(if (checked) colors.amber else colors.surface2)
                .border(1.dp, if (checked) colors.amber else colors.border, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Text("✓", color = Color(0xFF1A1300), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
