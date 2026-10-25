package com.schnellvpn.app

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

enum class AppLanguage(val code: String, val nativeName: String) {
    FA("fa", "فارسی"),
    EN("en", "English"),
    DE("de", "Deutsch"),
    KO("ko", "한국어"),
    JA("ja", "日本語");

    companion object {
        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: FA
    }
}

/** زبان جاری برنامه. تغییر آن همه‌ی Textها را دوباره ترجمه می‌کند. */
object Localization {
    val language = mutableStateOf(AppLanguage.FA)
}

/**
 * دیکشنری مرکزی: کلید = متن فارسی، مقدار = [English, Deutsch, 한국어, 日本語]
 */
private val TRANSLATIONS: Map<String, List<String>> = mapOf(
    // ناوبری
    "خانه" to listOf("Home", "Start", "홈", "ホーム"),
    "سرورها" to listOf("Servers", "Server", "서버", "サーバー"),
    "اپ‌ها" to listOf("Apps", "Apps", "앱", "アプリ"),
    "تنظیمات" to listOf("Settings", "Einstellungen", "설정", "設定"),
    "کانفیگ‌ها" to listOf("Configs", "Konfigurationen", "구성", "設定"),
    "مدیریت اپ‌ها" to listOf("App management", "App-Verwaltung", "앱 관리", "アプリ管理"),

    // وضعیت اتصال
    "وصل نیست" to listOf("Not connected", "Nicht verbunden", "연결 안 됨", "未接続"),
    "متصل" to listOf("Connected", "Verbunden", "연결됨", "接続済み"),
    "در حال اتصال…" to listOf("Connecting…", "Verbinden…", "연결 중…", "接続中…"),
    "قطع" to listOf("Disconnect", "Trennen", "연결 해제", "切断"),
    "اتصال" to listOf("Connect", "Verbinden", "연결", "接続"),
    "سروری انتخاب نشده" to listOf("No server selected", "Kein Server ausgewählt", "서버가 선택되지 않음", "サーバー未選択"),
    "بدون داده" to listOf("No data", "Keine Daten", "데이터 없음", "データなし"),
    "متصل — بدون داده" to listOf("Connected — no data", "Verbunden — keine Daten", "연결됨 — 데이터 없음", "接続済み — データなし"),
    "متصل شدید" to listOf("Connected", "Verbunden", "연결되었습니다", "接続しました"),
    "برای اتصال ضربه بزنید" to listOf("Tap to connect", "Zum Verbinden tippen", "탭하여 연결", "タップして接続"),
    "آماده" to listOf("Ready", "Bereit", "준비됨", "準備完了"),
    "آماده‌ی اتصال" to listOf("Ready to connect", "Bereit zum Verbinden", "연결 준비됨", "接続準備完了"),

    // خانه
    "مدت اتصال" to listOf("Duration", "Dauer", "연결 시간", "接続時間"),
    "حجم مصرفی" to listOf("Data used", "Datenverbrauch", "사용량", "使用量"),
    "پرمصرف‌ترین برنامه‌ها" to listOf("Top apps", "Top-Apps", "많이 사용한 앱", "使用量の多いアプリ"),
    "۲۴ ساعت گذشته" to listOf("Last 24 hours", "Letzte 24 Stunden", "지난 24시간", "過去24時間"),

    // کانفیگ‌ها
    "تست پینگ همه" to listOf("Ping all", "Alle anpingen", "모두 핑", "すべてPing"),
    "اشتراک‌گذاری" to listOf("Share", "Teilen", "공유", "共有"),
    "اشتراک‌گذاری پروفایل" to listOf("Share profile", "Profil teilen", "프로필 공유", "プロファイルを共有"),
    "اشتراک‌گذاری کانفیگ" to listOf("Share config", "Konfiguration teilen", "구성 공유", "設定を共有"),
    "حذف" to listOf("Delete", "Löschen", "삭제", "削除"),
    "حذف پروفایل" to listOf("Delete profile", "Profil löschen", "프로필 삭제", "プロファイル削除"),
    "افزودن کانفیگ" to listOf("Add config", "Konfiguration hinzufügen", "구성 추가", "設定を追加"),
    "افزودن" to listOf("Add", "Hinzufügen", "추가", "追加"),
    "بروزرسانی اشتراک" to listOf("Refresh subscription", "Abo aktualisieren", "구독 새로고침", "サブスクリプション更新"),
    "بروزرسانی ناموفق بود — اینترنت یا لینک اشتراک رو چک کن" to listOf(
        "Refresh failed — check internet or subscription link",
        "Aktualisierung fehlgeschlagen — Internet oder Abo-Link prüfen",
        "새로고침 실패 — 인터넷 또는 구독 링크 확인",
        "更新失敗 — インターネットまたはサブスクリンクを確認"
    ),
    "در حال بروزرسانی…" to listOf("Refreshing…", "Aktualisieren…", "새로고침 중…", "更新中…"),

    // لاگین
    "شروع" to listOf("Start", "Start", "시작", "開始"),
    "وارد کردن لینک" to listOf("Import link", "Link importieren", "링크 가져오기", "リンクをインポート"),
    "اسکن QR Code" to listOf("Scan QR Code", "QR-Code scannen", "QR 코드 스캔", "QRコードをスキャン"),
    "اسکن QR" to listOf("Scan QR", "QR scannen", "QR 스캔", "QRスキャン"),
    "لینک اشتراک یا لینک کانفیگ را وارد کن" to listOf(
        "Enter subscription or config link",
        "Abo- oder Konfigurationslink eingeben",
        "구독 또는 구성 링크 입력",
        "サブスクまたは設定リンクを入力"
    ),
    "سریع. امن. بدون پیچیدگی." to listOf("Fast. Secure. Simple.", "Schnell. Sicher. Einfach.", "빠르고 안전하고 간단합니다.", "高速・安全・シンプル。"),
    "سرعت را حس کن، امن بمان." to listOf("Feel the speed, stay secure.", "Spüre die Geschwindigkeit, bleib sicher.", "속도를 느끼고 안전하게.", "速さを実感し、安全に。"),
    "از ادمین کانال دریافت کن" to listOf("Get it from the channel admin", "Vom Kanal-Admin erhalten", "채널 관리자에게 받기", "チャンネル管理者から取得"),
    "کانفیگ نداری؟" to listOf("No config?", "Keine Konfiguration?", "구성이 없나요?", "設定がありませんか？"),

    // تنظیمات
    "حالت تاریک" to listOf("Dark mode", "Dunkelmodus", "다크 모드", "ダークモード"),
    "رابط کاربری با نور کم" to listOf("Low-light interface", "Benutzeroberfläche mit wenig Licht", "저조도 인터페이스", "低光量UI"),
    "تم شیشه‌ای" to listOf("Glass theme", "Glas-Design", "글래스 테마", "ガラステーマ"),
    "ظاهر شیشه‌ای در همه‌ی صفحه‌ها" to listOf("Glass look on all screens", "Glas-Optik auf allen Seiten", "모든 화면에 글래스 스타일", "全画面ガラス調"),
    "اتصال خودکار در بوت" to listOf("Auto-connect on boot", "Automatisch beim Start verbinden", "부팅 시 자동 연결", "起動時に自動接続"),
    "بعد از روشن شدن گوشی خودکار وصل شود" to listOf(
        "Connect automatically after boot",
        "Nach dem Start automatisch verbinden",
        "부팅 후 자동 연결",
        "起動後に自動接続"
    ),
    "قطع اتصال هنگام قفل صفحه" to listOf("Disconnect on screen lock", "Bei Bildschirmsperre trennen", "화면 잠금 시 연결 해제", "画面ロック時に切断"),
    "با خاموش شدن صفحه، VPN قطع شود" to listOf(
        "Disconnect VPN when screen turns off",
        "VPN bei Bildschirmaus trennen",
        "화면이 꺼지면 VPN 연결 해제",
        "画面オフ時にVPNを切断"
    ),
    "اشتراک VPN روی هات‌اسپات" to listOf("Share VPN over hotspot", "VPN über Hotspot teilen", "핫스팟으로 VPN 공유", "ホットスポットでVPN共有"),
    "پروکسی محلی برای دستگاه‌های هات‌اسپات" to listOf(
        "Local proxy for hotspot devices",
        "Lokaler Proxy für Hotspot-Geräte",
        "핫스팟 기기용 로컬 프록시",
        "ホットスポット端末用ローカルプロキシ"
    ),
    "راهنمای کامل اتصال دستگاه‌ها" to listOf(
        "Full device setup guide",
        "Vollständige Geräteanleitung",
        "기기 연결 전체 안내",
        "端末接続の完全ガイド"
    ),
    "پروفایل‌ها" to listOf("Profiles", "Profile", "프로필", "プロファイル"),
    "درباره" to listOf("About", "Über", "정보", "情報"),
    "نسخه برنامه" to listOf("App version", "App-Version", "앱 버전", "アプリバージョン"),
    "هسته اتصال" to listOf("Core", "Kern", "코어", "コア"),
    "توسعه‌دهنده" to listOf("Developer", "Entwickler", "개발자", "開発者"),
    "ارتباط با پشتیبانی" to listOf("Support", "Support", "지원", "サポート"),
    "خروج از حساب" to listOf("Log out", "Abmelden", "로그아웃", "ログアウト"),

    // به‌روزرسانی
    "به‌روزرسانی برنامه" to listOf("App update", "App-Update", "앱 업데이트", "アプリ更新"),
    "بررسی به‌روزرسانی" to listOf("Check for updates", "Nach Updates suchen", "업데이트 확인", "アップデートを確認"),
    "در حال بررسی…" to listOf("Checking…", "Prüfen…", "확인 중…", "確認中…"),
    "تلاش دوباره" to listOf("Retry", "Erneut versuchen", "다시 시도", "再試行"),
    "نسخه‌ی جدیدی موجود نیست" to listOf("You're up to date", "Keine neue Version", "최신 버전입니다", "最新版です"),
    "در حال دانلود…" to listOf("Downloading…", "Wird heruntergeladen…", "다운로드 중…", "ダウンロード中…"),

    // اپ‌ها
    "خارج از VPN" to listOf("Bypass VPN", "VPN umgehen", "VPN 우회", "VPN除外"),
    "باز شدن اپ → روشن شدن VPN" to listOf(
        "App open → connect VPN",
        "App öffnen → VPN verbinden",
        "앱 열기 → VPN 연결",
        "アプリ起動 → VPN接続"
    ),
    "در حال خواندن لیست اپ‌ها…" to listOf("Loading app list…", "App-Liste wird geladen…", "앱 목록 불러오는 중…", "アプリ一覧を読み込み中…"),
    "سرویس دسترسی فعال است ✓" to listOf(
        "Accessibility service is on ✓",
        "Bedienungshilfe aktiv ✓",
        "접근성 서비스 활성화됨 ✓",
        "アクセシビリティ有効 ✓"
    ),
    "برای این قابلیت، «سرویس دسترسی» باید فعال شود." to listOf(
        "Accessibility service must be enabled for this feature.",
        "Für diese Funktion muss die Bedienungshilfe aktiviert sein.",
        "이 기능을 사용하려면 접근성 서비스를 활성화해야 합니다.",
        "この機能にはアクセシビリティサービスが必要です。"
    ),
    "باز کردن تنظیمات دسترسی" to listOf(
        "Open accessibility settings",
        "Bedienungshilfen öffnen",
        "접근성 설정 열기",
        "アクセシビリティ設定を開く"
    ),
    "بررسی مجدد" to listOf("Re-check", "Erneut prüfen", "다시 확인", "再確認"),
    "VPN با پروفایل و سرور فعالِ فعلی روشن می‌شود." to listOf(
        "VPN connects with the current active profile and server.",
        "VPN verbindet mit dem aktuell aktiven Profil und Server.",
        "현재 활성 프로필과 서버로 VPN이 연결됩니다.",
        "現在のアクティブなプロファイルとサーバーでVPNに接続します。"
    ),

    // عمومی
    "کپی" to listOf("Copy", "Kopieren", "복사", "コピー"),
    "فهمیدم" to listOf("Got it", "Verstanden", "확인", "了解"),
    "راهنمای اشتراک VPN روی هات‌اسپات" to listOf(
        "Hotspot VPN guide",
        "Hotspot-VPN-Anleitung",
        "핫스팟 VPN 안내",
        "ホットスポットVPNガイド"
    ),
    "در حال دریافت سرورها…" to listOf("Fetching servers…", "Server werden geladen…", "서버 불러오는 중…", "サーバー取得中…"),
    "در حال دریافت…" to listOf("Fetching…", "Laden…", "가져오는 중…", "取得中…"),
    "در حال تست پینگ…" to listOf("Testing ping…", "Ping wird getestet…", "핑 테스트 중…", "Pingテスト中…"),
    "در حال خواندن آمار…" to listOf("Reading stats…", "Statistiken werden gelesen…", "통계 읽는 중…", "統計を読み込み中…"),
    "هنوز مصرفی ثبت نشده" to listOf("No usage recorded yet", "Noch keine Nutzung", "아직 사용 기록 없음", "使用記録なし"),
    "اعطای دسترسی" to listOf("Grant access", "Zugriff gewähren", "권한 부여", "アクセスを許可"),

    // پیام‌ها
    "اول یک سرور انتخاب کن" to listOf("Select a server first", "Wähle zuerst einen Server", "먼저 서버를 선택하세요", "先にサーバーを選択"),
    "اینترنت گوشی وصل نیست — اول وای‌فای یا دیتا را روشن کن" to listOf(
        "No internet — turn on Wi-Fi or mobile data",
        "Kein Internet — WLAN oder mobile Daten einschalten",
        "인터넷 없음 — Wi-Fi 또는 데이터를 켜세요",
        "インターネットなし — Wi-Fiまたはデータをオン"
    ),
    "اینترنت گوشی وصل نیست — پینگ امکان‌پذیر نیست" to listOf(
        "No internet — ping unavailable",
        "Kein Internet — Ping nicht möglich",
        "인터넷 없음 — 핑 불가",
        "インターネットなし — Ping不可"
    ),
    "این لینک کانفیگ معتبر نیست" to listOf("Invalid config link", "Ungültiger Konfigurationslink", "잘못된 구성 링크", "無効な設定リンク"),
    "هیچ سرور معتبری توی این لینک پیدا نشد" to listOf(
        "No valid servers found in this link",
        "Keine gültigen Server gefunden",
        "유효한 서버를 찾을 수 없음",
        "有効なサーバーが見つかりません"
    ),
    "این کانفیگ قبلاً اضافه شده بود" to listOf(
        "This config was already added",
        "Diese Konfiguration existiert bereits",
        "이미 추가된 구성입니다",
        "この設定は既に追加されています"
    ),
    "کانفیگ اضافه و انتخاب شد" to listOf(
        "Config added and selected",
        "Konfiguration hinzugefügt und ausgewählt",
        "구성이 추가되고 선택됨",
        "設定を追加して選択しました"
    ),
    "اتصال برقرار شد ولی دیتا رد نمی‌شود — سرور یا کانفیگ را چک کن" to listOf(
        "Connected but no data — check server/config",
        "Verbunden, aber keine Daten — Server/Konfiguration prüfen",
        "연결됨, 데이터 없음 — 서버/구성 확인",
        "接続済みだがデータなし — サーバー/設定を確認"
    ),
    "اتصال برقرار نشد — دوباره تلاش کن" to listOf(
        "Connection failed — try again",
        "Verbindung fehlgeschlagen — erneut versuchen",
        "연결 실패 — 다시 시도",
        "接続失敗 — 再試行"
    ),
    "اتصال قطع شد" to listOf("Disconnected", "Getrennt", "연결 해제됨", "切断しました"),
    "اجازه‌ی نصب اپ‌ها را بده و دوباره تلاش کن" to listOf(
        "Allow app installs and try again",
        "App-Installation erlauben und erneut versuchen",
        "앱 설치를 허용하고 다시 시도",
        "アプリのインストールを許可して再試行"
    ),
    "دانلود ناموفق بود — دوباره تلاش کن" to listOf(
        "Download failed — try again",
        "Download fehlgeschlagen — erneut versuchen",
        "다운로드 실패 — 다시 시도",
        "ダウンロード失敗 — 再試行"
    ),
    "لطفاً یک لینک معتبر وارد کن" to listOf(
        "Please enter a valid link",
        "Bitte einen gültigen Link eingeben",
        "유효한 링크를 입력하세요",
        "有効なリンクを入力してください"
    ),
    "نسخه 1.3.0" to listOf("Version 1.3.0", "Version 1.3.0", "버전 1.3.0", "バージョン 1.3.0")
)

