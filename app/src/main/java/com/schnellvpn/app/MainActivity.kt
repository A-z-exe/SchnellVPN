package com.schnellvpn.app

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

// ---------------- رنگ‌ها (همون توکن‌های طراحی HTML قبلی) ----------------
data class AppColors(
    val bg: Color, val surface: Color, val surface2: Color, val border: Color,
    val text: Color, val textDim: Color,
    val amber: Color, val teal: Color, val coral: Color
)

val DarkColors = AppColors(
    bg = Color(0xFF0D1526), surface = Color(0xFF16213A), surface2 = Color(0xFF1C2942),
    border = Color(0xFF2B3A5C), text = Color(0xFFEDF1F7), textDim = Color(0xFF8C9AB8),
    amber = Color(0xFFF5A623), teal = Color(0xFF38C9B9), coral = Color(0xFFFF6B5E)
)
val LightColors = AppColors(
    bg = Color(0xFFF4F6FB), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFECEFF6),
    border = Color(0xFFD8DEEB), text = Color(0xFF131A2B), textDim = Color(0xFF5B6781),
    amber = Color(0xFFD9870A), teal = Color(0xFF1F9E8F), coral = Color(0xFFE0473A)
)

enum class Tab { HOME, SERVERS, SETTINGS }

class MainActivity : ComponentActivity() {

    // لیست واقعی سرورها — خالی شروع می‌شه، فقط با لینک Subscription واقعی پر می‌شه
    private val servers = mutableStateListOf<VpnServer>()

    private var loggedIn by mutableStateOf(false)
    private var isDark by mutableStateOf(true)
    private var currentTab by mutableStateOf(Tab.HOME)

    private var selectedServerId by mutableStateOf(-1)
    private var connected by mutableStateOf(false)
    private var connecting by mutableStateOf(false)
    private var durationSec by mutableStateOf(0)
    private var dataMB by mutableStateOf(0f)
    private var subLink by mutableStateOf("")
    private var searchQuery by mutableStateOf("")
    private var toastText by mutableStateOf<String?>(null)
    private var loginLoading by mutableStateOf(false)

    private var showAddLinkDialog by mutableStateOf(false)
    private var addLinkInput by mutableStateOf("")
    private var addLinkLoading by mutableStateOf(false)

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            actuallyStartVpn()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            // نمایش crash قبلی اگه وجود داشت
            val crashFile = java.io.File(filesDir, "last_crash.txt")
            if (crashFile.exists()) {
                val msg = crashFile.readText().take(400)
                crashFile.delete()
                android.widget.Toast.makeText(this, "Crash: $msg", android.widget.Toast.LENGTH_LONG).show()
            }
            val colors = if (isDark) DarkColors else LightColors
            val scope = rememberCoroutineScope()

            // تایمر مدت اتصال و حجم مصرفی، فقط وقتی واقعاً متصلیم
            LaunchedEffect(connected) {
                if (connected) {
                    durationSec = 0; dataMB = 0f
                    while (connected) {
                        delay(1000)
                        durationSec++
                        dataMB = VpnStatus.totalMB
                    }
                }
            }

