# SchnellVPN (نسخه تست)

[![Build](https://github.com/A-z-exe/SchnellVPN/actions/workflows/build.yml/badge.svg)](https://github.com/A-z-exe/SchnellVPN/actions/workflows/build.yml)
![Platform](https://img.shields.io/badge/platform-Android-green)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF)

یک کلاینت VPN برای اندروید با رابط کاربری Jetpack Compose که روی هسته‌ی Xray کار می‌کنه.

## ✨ ویژگی‌ها

- ✅ رابط کاربری Compose با حالت تاریک/روشن
- ✅ پشتیبانی از لینک‌های: VLESS (TCP / WS / gRPC / HTTPUpgrade / XHTTP، همراه با TLS و Reality)، VMess، Trojan، Shadowsocks
- ✅ ورود لینک Subscription (لیست ساده، Base64، یا JSON به سبک v2rayN)
- ✅ تست پینگ واقعی (زمان اتصال TCP به سرور)
- ✅ دکمه‌ی «بروزرسانی اشتراک»: دانلود دوباره‌ی همه‌ی اشتراک‌ها، تست پینگ خودکار و مرتب‌سازی از کمترین پینگ (سرورهای اضافه‌شده با QR حفظ می‌شن)
- ✅ اسکن QR با دوربین: لینک اشتراک (https) یا کانفیگ vless/vmess/trojan/ss
- ✅ تم شیشه‌ای در صفحه‌ی اصلی (از تنظیمات قابل خاموش‌کردنه) + کارت «۳ برنامه‌ی پرمصرف امروز» (نیاز به دسترسی Usage access)
- ✅ اتصال یک‌کلیکی، نمایش مدت اتصال و حجم مصرفی
- ⚠️ پلاگین‌های Shadowsocks (v2ray-plugin و …) پشتیبانی نمی‌شن؛ این لینک‌ها موقع ورود رد می‌شن.

## 🧱 معماری

```
اپ‌ها ─► TUN (VpnService) ─► hev-socks5-tunnel ─► SOCKS 127.0.0.1:10808 ─► Xray-core ─► سرور
```

- `SchnellVpnService.kt` — VpnService، foreground service، چرخه‌ی اتصال
- `XrayConfigBuilder.kt` — تبدیل لینک/JSON به کانفیگ Xray
- `SubscriptionFetcher.kt` — دانلود و پارس Subscription
- `HevBridge.kt` + `hev/htproxy/TProxyService.kt` — پل JNI به hev-socks5-tunnel
- `PingTester.kt`, `ProfileManager.kt`, `VpnStatus.kt`, `MainActivity.kt`

نکات مهم طراحی (برای جلوگیری از باگ‌های قبلی):

1. کلاس JNI باید دقیقاً `hev.htproxy.TProxyService` با امضاهای `(String,Int):Boolean`، `():Boolean`، `():Boolean`، `():LongArray` باشه؛ هر ناهمخوانی یعنی SIGABRT موقع لود.
2. خود اپ با `addDisallowedApplication` از VPN مستثنی شده، وگرنه ترافیک Xray به TUN برمی‌گرده (حلقه).
3. `startForegroundService` حتماً باید با `startForeground` جواب داده بشه.
4. Xray فقط SOCKS محلی باز می‌کنه (`startLoop(config, 0)`)؛ TUN فقط دست hev است.
5. `hev-socks5-tunnel` روی یک commit ثابت (`HEV_REF` در workflow) پین شده.

## 🚀 ساخت و تست

### Build ابری (GitHub Actions)

با هر push یا PR، workflow اول **تست‌های واحد** رو اجرا می‌کنه، بعد APK رو می‌سازه و وجود `libhev-socks5-tunnel.so` و `libgojni.so` داخل APK رو چک می‌کنه.
APK از بخش **Artifacts** همون run قابل دانلوده.

### Build محلی

```
git clone https://github.com/A-z-exe/SchnellVPN.git
cd SchnellVPN
# libv2ray.aar رو در app/libs/ و hev-socks5-tunnel رو در app/src/main/jni/ بذار (مثل workflow)
./gradlew testDebugUnitTest assembleDebug
```

## 🔐 تنظیم سرورها

لینک Subscription رو در صفحه‌ی ورود وارد کن. لینک‌های `http://` (بدون TLS) به‌صورت پیش‌فرض توسط اندروید مسدوده؛
این کار عمداً فعال نشده چون یک مهاجم وسط مسیر می‌تونه سرورهای جعلی تزریق کنه.

## 📄 مجوز

کتابخونه‌ی `AndroidLibXrayLite` تحت LGPL-3.0 و `hev-socks5-tunnel` تحت MIT منتشر شده؛ جزئیات در ریپوهای اصلی‌شون.

## ⚠️ هشدار مسئولیت

استفاده از این اپ باید مطابق قوانین کشور محل استفاده‌ی کاربر باشه. توسعه‌دهنده مسئولیتی در قبال نحوه‌ی استفاده‌ی کاربران نهایی ندارد.
