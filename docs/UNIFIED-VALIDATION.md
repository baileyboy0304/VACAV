# Unified APK validation — 2026-10-27

App: `0.3.0-unified-preview.1` (version code 20).
Integration: `0.6.0`.
APK SHA-256: `974d403b0a462c2688936ea911fc98c7274ecab66ec4badf2096ee37f73c2e7e`.
Signer certificate SHA-256: `db9fc8d620b6bc43bb79b30aa5b1eed0616acc6c62c0c5163696f4992afe3304`.
The certificate matches the supplied prototype.13 APK. The keystore remains external.

## Automated checks

- Unified APK assembled successfully with the current standard Gradle toolchain.
- 72 Android tests passed (Java 21 test runtime): ES8 double-tap/voice lease/streaming
  policy plus imported overlay picker, remote focus, alarm, layout and URL tests;
  new mode selection, persistence and capability checks.
- 32 integration/installer tests passed, including a single bundled APK, checksum
  verification, tamper rejection, Fire TV + Dot selecting overlay mode and standard
  installations selecting standard mode without uninstalling.
- 52 ES8/Linux installer regression tests passed.

## Live Echo Show 8

- Updated prototype.13 in place with the supplied Linux signing key; no uninstall.
- The existing HA device reconnected as `standard_upstream_eda` with its existing
  saved installation and display identity. Only the standard display service ran.
- Dashboard rendered normally after update.
- Opened Silk, double-tapped outside the app, and visually verified the Home,
  Keep on and Brightness popup. Tapping Home returned to the VACAV dashboard.
- Echo Dot Assist remains the native audio owner; the app still advertises
  `native_audio=false` and does not start the overlay or Dot playback runtime.
- Existing voice/audio implementation was retained. A new spoken wake-word session
  and physical acoustic output were not independently assessed by the automation.

## Live Fire TV

- Installed the identical APK after the owner-authorized signing-key migration.
- Privately backed up the old APK, preferences and WebView data; restored the
  existing device identity, dashboard configuration, picker and browser state.
- HA reconnected to the existing `overlay_echo_dot` entry. Paired Echo connected;
  Sendspin streaming and reply playback were available.
- Built-in picker launcher opened the transparent picker. Remote navigation selected
  a dashboard. The original Home broadcast handler dismissed the overlay and returned
  focus to the Fire TV launcher. Accessibility remained disabled, as before migration.
- Two short HA Assist announcements completed successfully, including a repeat with
  the VPN connected. Music ducking returned to gain 1 after announcement completion.
- The reinstall changed Android's app UID. IPVanish retained an old UID in its active
  tunnel despite the package being selected for exclusion. Refreshed only VACAV's
  existing exclusion and reconnected the same Austria destination. Confirmed the new
  UID is outside the active VPN's UID ranges. This restored HA HTTP/dashboard/TTS access.
- A physical remote Home-key test and listening assessment remain user checks; ADB
  key injection did not consistently reproduce the physical Home broadcast.

No private device backups, browser state, account credentials, screenshots or signing
keys are included in this repository. Live compatibility used the already-running
HA integration; publishing this release does not restart Home Assistant.
