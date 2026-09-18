package com.gemini.webapp

import android.app.Application
import android.os.Build
import android.webkit.WebView

class GeminiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Isolate WebView data dir per process (avoids lock crashes) and warm the WebView
        // provider early so the first Activity paint does not pay the Chromium init cost.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val process = getProcessName()
            if (process != packageName) WebView.setDataDirectorySuffix(process)
        }
        ShellCache.init(this)
    }
}
