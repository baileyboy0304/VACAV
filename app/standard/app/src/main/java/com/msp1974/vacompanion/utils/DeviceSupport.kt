package com.msp1974.vacompanion.utils

import android.os.Build

/** Portal firmware rejects third-party Device Admin even though its settings activity exists. */
object DeviceSupport {
    fun isPortal(model: String = Build.MODEL): Boolean = model.trim().startsWith("Portal", ignoreCase = true)
}
