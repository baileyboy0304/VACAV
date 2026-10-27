package com.mph1970.vacavision.overlayruntime.utils

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import android.util.Log

class Logger {
    fun d(message: String) { if (com.msp1974.vacompanion.BuildConfig.DEBUG) Log.d("VACA_Vision", message) }
    fun i(message: String) { Log.i("VACA_Vision", message) }
    fun w(message: String) { Log.w("VACA_Vision", message) }
    fun e(message: String) { Log.e("VACA_Vision", message) }
}
