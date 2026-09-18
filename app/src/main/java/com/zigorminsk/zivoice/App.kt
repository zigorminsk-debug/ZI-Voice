package com.zigorminsk.zivoice

import android.app.Application
import android.os.Build

/**
 * Глобальная фиксация сбоев: последний краш сохраняется в файл
 * last_crash.txt, чтобы можно было увидеть точную причину
 * (MainActivity показывает его при следующем запуске).
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                openFileOutput("last_crash.txt", MODE_PRIVATE).use { out ->
                    val head = "ZI-Voice 0.2.1 · API " + Build.VERSION.SDK_INT +
                        " · поток: " + thread.name + "\n"
                    out.write((head + throwable.stackTraceToString()).toByteArray())
                }
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