fun translateText(text: String, lang: AppLanguage): String {
    if (lang == AppLanguage.FA) return text
    val idx = when (lang) {
        AppLanguage.EN -> 0
        AppLanguage.DE -> 1
        AppLanguage.KO -> 2
        AppLanguage.JA -> 3
        AppLanguage.FA -> return text
    }
    TRANSLATIONS[text]?.let { return it.getOrElse(idx) { text } }

    // الگوهای پویا
    Regex("^(\\d+) سرور$").find(text)?.let { m ->
        val n = m.groupValues[1]
        return when (lang) {
            AppLanguage.EN -> "$n servers"
            AppLanguage.DE -> "$n Server"
            AppLanguage.KO -> "${n}개 서버"
            AppLanguage.JA -> "${n}サーバー"
            else -> text
        }
    }
    Regex("^(\\d+) کانفیگ$").find(text)?.let { m ->
        val n = m.groupValues[1]
        return when (lang) {
            AppLanguage.EN -> "$n configs"
            AppLanguage.DE -> "$n Konfigurationen"
            AppLanguage.KO -> "${n}개 구성"
            AppLanguage.JA -> "${n}設定"
            else -> text
        }
    }
    Regex("^پروفایل (\\d+)$").find(text)?.let { m ->
        val n = m.groupValues[1]
        return when (lang) {
            AppLanguage.EN -> "Profile $n"
            AppLanguage.DE -> "Profil $n"
            AppLanguage.KO -> "프로필 $n"
            AppLanguage.JA -> "プロファイル $n"
            else -> text
        }
    }
    Regex("^نسخه ([\\d.]+)$").find(text)?.let { m ->
        val v = m.groupValues[1]
        return when (lang) {
            AppLanguage.EN -> "Version $v"
            AppLanguage.DE -> "Version $v"
            AppLanguage.KO -> "버전 $v"
            AppLanguage.JA -> "バージョン $v"
            else -> text
        }
    }
    Regex("^اشتراک‌گذاری (.+)$").find(text)?.let { m ->
        val n = m.groupValues[1]
        return when (lang) {
            AppLanguage.EN -> "Share $n"
            AppLanguage.DE -> "$n teilen"
            AppLanguage.KO -> "$n 공유"
            AppLanguage.JA -> "$n を共有"
            else -> text
        }
    }
    Regex("^نسخه فعلی: (.+)$").find(text)?.let { m ->
        val v = m.groupValues[1]
        return when (lang) {
            AppLanguage.EN -> "Current version: $v"
            AppLanguage.DE -> "Aktuelle Version: $v"
            AppLanguage.KO -> "현재 버전: $v"
            AppLanguage.JA -> "現在のバージョン: $v"
            else -> text
        }
    }
    Regex("^دانلود و نصب (.+)$").find(text)?.let { m ->
        val v = m.groupValues[1]
        return when (lang) {
            AppLanguage.EN -> "Download & install $v"
            AppLanguage.DE -> "$v herunterladen & installieren"
            AppLanguage.KO -> "$v 다운로드 및 설치"
            AppLanguage.JA -> "$v をダウンロードしてインストール"
            else -> text
        }
    }
    Regex("^در حال دانلود… (\\d+)%$").find(text)?.let { m ->
        val p = m.groupValues[1]
        return when (lang) {
            AppLanguage.EN -> "Downloading… $p%"
            AppLanguage.DE -> "Wird heruntergeladen… $p%"
            AppLanguage.KO -> "다운로드 중… $p%"
            AppLanguage.JA -> "ダウンロード中… $p%"
            else -> text
        }
    }
    return text
}

/**
 * جایگزین Text متریال: متن فارسی را قبل از نمایش به زبان جاری ترجمه می‌کند.
 * بقیه‌ی فایل‌ها import متریال Text را حذف کرده‌اند تا این تابع استفاده شود.
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current
) {
    val lang = Localization.language.value
    MaterialText(
        text = translateText(text, lang),
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        onTextLayout = onTextLayout,
        style = style
    )
}
