package com.schnellvpn.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
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
    private var glass by mutableStateOf(true)
    private var refreshing by mutableStateOf(false)

    // پروفایل‌های مستقل (پروفایل ۱، ۲، ۳ …) — هر کدام سرورها و اشتراک خودش را دارد
    private val profiles = mutableStateListOf<ServerProfile>()
    private var activeProfileId by mutableStateOf(-1)
    private var showSplash by mutableStateOf(true)

    private var showAddLinkDialog by mutableStateOf(false)
    private var addLinkInput by mutableStateOf("")
    private var addLinkLoading by mutableStateOf(false)

    // ==================== مجوز VPN ====================
    private var pendingLink: String? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingLink?.let { startVpnService(it) }
        } else {
            // کاربر مجوز رو رد کرد (انصراف/دنای)
            toastText = "مجوز VPN داده نشد — دوباره تلاش کن"
            lifecycleScope.launch {
                delay(2600)
                if (toastText == "مجوز VPN داده نشد — دوباره تلاش کن") toastText = null
            }
        }
        pendingLink = null
    }

    // ==================== اسکن QR ====================
    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents
        if (text != null) onQrScanned(text)
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchScanner() else showToast(lifecycleScope, "برای اسکن QR باید دسترسی دوربین رو بدی")
    }

    // Android 13+: without this the VPN notification (and its Disconnect button) is hidden.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* the VPN works either way; nothing to do */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // نمایش crash قبلی (یک‌بار، خارج از composition)
        val crashFile = java.io.File(filesDir, "last_crash.txt")
        if (crashFile.exists()) {
            val msg = runCatching { crashFile.readText().take(400) }.getOrDefault("")
            crashFile.delete()
            if (msg.isNotEmpty()) {
                android.widget.Toast.makeText(this, "Crash: $msg", android.widget.Toast.LENGTH_LONG).show()
            }
        }

        // بارگذاری پروفایل‌ها از حافظه (با مهاجرت از نسخه‌ی تک‌لیستی قدیمی)
        val savedProfiles = ProfileStore.load(this)
        if (savedProfiles.isNotEmpty()) {
            profiles.addAll(savedProfiles)
            val savedActive = ProfileStore.loadActiveId(this)
            activeProfileId = if (savedProfiles.any { it.id == savedActive }) savedActive
            else savedProfiles.first().id
        } else {
            val legacyServers = ProfileManager.loadServers(this)
            if (legacyServers.isNotEmpty()) {
                profiles.add(
                    ServerProfile(
                        id = 1,
                        name = "پروفایل 1",
                        servers = legacyServers,
                        selectedServerId = ProfileManager.loadSelectedServerId(this),
                        subscriptionUrls = ProfileManager.loadSubscriptionUrls(this)
                    )
                )
                activeProfileId = 1
                ProfileStore.save(this, profiles.toList())
                ProfileStore.saveActiveId(this, 1)
            }
        }
        val active = profiles.find { it.id == activeProfileId }
        if (active != null) {
            servers.addAll(active.servers)
            selectedServerId = active.selectedServerId
                .takeIf { id -> active.servers.any { it.id == id } }
                ?: active.servers.firstOrNull()?.id ?: -1
            subLink = active.subscriptionUrls.firstOrNull() ?: ""
        }
        if (profiles.isNotEmpty()) loggedIn = true
        glass = ProfileManager.loadGlass(this)

        setContent {
            val colors = if (isDark) DarkColors else LightColors
            val scope = rememberCoroutineScope()

            // sync وضعیت اتصال با سرویس — منبع حقیقت: VpnStatus (نه متغیر محلی UI)
            LaunchedEffect(Unit) {
                while (true) {
                    val svc = VpnStatus.isConnected.value
                    val svcConnecting = VpnStatus.isConnecting.value
                    if (connected != svc) {
                        connected = svc
                        // هر زمان وضعیت نهایی شد، «در حال اتصال» باید پاک شود
                        connecting = false
                        if (!svc) { durationSec = 0; dataMB = 0f }
                    }
                    if (svcConnecting != connecting) connecting = svcConnecting
                    VpnStatus.lastError.value?.let { err ->
                        connecting = false
                        toastText = "خطا: $err"
                        VpnStatus.setLastError(null)
                    }
                    delay(500)
                }
            }

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

            // پنهان کردن اسپلش بعد از انیمیشن ورود لوگو
            LaunchedEffect(Unit) {
                delay(1900)
                showSplash = false
            }

            MaterialTheme {
                Surface(color = colors.bg, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        if (showSplash) {
                            SplashScreen(colors)
                        } else if (!loggedIn) {
                            LoginScreen(
                                colors = colors,
                                subLink = subLink,
                                loading = loginLoading,
                                glass = glass,
                                isDark = isDark,
                                onSubLinkChange = { subLink = it },
                                onImport = { importSubscription(scope, subLink, isInitialLogin = true) },
                                onScanQr = { startQrScan() }
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
                                            glass = glass,
                                            isDark = isDark,
                                            profiles = profiles,
                                            activeProfileId = activeProfileId,
                                            onSwitchProfile = { switchProfile(it) },
                                            onToggleConnect = { toggleConnect(scope) },
                                            onOpenServers = { currentTab = Tab.SERVERS },
                                            onOpenSettings = { currentTab = Tab.SETTINGS }
                                        )
                                        Tab.SERVERS -> ServersScreen(
                                            colors = colors,
                                            servers = servers,
                                            selectedId = selectedServerId,
                                            query = searchQuery,
                                            refreshing = refreshing,
                                            glass = glass,
                                            isDark = isDark,
                                            profiles = profiles,
                                            activeProfileId = activeProfileId,
                                            onSwitchProfile = { switchProfile(it) },
                                            onRefresh = { refreshSubscriptions(scope) },
                                            onQueryChange = { searchQuery = it },
                                            onSelect = { id ->
                                                selectedServerId = id
                                                syncActiveProfile()
                                                scope.launch {
                                                    toastText = "سرور انتخاب شد"
                                                    delay(450)
                                                    currentTab = Tab.HOME
                                                    delay(1800)
                                                    if (toastText == "سرور انتخاب شد") toastText = null
                                                }
                                            },
                                            onTestPings = {
                                                scope.launch {
                                                    showToast(scope, "در حال تست پینگ…")
                                                    val results = PingTester.pingAll(servers.toList())
                                                    for (i in servers.indices) {
                                                        val s = servers[i]
                                                        servers[i] = s.copy(pingMs = results[s.id])
                                                    }
                                                    syncActiveProfile()
                                                    val ok = results.values.count { it != null }
                                                    showToast(scope, "پینگ: $ok از ${results.size} سرور پاسخ دادند")
                                                }
                                            },
                                            onImport = { addLinkInput = ""; showAddLinkDialog = true },
                                            onScanQr = { startQrScan() }
                                        )
                                        Tab.SETTINGS -> SettingsScreen(
                                            colors = colors,
                                            isDark = isDark,
                                            onToggleDark = { isDark = !isDark },
                                            glass = glass,
                                            onToggleGlass = {
                                                glass = !glass
                                                ProfileManager.saveGlass(this@MainActivity, glass)
                                            },
                                            profiles = profiles,
                                            activeProfileId = activeProfileId,
                                            onSwitchProfile = { switchProfile(it) },
                                            onDeleteProfile = { deleteProfile(it) },
                                            onLogout = {
                                                stopVpn()
                                                ProfileManager.clearServers(this@MainActivity)
                                                ProfileManager.clearSubscriptionUrls(this@MainActivity)
                                                ProfileStore.clear(this@MainActivity)
                                                profiles.clear()
                                                activeProfileId = -1
                                                subLink = ""
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

    // ==================== پروفایل‌ها ====================
    private fun activeProfile(): ServerProfile? = profiles.find { it.id == activeProfileId }

    private fun replaceProfile(updated: ServerProfile) {
        val i = profiles.indexOfFirst { it.id == updated.id }
        if (i >= 0) profiles[i] = updated
    }

    private fun persistProfiles() {
        ProfileStore.save(this, profiles.toList())
        ProfileStore.saveActiveId(this, activeProfileId)
    }

    /** اگر هیچ پروفایلی نیست، یک پروفایل خالی می‌سازد. */
    private fun ensureProfile() {
        if (profiles.isEmpty()) {
            profiles.add(ServerProfile(1, "پروفایل 1", emptyList(), -1, emptyList()))
            activeProfileId = 1
            persistProfiles()
        } else if (activeProfile() == null) {
            activeProfileId = profiles.first().id
        }
    }

    /** لیست فعلی سرورها/سرور انتخابی را در پروفایل فعال ذخیره می‌کند. */
    private fun syncActiveProfile() {
        val p = activeProfile()
        if (p != null) {
            replaceProfile(p.copy(servers = servers.toList(), selectedServerId = selectedServerId))
        } else if (servers.isNotEmpty()) {
            profiles.add(ServerProfile(1, "پروفایل 1", servers.toList(), selectedServerId, emptyList()))
            activeProfileId = 1
        }
        persistProfiles()
    }

    private fun switchProfile(id: Int) {
        if (id == activeProfileId) return
        if (connected || connecting) stopVpn()
        syncActiveProfile()
        val p = profiles.find { it.id == id } ?: return
        activeProfileId = id
        servers.clear(); servers.addAll(p.servers)
        selectedServerId = p.selectedServerId
            .takeIf { sid -> p.servers.any { it.id == sid } }
            ?: p.servers.firstOrNull()?.id ?: -1
        persistProfiles()
    }

    private fun deleteProfile(id: Int) {
        if (connected || connecting) stopVpn()
        val before = profiles.size
        profiles.removeAll { it.id == id }
        if (profiles.size == before) return
        if (activeProfileId == id) {
            activeProfileId = profiles.firstOrNull()?.id ?: -1
            val p = activeProfile()
            servers.clear()
            if (p != null) servers.addAll(p.servers)
            selectedServerId = p?.selectedServerId ?: -1
        }
        persistProfiles()
        if (profiles.isEmpty()) loggedIn = false
    }

    /**
     * افزودن یک لینک:
     * - لینک مستقیم کانفیگ (vless/vmess/trojan/ss) → به پروفایل فعلی اضافه می‌شود (بدون اینترنت).
     * - لینک اشتراک (http/https) → یک پروفایل جدید می‌سازد (پروفایل ۱، ۲، ۳ …).
     */
    private fun importSubscription(scope: kotlinx.coroutines.CoroutineScope, link: String, isInitialLogin: Boolean) {
        val trimmed = link.trim()
        if (trimmed.isEmpty()) {
            showToast(scope, "لطفاً یک لینک معتبر وارد کن")
            return
        }
        scope.launch {
            if (isInitialLogin) loginLoading = true else addLinkLoading = true
            try {
                val isHttp = trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true)

                if (!isHttp) {
                    // کانفیگ مستقیم — بدون نیاز به اینترنت
                    val parsed = withContext(Dispatchers.IO) { SubscriptionFetcher.parseContent(trimmed) }
                    if (parsed.isEmpty()) {
                        showToast(scope, "این لینک کانفیگ معتبر نیست")
                        return@launch
                    }
                    ensureProfile()
                    val before = servers.size
                    val merged = ServerListOps.addUnique(servers.toList(), parsed, ServerListOps.MANUAL)
                    val added = merged.size - before
                    servers.clear(); servers.addAll(merged)
                    if (servers.none { it.id == selectedServerId } && servers.isNotEmpty()) selectedServerId = servers.first().id
                    syncActiveProfile()
                    loggedIn = true
                    showAddLinkDialog = false
                    showToast(scope, if (added == 0) "این کانفیگ قبلاً اضافه شده بود" else "$added کانفیگ به پروفایل فعلی اضافه شد")
                } else {
                    // اشتراک
                    val result = withContext(Dispatchers.IO) { SubscriptionFetcher.fetchAndParse(trimmed) }
                    if (result.isEmpty()) {
                        showToast(scope, "هیچ سرور معتبری توی این لینک پیدا نشد")
                        return@launch
                    }
                    val numbered = ServerListOps.renumber(result.map { it.copy(source = trimmed) }, null).first
                    if (isInitialLogin && profiles.isEmpty()) {
                        profiles.add(ServerProfile(1, "پروفایل 1", numbered, numbered.firstOrNull()?.id ?: -1, listOf(trimmed)))
                        activeProfileId = 1
                        servers.clear(); servers.addAll(numbered)
                        selectedServerId = numbered.firstOrNull()?.id ?: -1
                        loggedIn = true
                        persistProfiles()
                        showToast(scope, "${result.size} سرور با موفقیت اضافه شد")
                    } else {
                        // هر اشتراک جدید = یک پروفایل جدید
                        if (connected || connecting) stopVpn()
                        val newId = (profiles.maxOfOrNull { it.id } ?: 0) + 1
                        profiles.add(
                            ServerProfile(
                                id = newId,
                                name = "پروفایل $newId",
                                servers = numbered,
                                selectedServerId = numbered.firstOrNull()?.id ?: -1,
                                subscriptionUrls = listOf(trimmed)
                            )
                        )
                        activeProfileId = newId
                        servers.clear(); servers.addAll(numbered)
                        selectedServerId = numbered.firstOrNull()?.id ?: -1
                        persistProfiles()
                        showAddLinkDialog = false
                        showToast(scope, "پروفایل $newId با ${numbered.size} سرور ساخته شد")
                    }
                }
            } catch (e: Exception) {
                showToast(scope, "خطا در دریافت لینک: ${e.message ?: "اتصال برقرار نشد"}")
            } finally {
                if (isInitialLogin) loginLoading = false else addLinkLoading = false
            }
        }
    }

    // ==================== بروزرسانی اشتراک + پینگ + مرتب‌سازی ====================
    private fun refreshSubscriptions(scope: kotlinx.coroutines.CoroutineScope) {
        if (refreshing) return
        val urls = activeProfile()?.subscriptionUrls ?: emptyList()
        if (urls.isEmpty()) {
            showToast(scope, "لینک اشتراکی ذخیره نشده — اول یک لینک اضافه کن")
            return
        }
        scope.launch {
            refreshing = true
            try {
                // ۱) دانلود همه‌ی اشتراک‌ها؛ اشتراکی که دانلودش خراب بشه سرورهای قبلی‌ش رو نگه می‌داره
                val fetched = LinkedHashMap<String, List<VpnServer>?>()
                var failures = 0
                for (url in urls) {
                    val list = try {
                        withContext(Dispatchers.IO) { SubscriptionFetcher.fetchAndParse(url) }
                    } catch (e: Exception) {
                        null
                    }
                    if (list.isNullOrEmpty()) {
                        failures++
                        fetched[url] = null
                    } else {
                        fetched[url] = list.map { it.copy(source = url) }
                    }
                }
                if (failures == urls.size) {
                    showToast(scope, "بروزرسانی ناموفق بود — اینترنت یا لینک اشتراک رو چک کن")
                    return@launch
                }

                // ۲) ادغام، پینگ واقعی، مرتب‌سازی از کمترین پینگ
                val selectedLink = servers.find { it.id == selectedServerId }?.link
                val merged = ServerListOps.mergeRefresh(servers.toList(), fetched)
                val (numbered, _) = ServerListOps.renumber(merged, selectedLink)
                val pings = PingTester.pingAll(numbered)
                val (sorted, newSelected) = ServerListOps.applyPings(numbered, pings, selectedLink)

                servers.clear()
                servers.addAll(sorted)
                selectedServerId = newSelected
                syncActiveProfile()

                val alive = sorted.count { it.pingMs != null }
                val note = if (failures > 0) " ($failures لینک دانلود نشد)" else ""
                showToast(scope, "${sorted.size} سرور بروزرسانی شد؛ $alive تا پاسخ دادن$note")
            } catch (e: Exception) {
                showToast(scope, "خطا در بروزرسانی: ${e.message ?: "نامشخص"}")
            } finally {
                refreshing = false
            }
        }
    }

    // ==================== اسکن QR ====================
    private fun startQrScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        launchScanner()
    }

    private fun launchScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("QR کانفیگ یا لینک اشتراک رو داخل کادر بگیر")
            setBeepEnabled(false)
            setOrientationLocked(false)
        }
        scanLauncher.launch(options)
    }

    /** QR می‌تونه لینک اشتراک (https://…) یا یک/چند کانفیگ (vless:// vmess:// trojan:// ss://) باشه. */
    private fun onQrScanned(raw: String) {
        val scope = lifecycleScope
        val text = raw.trim()
        if (text.isEmpty()) return

        if (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)) {
            importSubscription(scope, text, isInitialLogin = !loggedIn)
            return
        }

        val parsed = SubscriptionFetcher.parseContent(text)
        if (parsed.isEmpty()) {
            showToast(scope, "این QR یک کانفیگ یا لینک اشتراک معتبر نیست")
            return
        }

        val before = servers.size
        val merged = ServerListOps.addUnique(servers.toList(), parsed, ServerListOps.MANUAL)
        val added = merged.size - before
        if (added == 0) {
            showToast(scope, "این کانفیگ قبلاً اضافه شده بود")
            return
        }

        servers.clear()
        servers.addAll(merged)
        selectedServerId = merged[before].id // اولین سرور جدید رو انتخاب کن تا بشه فوراً وصل شد
        loggedIn = true
        ensureProfile()
        syncActiveProfile()
        showToast(scope, if (added == 1) "کانفیگ اضافه و انتخاب شد" else "$added کانفیگ اضافه شد")
    }

    // ==================== اتصال / قطع VPN ====================
    private fun toggleConnect(scope: kotlinx.coroutines.CoroutineScope) {
        if (connected || connecting) {
            stopVpn()
            showToast(scope, "اتصال قطع شد")
        } else {
            val link = servers.find { it.id == selectedServerId }?.link
            if (link == null) {
                showToast(scope, "اول یک سرور انتخاب کن")
            } else {
                connecting = true
                VpnStatus.setConnecting(true)
                connectVpn(link)
                // تایم‌اوت ایمنی: هرگز روی «در حال اتصال» گیر نکنیم
                scope.launch {
                    delay(30_000)
                    if (!VpnStatus.isConnected.value && VpnStatus.isConnecting.value) {
                        VpnStatus.setConnecting(false)
                        toastText = "اتصال برقرار نشد — دوباره تلاش کن"
                        delay(2500)
                        if (toastText == "اتصال برقرار نشد — دوباره تلاش کن") toastText = null
                    }
                }
            }
        }
    }

    private fun connectVpn(link: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            // کاربر قبلاً مجوز نداده → دیالوگ سیستم باز می‌شه
            pendingLink = link
            vpnPermissionLauncher.launch(prepare)
        } else {
            startVpnService(link)
        }
    }

    private fun startVpnService(link: String) {
        val i = Intent(this, SchnellVpnService::class.java).apply {
            action = SchnellVpnService.ACTION_CONNECT
            putExtra(SchnellVpnService.EXTRA_LINK, link)
        }
        ContextCompat.startForegroundService(this, i)
        // نکته: connected اینجا true نمی‌شه — LaunchedEffect sync از VpnStatus.isConnected می‌خونه
    }

    private fun stopVpn() {
        val i = Intent(this, SchnellVpnService::class.java).apply {
            action = SchnellVpnService.ACTION_DISCONNECT
        }
        startService(i)
        connecting = false
        VpnStatus.setConnecting(false)
        // connected رو دستی false نمی‌کنیم — sync loop از VpnStatus می‌خونه
    }
}