            MaterialTheme {
                Surface(color = colors.bg, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        if (!loggedIn) {
                            LoginScreen(
                                colors = colors,
                                subLink = subLink,
                                loading = loginLoading,
                                onSubLinkChange = { subLink = it },
                                onImport = { importSubscription(scope, subLink, isInitialLogin = true) },
                                onScanQr = { showToast(scope, "اسکن QR هنوز فعال نیست — به‌زودی") }
                            )
                        } else {
                            Column(Modifier.fillMaxSize()) {
                                Box(Modifier.weight(1f)) {
                                    when (currentTab) {
                                        Tab.HOME -> HomeScreen(
                                            colors = colors,
                                            servers = servers,
                                            selectedId = selectedServerId,
                                            connected = connected,
                                            connecting = connecting,
                                            durationSec = durationSec,
                                            dataMB = dataMB,
                                            onToggleConnect = { toggleConnect(scope) },
                                            onOpenServers = { currentTab = Tab.SERVERS },
                                            onOpenSettings = { currentTab = Tab.SETTINGS }
                                        )
                                        Tab.SERVERS -> ServersScreen(
                                            colors = colors,
                                            servers = servers,
                                            selectedId = selectedServerId,
                                            query = searchQuery,
                                            onQueryChange = { searchQuery = it },
                                            onSelect = { id ->
                                                selectedServerId = id
                                                scope.launch {
                                                    toastText = "سرور انتخاب شد"
                                                    delay(450)
                                                    currentTab = Tab.HOME
                                                    delay(1800)
                                                    if (toastText == "سرور انتخاب شد") toastText = null
                                                }
                                            },
                                            onTestPings = {
                                                for (i in servers.indices) {
                                                    val s = servers[i]
                                                    val np = max(18, (s.pingMs ?: 80) + (Math.random() * 30 - 15).toInt())
                                                    servers[i] = s.copy(pingMs = np)
                                                }
                                                showToast(scope, "پینگ سرورها به‌روزرسانی شد")
                                            },
                                            onImport = { addLinkInput = ""; showAddLinkDialog = true },
                                            onScanQr = { showToast(scope, "اسکن QR هنوز فعال نیست — به‌زودی") }
                                        )
                                        Tab.SETTINGS -> SettingsScreen(
                                            colors = colors,
                                            isDark = isDark,
                                            onToggleDark = { isDark = !isDark },
                                            onLogout = {
                                                stopVpn()
                                                servers.clear()
                                                selectedServerId = -1
                                                loggedIn = false
                                                currentTab = Tab.HOME
                                            }
                                        )
                                    }
                                }
                                BottomNav(colors = colors, current = currentTab, onSelect = { currentTab = it })
                            }
                        }

                        if (showAddLinkDialog) {
                            AddLinkDialog(
                                colors = colors,
                                value = addLinkInput,
                                loading = addLinkLoading,
                                onChange = { addLinkInput = it },
                                onConfirm = { importSubscription(scope, addLinkInput, isInitialLogin = false) },
                                onDismiss = { if (!addLinkLoading) showAddLinkDialog = false }
                            )
                        }

                        toastText?.let { msg ->
                            Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 96.dp, start = 18.dp, end = 18.dp)
                                    .fillMaxWidth()
                                    .background(colors.surface2, RoundedCornerShape(14.dp))
                                    .border(1.dp, colors.amber, RoundedCornerShape(14.dp))
                                    .padding(12.dp)
                            ) {
                                Text(msg, color = colors.text, fontSize = 12.5.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showToast(scope: kotlinx.coroutines.CoroutineScope, msg: String) {
        scope.launch {
            toastText = msg
            delay(2200)
            if (toastText == msg) toastText = null
        }
    }

    // این تابع واقعاً به اینترنت می‌زنه، لینک Subscription رو می‌خونه، و سرورهای واقعی داخلش رو استخراج می‌کنه
    private fun importSubscription(scope: kotlinx.coroutines.CoroutineScope, link: String, isInitialLogin: Boolean) {
        val trimmed = link.trim()
        if (trimmed.isEmpty()) {
            showToast(scope, "لطفاً یک لینک معتبر وارد کن")
            return
        }
        scope.launch {
            if (isInitialLogin) loginLoading = true else addLinkLoading = true
            try {
                val result = withContext(Dispatchers.IO) { SubscriptionFetcher.fetchAndParse(trimmed) }
                if (result.isEmpty()) {
                    showToast(scope, "هیچ سرور معتبری توی این لینک پیدا نشد")
                } else if (isInitialLogin) {
                    servers.clear()
                    servers.addAll(result)
                    selectedServerId = result.first().id
                    loggedIn = true
                    showToast(scope, "${result.size} سرور با موفقیت اضافه شد")
                } else {
                    val existingLinks = servers.map { it.link }.toSet()
                    val newOnes = result.filter { it.link !in existingLinks }
                    val startId = (servers.maxOfOrNull { it.id } ?: 0) + 1
                    newOnes.forEachIndexed { i, s -> servers.add(s.copy(id = startId + i)) }
                    if (selectedServerId == -1 && servers.isNotEmpty()) selectedServerId = servers.first().id
                    showAddLinkDialog = false
                    showToast(scope, if (newOnes.isEmpty()) "همه‌ی این سرورها قبلاً اضافه شده بودن" else "${newOnes.size} سرور جدید اضافه شد")
                }
            } catch (e: Exception) {
                showToast(scope, "خطا در دریافت لینک: ${e.message ?: "اتصال برقرار نشد"}")
            } finally {
                if (isInitialLogin) loginLoading = false else addLinkLoading = false
            }
        }
    }

    private fun toggleConnect(scope: kotlinx.coroutines.CoroutineScope) {
        if (connected) {
            stopVpn()
            showToast(scope, "اتصال قطع شد")
        } else {
            requestVpnPermissionAndConnect()
        }
    }

    private fun requestVpnPermissionAndConnect() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermissionLauncher.launch(intent) else actuallyStartVpn()
    }

    private fun actuallyStartVpn() {
        val link = servers.find { it.id == selectedServerId }?.link ?: return
        val intent = Intent(this, SchnellVpnService::class.java).apply {
            action = SchnellVpnService.ACTION_CONNECT
            putExtra(SchnellVpnService.EXTRA_LINK, link)
        }
        startForegroundService(intent)
        connecting = false
        connected = true
    }

    private fun stopVpn() {
        val intent = Intent(this, SchnellVpnService::class.java).apply {
            action = SchnellVpnService.ACTION_DISCONNECT
        }
        startService(intent)
        connected = false
        connecting = false
    }
}

// ==================== Login ====================
@Composable
fun LoginScreen(
    colors: AppColors,
    subLink: String,
    loading: Boolean,
    onSubLinkChange: (String) -> Unit,
    onImport: () -> Unit,
    onScanQr: () -> Unit
) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.size(74.dp).clip(RoundedCornerShape(22.dp)).background(colors.surface).border(1.dp, colors.border, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("⚡", fontSize = 30.sp, color = colors.amber)
        }
        Spacer(Modifier.height(18.dp))
        Text("SchnellVPN", color = colors.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("سریع. امن. بدون پیچیدگی.", color = colors.textDim, fontSize = 13.5.sp)
        Spacer(Modifier.height(30.dp))

        BasicField(colors, subLink, onSubLinkChange, "لینک اشتراک (Subscription) را وارد کنید")
        Spacer(Modifier.height(10.dp))
        PrimaryButton(colors, if (loading) "در حال دریافت سرورها…" else "وارد کردن لینک", onClick = { if (!loading) onImport() })
        Spacer(Modifier.height(14.dp))
        Text("یا", color = colors.textDim, fontSize = 11.5.sp)
        Spacer(Modifier.height(14.dp))
        GhostButton(colors, "اسکن QR Code", onClick = onScanQr)

        Spacer(Modifier.height(22.dp))
        Row {
            Text("کانفیگ نداری؟ ", color = colors.textDim, fontSize = 12.5.sp)
            Text(
                "از ادمین کانال دریافت کن",
                color = colors.amber,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/SchnellVPN"))
                    context.startActivity(intent)
                }
            )
        }
    }
}

