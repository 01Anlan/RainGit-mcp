package me.rain.git

import android.app.Application
import java.io.File

/** Keeps the last unexpected failure readable from app-private diagnostics. */
class RainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val text = "time=${System.currentTimeMillis()}\nthread=${thread.name}\n" + error.stackTraceToString()
                File(filesDir, "last-crash.txt").writeText(text.takeLast(100_000))
            }
            old?.uncaughtException(thread, error)
        }
    }
}
