package com.schnellvpn.app

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Saves the last crash to a file (shown on next launch), then lets the system handle it. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val crashFile = File(filesDir, "last_crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                crashFile.writeText("Thread: ${thread.name}\n\n$sw")
            } catch (_: Throwable) {
            }
            if (previous != null) previous.uncaughtException(thread, throwable)
            else android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
