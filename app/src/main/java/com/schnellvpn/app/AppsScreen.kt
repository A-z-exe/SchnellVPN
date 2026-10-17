package com.schnellvpn.app

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.style.TextAlign
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
 * صفحه‌ی اپ‌ها با دو حالت:
 *  - «خارج از VPN»: اپ‌هایی که نباید از VPN عبور کنند (Split tunneling)
 *  - «روشن‌شدن VPN با باز شدن اپ»: اپ‌هایی که وقتی VPN خاموش است و باز می‌شوند، VPN خودکار وصل شود.
 */
@Composable
fun AppsScreen(
    colors: AppColors,
    glass: Boolean,
    isDark: Boolean,
    excluded: Set<String>,
    triggers: Set<String>,
    onToggleExcluded: (String) -> Unit,
    onToggleTrigger: (String) -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var mode by remember { mutableStateOf(0) } // 0 = exclude, 1 = trigger
    var refresh by remember { mutableStateOf(0) }

    val accessibilityEnabled = remember(refresh, mode) {
        NetworkUtils.isAccessibilityEnabled(context)
    }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val self = context.packageName
            val result = ArrayList<InstalledApp>()
            val list = try { pm.getInstalledApplications(0) } catch (e: Exception) { emptyList() }
            for (info in list) {
                if (info.packageName == self) continue
                val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
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

    val activeSet = if (mode == 0) excluded else triggers
    val activeCount = activeSet.size

    GlassBackground(isDark = isDark, enabled = glass) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text("مدیریت اپ‌ها", color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip(colors, "خارج از VPN", mode == 0, Modifier.weight(1f)) { mode = 0 }
                ModeChip(colors, "باز شدن اپ → روشن شدن VPN", mode == 1, Modifier.weight(1f)) { mode = 1 }
            }
            Spacer(Modifier.height(10.dp))

            Text(
                if (mode == 0) {
                    "اپ‌هایی که تیک می‌زنی از VPN عبور نمی‌کنند (مثلاً همراه بانک). انتخاب‌شده: $activeCount"
                } else {
                    "اپ‌هایی که با باز شدنشان، اگر VPN خاموش باشد خودکار روشن می‌شود. انتخاب‌شده: $activeCount"
                },
                color = colors.textDim, fontSize = 11.5.sp
            )

            if (mode == 1) {
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.surface2, RoundedCornerShape(14.dp))
                        .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        if (accessibilityEnabled) "سرویس دسترسی فعال است ✓"
                        else "برای این قابلیت، «سرویس دسترسی» باید فعال شود.",
                        color = if (accessibilityEnabled) colors.teal else colors.coral,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "VPN با پروفایل و سرور فعالِ فعلی روشن می‌شود.",
                        color = colors.textDim, fontSize = 11.sp
                    )
                    if (!accessibilityEnabled) {
                        Spacer(Modifier.height(8.dp))
                        GhostButton(colors, "باز کردن تنظیمات دسترسی", onClick = onOpenAccessibilitySettings)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "بررسی مجدد",
                        color = colors.amber, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { refresh++ }.padding(vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
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
                            checked = activeSet.contains(app.packageName),
                            onToggle = {
                                if (mode == 0) onToggleExcluded(app.packageName)
                                else onToggleTrigger(app.packageName)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeChip(
    colors: AppColors,
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .background(
                if (active) colors.amber.copy(alpha = 0.25f) else colors.surface2,
                RoundedCornerShape(12.dp)
            )
            .border(1.dp, if (active) colors.amber else colors.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (active) colors.amber else colors.text,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center
        )
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