// ==================== Login ====================
@Composable
fun LoginScreen(
    colors: AppColors,
    subLink: String,
    loading: Boolean,
    glass: Boolean,
    isDark: Boolean,
    onSubLinkChange: (String) -> Unit,
    onImport: () -> Unit,
    onScanQr: () -> Unit
) {
    val context = LocalContext.current
    GlassBackground(isDark = isDark, enabled = glass) {
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

        BasicField(colors, subLink, onSubLinkChange, "لینک اشتراک یا لینک کانفیگ را وارد کنید")
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
    glass: Boolean,
    isDark: Boolean,
    profiles: List<ServerProfile>,
    activeProfileId: Int,
    onSwitchProfile: (Int) -> Unit,
    onToggleConnect: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val server = servers.find { it.id == selectedId }
    val ping = server?.pingMs ?: 0

    GlassBackground(isDark = isDark, enabled = glass) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("SchnellVPN", color = colors.amber, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Box(
                Modifier.size(36.dp).panel(colors, glass, isDark, RoundedCornerShape(11.dp), solid = colors.surface2).clickable { onOpenSettings() },
                contentAlignment = Alignment.Center
            ) { Text("⚙", color = colors.textDim, fontSize = 16.sp) }
        }

        if (profiles.size > 1) {
            Spacer(Modifier.height(12.dp))
            ProfileChips(colors, profiles, activeProfileId, onSwitchProfile)
        }

        Spacer(Modifier.height(24.dp))

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ConnectGauge(
                colors = colors,
                connected = connected,
                connecting = connecting,
                pingMs = ping,
                glass = glass,
                isDark = isDark,
                onClick = onToggleConnect
            )
        }

        Spacer(Modifier.height(26.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .panel(colors, glass, isDark, RoundedCornerShape(18.dp))
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
            StatBox(colors, "مدت اتصال", fmtTime(durationSec), Modifier.weight(1f), glass, isDark)
            StatBox(colors, "حجم مصرفی", "${"%.1f".format(dataMB)} MB", Modifier.weight(1f), glass, isDark)
        }

        Spacer(Modifier.height(14.dp))
        TopAppsCard(colors, glass, isDark)
    }
    }
}

