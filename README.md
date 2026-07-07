# SchnellVPN

![Build](https://github.com/A-z-exe/SchnellVPN-/actions/workflows/build.yml/badge.svg)
![License](https://img.shields.io/badge/license-Proprietary-blue)
![Platform](https://img.shields.io/badge/platform-Android-green)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF)

یک کلاینت VPN اختصاصی برای اندروید، با رابط کاربری مدرن و پشتیبانی از پروتکل‌های پرکاربرد امروزی.

---

## ✨ ویژگی‌ها

- ✅ رابط کاربری اختصاصی (Jetpack Compose) با حالت تاریک/روشن
- ✅ پشتیبانی از پروتکل‌ها:
  - VLESS (+ Reality)
  - VMess
  - Trojan
  - Shadowsocks
  - gRPC / WebSocket
- ✅ وارد کردن لینک Subscription
- ✅ اسکن QR Code
- ✅ نمایش پینگ زنده‌ی سرورها
- ✅ اتصال یک‌کلیکی
- ✅ نمایش مدت اتصال و حجم مصرفی

## 📱 اسکرین‌شات

> *(بعداً اینجا چندتا اسکرین‌شات از اپ بگذار)*

## 🧱 ساختار پروژه

```
SchnellVPN/
├── .github/workflows/build.yml     # Build خودکار روی GitHub Actions
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/schnellvpn/app/
│           ├── MainActivity.kt          # رابط کاربری (Compose)
│           ├── SchnellVpnService.kt     # VpnService + اتصال به Xray-core
│           ├── XrayConfigBuilder.kt     # پارس‌کننده‌ی لینک‌ها (ConfigParser)
│           └── Server.kt                # مدل داده‌ی سرور
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## ⚙️ هسته‌ی شبکه

این پروژه از کتابخونه‌ی متن‌باز [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite) (لایسنس LGPL-3.0) برای پیاده‌سازی واقعی پروتکل‌های VLESS/VMess/Trojan/Shadowsocks استفاده می‌کنه. این فایل (`libv2ray.aar`) به‌صورت خودکار توی مرحله‌ی Build از ریلیزهای رسمی همون ریپو دانلود می‌شه — نیازی به آپلود دستیش نیست.

## 🚀 ساخت و اجرا

### روش ۱ — Build ابری (بدون نیاز به نصب چیزی روی کامپیوتر)
هر بار که تغییری Commit بشه، GitHub Actions خودش پروژه رو می‌سازه. برای دریافت APK:
1. برو تب **Actions**
2. آخرین Workflow موفق (✅ سبز) رو باز کن
3. از بخش **Artifacts**، فایل APK رو دانلود کن

### روش ۲ — Build محلی (اگه Android Studio نصب داری)
```bash
git clone https://github.com/A-z-exe/SchnellVPN-.git
cd SchnellVPN-
./gradlew assembleDebug
```
خروجی APK اینجا قرار می‌گیره:
```
app/build/outputs/apk/debug/app-debug.apk
```

## 🔐 تنظیم سرورها

لینک‌های واقعی سرورهات رو توی فایل زیر جایگزین مقدارهای نمونه کن:
```
app/src/main/java/com/schnellvpn/app/MainActivity.kt
```

## 📄 مجوز

این پروژه برای استفاده‌ی داخلی/تجاری ساخته شده. کتابخونه‌ی `AndroidLibXrayLite` که به‌عنوان dependency استفاده می‌شه، تحت لایسنس **LGPL-3.0** منتشر شده؛ جزئیات رو در [ریپوی اصلی](https://github.com/2dust/AndroidLibXrayLite) ببین.

## ⚠️ هشدار مسئولیت

استفاده از این اپ باید مطابق قوانین کشور محل استفاده‌ی کاربر باشه. توسعه‌دهنده مسئولیتی در قبال نحوه‌ی استفاده‌ی کاربران نهایی ندارد.