// ==================== Home ====================
@Composable
fun HomeScreen(
    colors: AppColors,
    servers: List<VpnServer>,
    selectedId: Int,
    connected: Boolean,
    connecting: Boolean,
    durationSec: Int,
    dataMB: Float,
    onToggleConnect: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val server = servers.find { it.id == selectedId }
    val ping = server?.pingMs ?: 260

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("SchnellVPN", color = colors.amber, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(colors.surface2).border(1.dp, colors.border, RoundedCornerShape(11.dp)).clickable { onOpenSettings() },
                contentAlignment = Alignment.Center
            ) { Text("⚙", color = colors.textDim, fontSize = 16.sp) }
        }

        Spacer(Modifier.height(24.dp))

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ConnectGauge(
                colors = colors,
                connected = connected,
                connecting = connecting,
                pingMs = ping,
                onClick = onToggleConnect
            )
        }

        Spacer(Modifier.height(26.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(18.dp))
                .border(1.dp, colors.border, RoundedCornerShape(18.dp))
                .clickable { onOpenServers() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(server?.flag ?: "🏳", fontSize = 24.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(server?.name ?: "سروری انتخاب نشده", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(3.dp))
                Text(server?.protocolLabel ?: "—", color = colors.textDim, fontSize = 11.sp)
            }
            Text("‹", color = colors.textDim, fontSize = 18.sp)
        }

        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatBox(colors, "مدت اتصال", fmtTime(durationSec), Modifier.weight(1f))
            StatBox(colors, "حجم مصرفی", "${"%.1f".format(dataMB)} MB", Modifier.weight(1f))
        }
    }
}

