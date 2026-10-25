package com.msp1974.vacompanion.device

import android.annotation.SuppressLint
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.FirebaseManager
import com.msp1974.vacompanion.utils.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

enum class ScreenOnMode {
    ON, ON_DARK, OFF
}

class ScreenUtils (val context: Context, val config: APPConfig) : ContextWrapper(context) {
    var log = Logger()
    private val firebase = FirebaseManager.getInstance(context)

    private var wakeLock: PowerManager.WakeLock? = null

    suspend fun setScreenMode(mode: ScreenOnMode, window: Window, isDeviceAdmin: Boolean, setOverlay: (Boolean) -> Unit) {
        log.i("ScreenUtils - Setting screen mode: $mode")
        when (mode) {
            ScreenOnMode.ON -> {
                setOverlay(false)
                setScreenAlwaysOn(window, config.screenAlwaysOn)
                applyBrightness(window)
                setScreenTimeout(config.screenTimeout)
                wakeScreen()
            }
            ScreenOnMode.ON_DARK -> {
                setOverlay(true)
                setScreenAlwaysOn(window, true)
                setScreenBrightness(window, MIN_BRIGHTNESS)
                wakeScreen()
            }
            ScreenOnMode.OFF -> {
                if (isDeviceAdmin) {
                    setPartialWakeLock()
                    lockScreen()
                } else {
                    log.d("Simulating screen off via timeout and black overlay")
                    setPartialWakeLock()
                    setScreenAlwaysOn(window, false)
                    setScreenBrightness(window, MIN_BRIGHTNESS)
                    setOverlay(true)

                    if (!setScreenTimeout(1000)) {
                        // Without WRITE_SETTINGS (e.g. Fire OS) the screen timeout cannot be
                        // shortened, so stay dimmed behind the black overlay until the next
                        // screen-on, which restores brightness and removes the overlay.
                        log.d("Cannot change screen timeout; staying dimmed with overlay")
                        return
                    }
                    try {
                        // Wait up to 15 seconds for the screen to actually turn off
                        delay(1000) // Give it a second to start dimming
                        withTimeout(15000) {
                            while (!isScreenOff()) {
                                delay(200)
                            }
                        }
                        log.d("Screen verified as OFF")
                    } catch (e: Exception) {
                        log.w("Simulated sleep interrupted or timed out: $e")
                    } finally {
                        // Restore original values so the next wake event is clean
                        // Doing this while the screen is off prevents the "1 second turn off"
                        // from happening immediately on the next wake.
                        setScreenTimeout(config.screenTimeout)
                        applyBrightness(window)
                        setOverlay(false)
                        log.d("Screen settings restored for next wake")
                    }
                }
            }
        }
    }

    /**
     * Brightness is controlled on this app's window rather than the global system setting, so it
     * works without WRITE_SETTINGS (which Fire OS has no screen to grant) and does not fight the
     * system's own automatic brightness. "Auto" hands control back to the system brightness.
     */
    fun applyBrightness(window: Window) {
        if (config.screenAutoBrightness) {
            setScreenBrightness(window, WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        } else {
            setScreenBrightness(window, config.screenBrightness.coerceIn(MIN_BRIGHTNESS, 1f))
        }
    }

    fun setScreenBrightness(window: Window, brightness: Float) {
        try {
            val layout = window.attributes
            layout.screenBrightness = brightness
            window.attributes = layout
        } catch (e: Exception) {
            log.e("Error setting screen brightness: $e")
            firebase.logException(e)
        }
    }

    fun setScreenAlwaysOn(window: Window, state: Boolean) {
        // wake lock
        window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.decorView.keepScreenOn = state
        if (state) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    fun getDeviceBrightnessMode(): Int {
        try {
            return Settings.System.getInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE
            ) //this will return integer (0 or 1)
        } catch (e: Settings.SettingNotFoundException) {
            log.e("No screen brightness mode setting available")
            return -1
        }
    }

    fun setDeviceBrightnessMode(automatic: Boolean = false) {
        if (!canWriteScreenSetting()) {
            return
        }
        val mode = getDeviceBrightnessMode()
        try {
            if (automatic) {
                if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                    //reset back to automatic mode
                    Settings.System.putInt(
                        contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                    )
                }
            } else {
                if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                    //Automatic mode, need to be in manual to change brightness
                    Settings.System.putInt(
                        contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                    )
                }
            }
        } catch (e: SecurityException) {
            log.e("Error setting screen brightness mode: $e")
            firebase.logException(e)
        }
    }

    @SuppressLint("SourceLockedOrientationActivity")
    fun setScreenOrientation(activity: Activity, mode: String) {
        when (mode) {
            "auto" ->  activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
            "portrait" -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            "landscape" -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            "reverse_portrait" -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT)
            "reverse_landscape" -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)
        }
    }

    fun wakeScreen(lockDuration: Long = 500) {
        log.d("Acquiring screen on wake lock")
        
        // Activity-level wake flags
        (context as? Activity)?.let { activity ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                activity.setTurnScreenOn(true)
                activity.setShowWhenLocked(true)
            } else {
                activity.window?.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
                activity.window?.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            }
        }

        if (wakeLock != null && wakeLock!!.isHeld) {
            wakeLock!!.release()
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "vacompanion.ScreenUtils:wakeLock"
        )
        wakeLock?.acquire(lockDuration)
    }

    fun setPartialWakeLock() {
        if (wakeLock != null && wakeLock!!.isHeld) {
            wakeLock!!.release()
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "vacompanion.ScreenUtils:partialWakeLock"
        )
        wakeLock?.acquire()
    }

    fun lockScreen() {
        if (com.msp1974.vacompanion.utils.DeviceSupport.isPortal()) return
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.lockNow()
    }

    companion object {
        private const val MIN_BRIGHTNESS = 0.01f
    }

    fun canWriteScreenSetting(): Boolean {
        return Settings.System.canWrite(applicationContext)
    }

    fun isScreenOn(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }

    fun isScreenOff(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return display.state == Display.STATE_OFF
        } else {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            return wm.defaultDisplay.state == Display.STATE_OFF
        }
    }

    fun getScreenTimeout(): Int {
        return Settings.System.getString(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT).toInt()
    }

    fun setScreenTimeout(timeout: Int): Boolean {
        // EDA owns inactivity; FLAG_KEEP_SCREEN_ON remains available to this window.
        // Manual off without device-admin permission uses the existing dim overlay fallback.
        if (config.edaOwnsTimeout) return false
        if (canWriteScreenSetting()) {
            try {
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, timeout)
                return true
            } catch (e: Exception) {
                log.e("Error setting screen timeout: $e")
                return false
            }
        }
        return false
    }

    fun hideSystemUI(window: Window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).let { controller ->
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            val decorView: View = window.decorView
            decorView.setSystemUiVisibility(
                (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN)
            )
        }
    }
}
