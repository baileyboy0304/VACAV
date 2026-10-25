package com.msp1974.vacompanion.utils;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/** Opt-in service: exact dialog event -> Back. Cannot retrieve window contents or inject gestures. */
public final class ItvxWarningService extends AccessibilityService {
    private long lastDismissed = -10000;

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (!getSharedPreferences("assist", 0).getBoolean("dismiss_itvx_google_warning", true)) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastDismissed < 2000) return;
        if (!ItvxWarningPolicy.matches(String.valueOf(event.getPackageName()), String.valueOf(event.getClassName()),
                event.getText(), now - event.getEventTime())) return;
        // Act immediately on a fresh dialog event; never schedule a later Back on a different screen.
        lastDismissed = now;
        boolean dismissed = performGlobalAction(GLOBAL_ACTION_BACK);
        Log.i("AthenaItvxWarning", "Exact Google services warning: Back " + (dismissed ? "sent" : "refused"));
    }

    @Override public void onInterrupt() { }
}
