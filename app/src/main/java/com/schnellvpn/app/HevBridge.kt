package com.schnellvpn.app

/**
 * پل واقعی به کتابخونه‌ی hev-socks5-tunnel (نه حدسی/dlopen، بلکه دقیقاً
 * همون سه تابع نیتیوی که خود این کتابخونه ثبت می‌کنه).
 *
 * نکته‌ی مهم: این توابع با RegisterNatives توسط خودِ hev-jni.c
 * (در JNI_OnLoad) به این کلاس وصل می‌شن، به شرطی که موقع کامپایل native
 * با PKGNAME=com/schnellvpn/app و CLSNAME=HevBridge ساخته شده باشه
 * (این تنظیم توی app/build.gradle.kts انجام شده).
 */
object HevBridge {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    @JvmStatic external fun TProxyStartService(configPath: String, fd: Int)
    @JvmStatic external fun TProxyStopService()
    @JvmStatic external fun TProxyGetStats(): LongArray
}