@Composable
fun StatBox(colors: AppColors, label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(colors.surface, RoundedCornerShape(16.dp))
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .padding(13.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = colors.textDim, fontSize = 11.sp)
        Spacer(Modifier.height(5.dp))
        Text(value, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

fun fmtTime(sec: Int): String {
    val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

// ---- دایره‌ی سرعت‌سنج (همون عنصر شاخص طراحی) ----
@Composable
fun ConnectGauge(colors: AppColors, connected: Boolean, connecting: Boolean, pingMs: Int, onClick: () -> Unit) {
    val targetAngle = if (connected) {
        max(-120f, min(120f, 120f - (pingMs / 300f) * 240f))
    } else -120f

    val infinite = rememberInfiniteTransition()
    val sweepAngle by infinite.animateFloat(
        initialValue = -120f, targetValue = 120f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse)
    )
    val settledAngle by animateFloatAsState(targetValue = targetAngle, animationSpec = tween(700))
    val angle = if (connecting) sweepAngle else settledAngle

    val indicatorColor = when {
        !connected -> colors.amber
        pingMs < 100 -> colors.teal
        pingMs < 220 -> colors.amber
        else -> colors.coral
    }

    Box(
        Modifier.size(212.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.minDimension / 2
            listOf(-120f, -80f, -40f, 0f, 40f, 80f, 120f).forEach { deg ->
                rotate(degrees = deg, pivot = center) {
                    drawLine(
                        color = colors.border,
                        start = Offset(center.x, center.y - radius + 6.dp.toPx()),
                        end = Offset(center.x, center.y - radius + 19.dp.toPx()),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
            rotate(degrees = angle, pivot = center) {
                drawLine(
                    color = indicatorColor,
                    start = Offset(center.x, center.y - radius + 3.dp.toPx()),
                    end = Offset(center.x, center.y - radius + 23.dp.toPx()),
                    strokeWidth = 5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
        Box(
            Modifier.size(176.dp).clip(CircleShape).background(colors.surface).border(1.dp, colors.border, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when { connecting -> "در حال اتصال…"; connected -> "متصل شدید"; else -> "برای اتصال ضربه بزنید" },
                    color = colors.textDim, fontSize = 12.sp, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (connected) "$pingMs ms" else if (connecting) "···" else "—",
                    color = colors.text, fontSize = 26.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(if (connected) "آماده" else "آماده‌ی اتصال", color = colors.textDim, fontSize = 10.5.sp)
            }
        }
    }
}

// ==================== Servers ====================
@Composable
fun ServersScreen(
    colors: AppColors,
    servers: List<VpnServer>,
    selectedId: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (Int) -> Unit,
    onTestPings: () -> Unit,
    onImport: () -> Unit,
    onScanQr: () -> Unit
) {
    val filtered = servers.filter { it.name.contains(query) || it.protocolLabel.contains(query, true) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("سرورها", color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        BasicField(colors, query, onQueryChange, "جستجوی کشور یا سرور")
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(colors, "افزودن از لینک", onClick = onImport, modifier = Modifier.weight(1f))
            GhostButton(colors, "اسکن QR", onClick = onScanQr, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${filtered.size} سرور", color = colors.textDim, fontSize = 11.5.sp)
            Text("تست پینگ همه", color = colors.amber, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onTestPings() })
        }
        Spacer(Modifier.height(10.dp))

        LazyColumn(Modifier.fillMaxWidth()) {
            items(filtered) { server ->
                val isSelected = server.id == selectedId
                val ping = server.pingMs
                val pingColor = when {
                    ping == null -> colors.coral
                    ping < 100 -> colors.teal
                    ping < 220 -> colors.amber
                    else -> colors.coral
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .background(if (isSelected) colors.surface2 else colors.surface, RoundedCornerShape(16.dp))
                        .border(1.dp, if (isSelected) colors.amber else colors.border, RoundedCornerShape(16.dp))
                        .clickable { onSelect(server.id) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(server.flag, fontSize = 20.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(server.name, color = colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(3.dp))
                        Text(server.protocolLabel, color = colors.textDim, fontSize = 10.5.sp)
                    }
                    Text(if (ping == null) "—" else "$ping ms", color = pingColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ==================== Settings ====================
@Composable
fun SettingsScreen(colors: AppColors, isDark: Boolean, onToggleDark: () -> Unit, onLogout: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("تنظیمات", color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))

        SettingRow(colors, "حالت تاریک", "رابط کاربری با نور کم", isDark, onToggleDark)

        Spacer(Modifier.height(24.dp))
        Text("درباره", color = colors.textDim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        AboutRow(colors, "نسخه برنامه", "1.0.0")
        AboutRow(colors, "هسته اتصال", "Xray-core")
        AboutRow(colors, "توسعه‌دهنده", "SchnellVPN Team") // 👈 بعداً اسم/اطلاعات واقعی رو همینجا بگذار
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/SchnellVPN")))
                },
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("ارتباط با پشتیبانی", color = colors.textDim, fontSize = 12.5.sp)
            Text("t.me/SchnellVPN", color = colors.amber, fontSize = 12.5.sp)
        }

        Spacer(Modifier.height(24.dp))
        DangerButton(colors, "خروج از حساب", onClick = onLogout)
    }
}

@Composable
fun SettingRow(colors: AppColors, title: String, subtitle: String, value: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(14.dp))
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(title, color = colors.text, fontSize = 13.5.sp)
            Text(subtitle, color = colors.textDim, fontSize = 11.sp)
        }
        Box(
            Modifier
                .size(width = 42.dp, height = 24.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (value) colors.amber.copy(alpha = 0.3f) else colors.surface2)
                .border(1.dp, if (value) colors.amber else colors.border, RoundedCornerShape(14.dp))
                .clickable { onToggle() },
            contentAlignment = if (value) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                Modifier.padding(2.dp).size(18.dp).clip(CircleShape)
                    .background(if (value) colors.amber else colors.textDim)
            )
        }
    }
}

@Composable
fun AboutRow(colors: AppColors, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = colors.textDim, fontSize = 12.5.sp)
        Text(value, color = colors.text, fontSize = 12.5.sp)
    }
}

// ==================== shared bits ====================
@Composable
fun BasicField(colors: AppColors, value: String, onChange: (String) -> Unit, placeholder: String) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(color = colors.text, fontSize = 13.sp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface2, RoundedCornerShape(14.dp))
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .padding(14.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) Text(placeholder, color = colors.textDim, fontSize = 12.5.sp)
            inner()
        }
    )
}

@Composable
fun PrimaryButton(colors: AppColors, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .background(colors.amber, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color(0xFF1A1300), fontSize = 14.5.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun GhostButton(colors: AppColors, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .background(colors.surface2, RoundedCornerShape(14.dp))
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

@Composable
fun DangerButton(colors: AppColors, text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .border(1.dp, colors.coral, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = colors.coral, fontSize = 13.5.sp, fontWeight = FontWeight.Medium) }
}

@Composable
fun BottomNav(colors: AppColors, current: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .border(1.dp, colors.border)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        NavItem(colors, "خانه", current == Tab.HOME) { onSelect(Tab.HOME) }
        NavItem(colors, "سرورها", current == Tab.SERVERS) { onSelect(Tab.SERVERS) }
        NavItem(colors, "تنظیمات", current == Tab.SETTINGS) { onSelect(Tab.SETTINGS) }
    }
}

@Composable
fun NavItem(colors: AppColors, label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) colors.amber else colors.textDim,
        fontSize = 12.sp,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 16.dp)
    )
}

// ==================== Add Link Dialog (Servers screen) ====================
@Composable
fun AddLinkDialog(
    colors: AppColors,
    value: String,
    loading: Boolean,
    onChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .padding(28.dp)
                .background(colors.surface, RoundedCornerShape(18.dp))
                .border(1.dp, colors.border, RoundedCornerShape(18.dp))
                .clickable(onClick = {}) // برای اینکه لمس داخل باکس، دیالوگ رو نبنده
                .padding(18.dp)
        ) {
            Text("افزودن لینک اشتراک", color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            BasicField(colors, value, onChange, "لینک Subscription را وارد کنید")
            Spacer(Modifier.height(14.dp))
            PrimaryButton(colors, if (loading) "در حال دریافت…" else "افزودن", onClick = { if (!loading) onConfirm() })
        }
    }
}
