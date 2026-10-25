package com.msp1974.vacompanion.utils;

/** Exact, English-language compatibility dialog only. No account or playback errors. */
final class ItvxWarningPolicy {
    static final String PACKAGE = "air.ITVMobilePlayer";
    static final String MESSAGE = "ITVX won't run without Google Play services, which are not supported by your device.";

    static boolean matches(String packageName, String className, Iterable<CharSequence> text, long ageMs) {
        if (!PACKAGE.equals(packageName) || ageMs < 0 || ageMs > 1000 || text == null) return false;
        if (!"android.app.Dialog".equals(className) && !"android.app.AlertDialog".equals(className)
                && !"androidx.appcompat.app.AlertDialog".equals(className)) return false;
        for (CharSequence value : text) {
            if (value != null && MESSAGE.equals(value.toString().trim())) return true;
        }
        return false;
    }
}
