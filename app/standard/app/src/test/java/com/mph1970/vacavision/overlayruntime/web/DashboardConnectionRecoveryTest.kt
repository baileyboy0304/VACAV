package com.mph1970.vacavision.overlayruntime.web

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import com.mph1970.vacavision.overlayruntime.settings.APPConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [25, 28])
class DashboardConnectionRecoveryTest {
    private fun fixture(test: (DashboardWebView, FrameLayout, MutableList<String>) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val root = FrameLayout(activity.get())
        activity.get().setContentView(root)
        APPConfig.getInstance(activity.get()).haUrl = "http://ha.test:8123"
        val messages = mutableListOf<String>()
        val web = DashboardWebView(activity.get()) { messages.add(it) }
        web.openDashboard("/view-assist/weather")
        root.addView(web)
        try { test(web, root, messages) }
        finally { root.removeView(web); web.destroy(); activity.pause().stop().destroy() }
    }

    @Test fun failedVisibleStartupReloadsOnlyOncePerPresentation() = fixture { web, root, messages ->
        assertTrue(web.isShown)
        repeat(3) { web.handleConnectionResult(0, false) }
        assertEquals(1, messages.count { it.contains("retrying") })
        root.removeView(web)
        root.addView(web)
        web.handleConnectionResult(0, false)
        assertEquals(2, messages.count { it.contains("retrying") })
    }

    @Test fun hiddenDetachedAndStaleCallbacksCannotReloadDashboard() = fixture { web, root, messages ->
        web.visibility = View.INVISIBLE
        web.handleConnectionResult(0, false)
        web.visibility = View.VISIBLE
        root.removeView(web)
        web.handleConnectionResult(0, false)
        root.addView(web)
        Shadows.shadowOf(web).webViewClient.onPageStarted(web, web.url!!, null)
        web.handleConnectionResult(0, false)
        assertTrue(messages.isEmpty())
        web.handleConnectionResult(1, false)
        assertEquals(1, messages.size)
    }

    @Test fun successfulConnectionIsReportedOnceAndDoesNotReload() = fixture { web, _, messages ->
        repeat(3) { web.handleConnectionResult(0, true) }
        assertEquals(listOf("Dashboard connected"), messages)
    }

    @Test fun untrustedDocumentCannotTriggerRecovery() = fixture { web, _, messages ->
        web.loadUrl("http://untrusted.test/view-assist/weather")
        web.handleConnectionResult(0, false)
        assertTrue(messages.isEmpty())
    }
}