@Composable
fun StatBox(
    colors: AppColors,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    glass: Boolean = false,
    isDark: Boolean = true
) {
    Column(
        modifier
            .panel(colors, glass, isDark, RoundedCornerShape(16.dp))
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
fun ConnectGauge(
    colors: AppColors,
    connected: Boolean,
    connecting: Boolean,
    pingMs: Int,
    glass: Boolean,
    isDark: Boolean,
    onClick: () -> Unit
) {
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

    val tickColor = if (glass) colors.textDim.copy(alpha = 0.45f) else colors.border

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
                        color = tickColor,
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
            Modifier.size(176.dp).panel(colors, glass, isDark, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when { connecting -> "در حال اتصال…"; connected -> "متصل شدید"; else -> "برای اتصال ضربه بزنید" },
                    color = colors.textDim, fontSize = 12.sp, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (connected) (if (pingMs > 0) "$pingMs ms" else "—") else if (connecting) "···" else "—",
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
    refreshing: Boolean,
    glass: Boolean,
    isDark: Boolean,
    profiles: List<ServerProfile>,
    activeProfileId: Int,
    onSwitchProfile: (Int) -> Unit,
    onRefresh: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSelect: (Int) -> Unit,
    onTestPings: () -> Unit,
    onImport: () -> Unit,
    onScanQr: () -> Unit
) {
    val filtered = servers.filter { it.name.contains(query) || it.protocolLabel.contains(query, true) }

    GlassBackground(isDark = isDark, enabled = glass) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("سرورها", color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (profiles.size > 1) {
            Spacer(Modifier.height(12.dp))
            ProfileChips(colors, profiles, activeProfileId, onSwitchProfile)
        }
        Spacer(Modifier.height(14.dp))
        BasicField(colors, query, onQueryChange, "جستجوی کشور یا سرور")
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            colors,
            if (refreshing) "در حال بروزرسانی و تست پینگ…" else "بروزرسانی اشتراک (مرتب‌سازی با کمترین پینگ)",
            onClick = { if (!refreshing) onRefresh() }
        )
        Spacer(Modifier.height(10.dp))
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
                        .panel(colors, glass, isDark, RoundedCornerShape(16.dp), solid = if (isSelected) colors.surface2 else colors.surface)
                        .then(if (isSelected) Modifier.border(1.dp, colors.amber, RoundedCornerShape(16.dp)) else Modifier)
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
}

// ==================== Settings ====================
@Composable
fun SettingsScreen(
    colors: AppColors,
    isDark: Boolean,
    onToggleDark: () -> Unit,
    glass: Boolean,
    onToggleGlass: () -> Unit,
    profiles: List<ServerProfile>,
    activeProfileId: Int,
    onSwitchProfile: (Int) -> Unit,
    onDeleteProfile: (Int) -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    GlassBackground(isDark = isDark, enabled = glass) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("تنظیمات", color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))

        SettingRow(colors, "حالت تاریک", "رابط کاربری با نور کم", isDark, onToggleDark)
        Spacer(Modifier.height(10.dp))
        SettingRow(colors, "تم شیشه‌ای", "ظاهر شیشه‌ای در همه‌ی صفحه‌ها", glass, onToggleGlass)

        Spacer(Modifier.height(18.dp))
        Text("پروفایل‌ها", color = colors.textDim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (profiles.isEmpty()) {
            Text("هنوز پروفایلی نداری.", color = colors.textDim, fontSize = 12.sp)
        } else {
            profiles.forEach { p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(colors.surface, RoundedCornerShape(14.dp))
                        .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).clickable { onSwitchProfile(p.id) }) {
                        Text(
                            p.name + (if (p.id == activeProfileId) "  ✓" else ""),
                            color = if (p.id == activeProfileId) colors.amber else colors.text,
                            fontSize = 13.5.sp, fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(3.dp))
                        Text("${p.servers.size} سرور", color = colors.textDim, fontSize = 11.sp)
                    }
                    Text(
                        "✕", color = colors.coral, fontSize = 15.sp,
                        modifier = Modifier.clickable { onDeleteProfile(p.id) }.padding(6.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("درباره", color = colors.textDim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        AboutRow(colors, "نسخه برنامه", "1.0.0")
        AboutRow(colors, "هسته اتصال", "Xray-core")
        AboutRow(colors, "توسعه‌دهنده", "A-z-exe (Amirhosseinzarei)")
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

/** نوار انتخاب پروفایل (پروفایل ۱، ۲، ۳ …) */
@Composable
fun ProfileChips(
    colors: AppColors,
    profiles: List<ServerProfile>,
    activeId: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        profiles.forEach { p ->
            val active = p.id == activeId
            Box(
                Modifier
                    .background(colors.surface2, RoundedCornerShape(12.dp))
                    .border(1.dp, if (active) colors.amber else colors.border, RoundedCornerShape(12.dp))
                    .clickable { onSelect(p.id) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    "${p.name} (${p.servers.size})",
                    color = if (active) colors.amber else colors.text,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
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
            Text("افزودن اشتراک یا لینک سرور", color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "لینک Subscription یک پروفایل جدید می‌سازد؛ لینک مستقیم vless/vmess/trojan/ss به پروفایل فعلی اضافه می‌شود.",
                color = colors.textDim, fontSize = 11.sp
            )
            Spacer(Modifier.height(12.dp))
            BasicField(colors, value, onChange, "لینک اشتراک یا vless/vmess/trojan/ss")
            Spacer(Modifier.height(14.dp))
            PrimaryButton(colors, if (loading) "در حال دریافت…" else "افزودن", onClick = { if (!loading) onConfirm() })
        }
    }
}
