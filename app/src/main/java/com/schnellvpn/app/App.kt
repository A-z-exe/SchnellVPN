package com.schnellvpn.app

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * این کلاس همه‌ی Crashها رو می‌گیره و توی یه فایل متنی ذخیره می‌کنه.
 * دفعه‌ی بعد که اپ باز میشه، MainActivity این فایل رو می‌خونه و خطا رو نشون می‌ده.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val crashFile = File(filesDir, "last_crash.txt")

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            crashFile.writeText("Thread: ${thread.name}\n\n$sw")
            // اجازه بده سیستم اندروید هم خودش crash رو هندل کنه
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
