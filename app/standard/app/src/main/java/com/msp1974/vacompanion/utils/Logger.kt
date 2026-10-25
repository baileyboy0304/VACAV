package com.msp1974.vacompanion.utils

import android.content.Context
import timber.log.Timber

class Logger {
    companion object {
        const val TAG = "ViewAssistCA"
    }
    fun d(message: String) {
        Timber.tag(TAG).d(message)
    }
    fun e(message: String) {
        Timber.tag(TAG).e(message)
    }
    fun i(message: String) {
        Timber.tag(TAG).i(message)
    }
    fun w(message: String) {
        Timber.tag(TAG).w(message)
    }
}

/**
 * Firebase has been removed from this build (Fire OS devices have no Google Play services, so it
 * could only retry failed uploads in the background). The API is kept so existing call sites
 * still compile; exceptions are logged locally instead.
 */
class FirebaseManager private constructor() {

    companion object {
        private val instance = FirebaseManager()

        @Suppress("UNUSED_PARAMETER")
        fun getInstance(context: Context? = null): FirebaseManager = instance

        const val DIAGNOSTIC_POPUP_SHOWN = "diagnostic_popup_shown"
        const val SATELLITE_ALREADY_RUNNING_MAIN = "satellite_already_running_main"
        const val RENDER_PROCESS_KILLED = "render_process_killed"
        const val RENDER_PROCESS_CRASHED = "render_process_crashed"
        const val MAIN_ACTIVITY_BACKGROUND_TASK_ALREADY_RUNNING = "main_background_task_already_running"
        const val TRIM_MEMORY_UI_HIDDEN = "trim_memory_ui_hidden"
        const val TRIM_MEMORY_BACKGROUND = "trim_memory_background"
        const val LOST_NETWORK = "lost_network"
    }

    fun setCustomKeys(keys: Map<String, Any>) {}

    fun logEvent(event: String, params: Map<String, String>) {
        Timber.tag(Logger.TAG).d("Event: $event $params")
    }

    fun setUserProperty(key: String, value: String) {}

    fun addToCrashLog(message: String) {}

    fun logException(exception: Exception) {
        Timber.tag(Logger.TAG).w(exception)
    }
}
