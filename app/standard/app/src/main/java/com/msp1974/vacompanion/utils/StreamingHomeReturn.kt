package com.msp1974.vacompanion.utils

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.view.KeyEvent
import timber.log.Timber

/** Portal keeps some video players running after HOME hides their activity. */
object StreamingHomeReturn {
    private const val PREFS = "streaming_home_return"
    private const val PENDING_APP = "pending_app"
    private val streamingPackages = setOf(
        "com.netflix.mediaclient", "com.netflix.mediaclient.echo", "bbc.iplayer.android",
        "air.ITVMobilePlayer", "com.apple.atve.androidtv.appletv", "com.amazon.cloud9"
    )

    fun launched(context: Context, packageName: String?) {
        if (!(Build.MODEL.startsWith("Portal", ignoreCase = true) || Build.DEVICE == "athena")) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().apply {
            if (packageName in streamingPackages) putString(PENDING_APP, packageName)
            else remove(PENDING_APP)
        }.apply()
    }

    fun onHomeIntent(context: Context, intent: Intent?) {
        if (!(Build.MODEL.startsWith("Portal", ignoreCase = true) || Build.DEVICE == "athena") ||
            intent?.action != Intent.ACTION_MAIN ||
            !intent.hasCategory(Intent.CATEGORY_HOME)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val packageName = prefs.getString(PENDING_APP, null) ?: return
        clear(context)
        if (packageName !in streamingPackages) return
        // Use PAUSE, never a toggle: an already paused video must remain paused.
        // Android routes this to its current media session. Only send it for a HOME
        // return from a tracked video launch, never for ordinary dashboard resumes.
        try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            Timber.i("Paused streaming on Portal HOME return from %s", packageName)
        } catch (e: RuntimeException) {
            Timber.w(e, "Unable to pause streaming on HOME return")
        }
    }

    fun clear(context: Context) {
        // A return through Back, Recents, or an HA command ends this handoff too.
        // A later HOME press must not act on an unrelated music session.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(PENDING_APP).apply()
    }
}
