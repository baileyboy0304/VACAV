package com.msp1974.vacompanion.utils

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** On-demand status checks off the UI thread. No root shell, content access or retained diagnostics. */
internal class StreamingPlayerProbe(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var closed = false

    fun check(result: (Boolean) -> Unit) {
        if (closed || !busy.compareAndSet(false, true)) return
        worker.execute {
            val eligible = runCatching {
                val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                if (!power.isInteractive || keyguard.isKeyguardLocked ||
                    context.checkSelfPermission(Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED) false
                else {
                    // Fire OS restricts window_service discovery, even with DUMP.
                    // Input's window metadata is available with the same permission.
                    val window = status("input")
                    val metrics = DisplayMetrics()
                    (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
                    val player = StreamingPlayerPolicy.focusedPlayer(window, metrics.widthPixels, metrics.heightPixels)
                    player != null && StreamingPlayerPolicy.eligible(window,
                        if (player == "com.amazon.cloud9") status("media_session") else "",
                        metrics.widthPixels, metrics.heightPixels)
                }
            }.getOrDefault(false)
            handler.post {
                busy.set(false)
                if (!closed) result(eligible)
            }
        }
    }

    private fun status(vararg args: String): String {
        val process = ProcessBuilder(listOf("/system/bin/dumpsys", "-t", "1") + args)
            .redirectErrorStream(true).start()
        val timeout = Runnable { process.destroy() }
        handler.postDelayed(timeout, 1500)
        try {
            return process.inputStream.bufferedReader().use { reader ->
                val output = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    check(output.length + line.length < 1_048_576)
                    output.append(line).append('\n')
                }
                check(process.waitFor() == 0)
                output.toString()
            }
        } finally {
            handler.removeCallbacks(timeout)
            process.destroy()
        }
    }

    fun close() {
        closed = true
        worker.shutdownNow()
    }
}
