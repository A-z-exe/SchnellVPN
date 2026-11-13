# SchnellVPN

[![Build](https://github.com/A-z-exe/SchnellVPN/actions/workflows/build.yml/badge.svg)](https://github.com/A-z-exe/SchnellVPN/actions/workflows/build.yml)
[![Windows](https://github.com/A-z-exe/SchnellVPN/actions/workflows/windows.yml/badge.svg)](https://github.com/A-z-exe/SchnellVPN/actions/workflows/windows.yml)
![Platform](https://img.shields.io/badge/platform-Android-green)
![Windows](https://img.shields.io/badge/platform-Windows%20beta-blue)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF)
![Version](https://img.shields.io/badge/version-1.4.3-blue)

**SchnellVPN** is a VPN client built on **Xray-core**, shipped for two platforms that share the
same engine, link formats and honesty checks (no "connected" before data really flows):

| | 🤖 **Android** (stable) | 🪟 **Windows** (beta) |
| --- | --- | --- |
| Version | **1.4.3** | **0.1.0** |
| Built with | Kotlin + Jetpack Compose | Python + PySide6 |
| Engine | AndroidLibXrayLite | Xray-core 26.x (`xray.exe`, bundled) |
| Tunnel | TUN (VpnService) + hev-socks5-tunnel | SOCKS/HTTP + WinINET system proxy |
| Extras | QR import, split tunneling, per-app auto-connect, hotspot sharing | real delay, 20 s data-flow monitor, profiles, tray, fa/en/de |
| Download | [Latest APK](https://github.com/A-z-exe/SchnellVPN/releases/latest) | [Windows beta release](https://github.com/A-z-exe/SchnellVPN/releases?q=Windows) |

The Android app supports VLESS (Reality / WS / gRPC / XHTTP), VMess, Trojan and Shadowsocks,
with multiple subscription profiles, QR import, direct config links, split tunneling,
per-app auto-connect, hotspot sharing, multi-language (فارسی / English / Deutsch) and in-app
updates from GitHub.

[⬇️ Download the latest APK](https://github.com/A-z-exe/SchnellVPN/releases/latest) · [🌐 Website](https://a-z-exe.github.io/SchnellVPN/) · [📦 Releases](https://github.com/A-z-exe/SchnellVPN/releases)

کلاینت VPN برای اندروید با رابط کاربری Jetpack Compose که روی هسته‌ی Xray کار می‌کند.
نسخهٔ ویندوز (بتا) با Python و PySide6 در پوشهٔ [`windows/`](windows/README.md) است و همان
هسته، فرمت لینک‌ها و تست واقعی عبور داده را دارد؛ حالت TUN و split tunneling هنوز اضافه نشده است.

## ✨ ویژگی‌ها

### اتصال و پروتکل
- ✅ پشتیبانی از VLESS (TCP / WS / gRPC / HTTPUpgrade / XHTTP همراه TLS و Reality)، VMess، Trojan و Shadowsocks
- ✅ **تأخیر واقعی کانفیگ (Real Delay)** مثل v2rayNG/Hiddify: یک هسته‌ی موقت Xray برای هر کانفیگ و عبور یک درخواست HTTP واقعی؛ نمایش Timeout برای بالای ۴۰۰۰ms
- ✅ پینگ مجدد سرور انتخاب‌شده از نوار پایین (در حالت متصل، از داخل تونل واقعی)
- ✅ **تست عبور واقعی دیتا بعد از اتصال**: تا وقتی یک درخواست واقعی از داخل تونل موفق نشود، وضعیت «متصل» اعلام نمی‌شود؛ در صورت عدم عبور دیتا، اتصال قطع و خطا نمایش داده می‌شود.
- ✅ **جلوگیری از اتصال بدون اینترنت**: اگر وای‌فای/دیتا خاموش باشد، اتصال و پینگ انجام نمی‌شود و عدد الکی نمایش داده نمی‌شود.
- ✅ **تست پینگ خارج از VPN**: پینگ همیشه روی شبکه‌ی واقعی (Wi‑Fi/دیتا) انجام می‌شود و وقتی VPN روشن است از داخل تونل رد نمی‌شود.
- ✅ **پینگ واقعی تونل در صفحه‌ی سرورها**: سرورِ متصل همان پینگ واقعی صفحه‌ی خانه را نشان می‌دهد.
- ✅ **پایش مداوم عبور داده**: هر ۲۰ ثانیه تونل تست می‌شود؛ در صورت قطع داده «بدون داده» نمایش داده و اتصال خودکار قطع می‌شود.
- ✅ **اتصال هوشمند (بهترین سرور)**: دکمه‌ی ⚡ همه‌ی سرورها را تست و سریع‌ترین را وصل می‌کند و در صورت عدم پاسخ‌دهی **خودکار** به سرور بعدی سوئیچ می‌شود؛ انتخاب دستی سرور همیشه محترم است.
- ✅ **ویرایش هر سرور**: تغییر نام و لینک کانفیگ با دکمه‌ی مداد.
- ✅ **تغییر سرور در حین اتصال**: با انتخاب سرور جدید، اتصال کامل قطع و دوباره وصل می‌شود.
- ✅ **پینگ مجدد سرور انتخاب‌شده** از نوار پایین کنار دکمه‌ی اتصال/قطع.
- ✅ نمایش مدت اتصال، حجم مصرفی و سرعت لحظه‌ای

### پروفایل و سرور
- ✅ **پروفایل‌های مستقل (پروفایل ۱، ۲، ۳ …)**: هر اشتراک سرورها و سرور انتخابی خودش را دارد
- ✅ ورود لینک Subscription (لیست ساده، Base64، یا JSON به سبک v2rayN)
- ✅ **افزودن لینک مستقیم سرور** (vless / vmess / trojan / ss) بدون نیاز به اشتراک
- ✅ اسکن QR با دوربین
- ✅ بروزرسانی اشتراک + تست پینگ خودکار + مرتب‌سازی از کمترین پینگ
- ✅ **اشتراک‌گذاری پروفایل** با لینک و **QR Code** (کپی یا ارسال به اپ‌های دیگر)

### اتوماسیون و شبکه
- ✅ **Split tunneling**: انتخاب اپ‌هایی که نباید از VPN عبور کنند (مثل همراه بانک)
- ✅ **روشن شدن خودکار VPN با باز شدن اپ‌های انتخابی** (نیاز به فعال‌کردن سرویس Accessibility)
- ✅ **اتصال خودکار بعد از روشن شدن گوشی**
- ✅ **قطع اتصال هنگام قفل شدن صفحه**
- ✅ **اشتراک VPN روی هات‌اسپات (بدون روت)** به‌صورت پروکسی محلی روی پورت `10809` + راهنمای کامل اتصال دستگاه‌ها

### رابط کاربری
- ✅ **صفحه‌ی اول با انیمیشن ورود لوگو** به وسط صفحه
- ✅ **تم شیشه‌ای در همه‌ی صفحه‌ها** + حالت تاریک/روشن
- ✅ **کارت پرمصرف‌ترین اپ‌ها در ۲۴ ساعت گذشته** + نمایش لحظه‌ای اپی که دارد مصرف می‌کند
- ✅ **اعلان غنی**: مدت اتصال، سرعت لحظه‌ای و حجم کل
- ✅ **آیکن اختصاصی اپ در نوار وضعیت** کنار آیکن اینترنت
- ✅ **رابط کاربری چندزبانه**: فارسی، English، Deutsch (با تغییر خودکار جهت‌چیدمان RTL/LTR)
- ✅ **به‌روزرسانی درون‌برنامه از GitHub**: بررسی نسخه، دانلود، بررسی SHA-256 و اجرای نصب‌کننده

> ⚠️ پلاگین‌های Shadowsocks (v2ray-plugin و …) پشتیبانی نمی‌شوند و این لینک‌ها موقع ورود رد می‌شوند.

## 🧱 معماری

```
اپ‌ها ─► TUN (VpnService) ─► hev-socks5-tunnel ─► SOCKS 127.0.0.1:10808 ─► Xray-core ─► سرور
```

- `SchnellVpnService.kt` — VpnService، foreground service، چرخه‌ی اتصال، تست تونل و آمار
- `XrayConfigBuilder.kt` — تبدیل لینک/JSON به کانفیگ Xray
- `SubscriptionFetcher.kt` — دانلود و پارس Subscription
- `TunnelProbe.kt` — بررسی واقعی عبور دیتا از تونل
- `NetworkUtils.kt` — بررسی اینترنت، IP محلی و وضعیت Accessibility
- `HevBridge.kt` + `hev/htproxy/TProxyService.kt` — پل JNI به hev-socks5-tunnel
- `ProfileStore.kt` / `ServerProfile.kt` — ذخیره‌ی پروفایل‌های مستقل
- `AppsScreen.kt` / `TriggerAccessibilityService.kt` — مدیریت اپ‌ها و روشن‌کردن خودکار
- `ShareDialog.kt` / `HotspotInfoDialog.kt` — اشتراک‌گذاری QR و راهنمای هات‌اسپات
- `PingTester.kt`, `ProfileManager.kt`, `VpnStatus.kt`, `MainActivity.kt`

نکات مهم طراحی (برای جلوگیری از باگ‌های قبلی):

1. کلاس JNI باید دقیقاً `hev.htproxy.TProxyService` با امضاهای `(String,Int):Boolean`، `():Boolean`، `():Boolean`، `():LongArray` باشد؛ هر ناهم‌خوانی یعنی SIGABRT موقع لود.
2. خود اپ با `addDisallowedApplication` از VPN مستثنی شده، وگرنه ترافیک Xray به TUN برمی‌گردد (حلقه).
3. `startForegroundService` حتماً باید با `startForeground` جواب داده شود.
4. Xray فقط SOCKS محلی باز می‌کند (`startLoop(config, 0)`)؛ TUN فقط دست hev است.
5. `hev-socks5-tunnel` روی یک commit ثابت (`HEV_REF` در workflow) پین شده.
6. بعد از اتصال، `TunnelProbe` از طریق SOCKS یک درخواست واقعی می‌فرستد؛ فقط در صورت موفقیت وضعیت «متصل» می‌شود.

## 🚀 ساخت و تست

### Build ابری (GitHub Actions)

با هر push یا PR، workflow اول **تست‌های واحد** را اجرا می‌کند، بعد APK را می‌سازد و وجود `libhev-socks5-tunnel.so` و `libgojni.so` داخل APK را چک می‌کند.
APK از بخش **Artifacts** همان run یا از **Releases** قابل دانلود است.

### Build محلی

```
git clone https://github.com/A-z-exe/SchnellVPN.git
cd SchnellVPN
# libv2ray.aar را در app/libs/ و hev-socks5-tunnel را در app/src/main/jni/ بگذار (مثل workflow)
./gradlew testDebugUnitTest assembleDebug
```

## 🔐 تنظیم سرورها

لینک Subscription یا لینک مستقیم سرور را در صفحه‌ی ورود وارد کن. لینک‌های `http://` نیز پشتیبانی می‌شوند (برای سازگاری با اشتراک‌های قدیمی)؛ با این حال استفاده از `https://` امن‌تر است.

## ❓ سوالات متداول (FAQ)

**SchnellVPN چیست؟** یک کلاینت VPN متن‌باز برای اندروید است که با Kotlin، Jetpack Compose و Xray-core ساخته شده و از VLESS (Reality)، VMess، Trojan و Shadowsocks پشتیبانی می‌کند.

**آیا رایگان است؟** بله، سورس‌کد روی GitHub عمومی است و APK از بخش Releases رایگان دانلود می‌شود.

**چه پروتکل‌هایی پشتیبانی می‌شوند؟** VLESS (Reality / WS / gRPC / HTTPUpgrade / XHTTP)، VMess، Trojan و Shadowsocks.

**آیا به Google Play Services نیاز دارد؟** نه؛ اسکن QR به‌صورت آفلاین با ZXing انجام می‌شود.

**اشتراک روی هات‌اسپات روت می‌خواهد؟** نه. یک پروکسی HTTP محلی روی پورت `10809` اجرا می‌شود و دستگاه‌ها باید پروکسی را دستی تنظیم کنند. اشتراک شفاف بدون روت در اندروید ممکن نیست.

**اینترنت گوشی خاموش باشد چه می‌شود؟** اپ اجازه‌ی اتصال نمی‌دهد و پینگ الکی نشان نمی‌دهد؛ درستی مسیر هم بعد از اتصال با یک درخواست واقعی از داخل تونل بررسی می‌شود.

## 📄 مجوز

کتابخانه‌ی `AndroidLibXrayLite` تحت LGPL-3.0 و `hev-socks5-tunnel` تحت MIT منتشر شده؛ جزئیات در ریپوهای اصلی‌شان.

## ⚠️ هشدار مسئولیت

استفاده از این اپ باید مطابق قوانین کشور محل استفاده‌ی کاربر باشد. توسعه‌دهنده مسئولیتی در قبال نحوه‌ی استفاده‌ی کاربران نهایی ندارد.
