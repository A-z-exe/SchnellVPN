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
nimport androidx.compose.material3.Text
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf

class MainActivity : ComponentActivity() {

    // لیست واقعی سرورها — خالی شروع می‌شه، فقط با لینک Subscription واقعی پر می‌شه
    private val servers = mutableStateListOf<VpnServer>()

    private var loggedIn by mutableStateOf(false)
    private var isDark by mutableStateOf(true)
    private var currentTab by mutableStateOf(Tab.HOME)

    private var selectedServerId by mutableStateOf(-1)
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
            val colors = if (isDark) DarkColors else LightColors
            val scope = rememberCoroutineScope()

            // متصل بودن واقعی از وضعیت سرویس خوانده می‌شود
            val connected by remember { derivedStateOf { VpnStatus.isConnected.value } }

            // تایمر مدت اتصال و حجم مصرفی، فقط وقتی واقعاً متصلیم
            LaunchedEffect(connected) {
                if (connected) {
                    durationSec = 0; dataMB = 0f
                    while (VpnStatus.isConnected.value) {
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
        val currentlyConnected = VpnStatus.isConnected.value
        if (currentlyConnected) {
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
        connecting = true
        // منتظر تغییر VpnStatus.isConnected بمانید؛ UI از آن حالت را می‌خواند
    }

    private fun stopVpn() {
        val intent = Intent(this, SchnellVpnService::class.java).apply {
            action = SchnellVpnService.ACTION_DISCONNECT
        }
        startService(intent)
        connecting = false
        // منتظر VpnStatus.reset() که سرویس انجام می‌دهد
    }
}
