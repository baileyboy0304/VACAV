package com.msp1974.vacompanion.utils

import android.content.Intent
import android.net.Uri

/** Silk's own Echo intent option hides its toolbar after page loading. */
internal object StreamingLaunch {
    fun prepare(intent: Intent, packageName: String?) {
        if (packageName != "com.amazon.cloud9") return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        intent.putExtra("fullscreen", true)
        // A bare launcher intent does not load a page, so Silk never applies the option.
        // This device's Silk shortcut is the Apple TV streaming entry point.
        if (intent.data == null) {
            intent.action = Intent.ACTION_VIEW
            intent.data = Uri.parse("https://tv.apple.com/")
            intent.removeCategory(Intent.CATEGORY_LAUNCHER)
        }
    }
}
