# Preview.39 / integration 1.1.0 — picker profiles

The integration suite passed in two batches (43 plus 24, total 67 tests). Coverage includes persistent profile storage, independent exported copies, revision/name conflicts, disk write failure, invalid/credential-bearing imports, correlated device acknowledgements, disconnect without replay, app-to-library requests, real authenticated WebSocket upload/download/delete, and rejection of a non-admin user. An initial admin test referenced an unavailable fixture; using the provided read-only access token fixture corrected it. Ruff checks and formatting passed. Added the matching HA frontend test dependency for actual panel registration.

Android validation was limited to 10 profile checks across API 25/28 and the release build. At the user's request, no further physical device, visual browser or audio tests were run. The paired artifacts are for the user's own testing; nothing was deployed to live HA.

# Preview.15 / integration 1.0.4 — automatic TV resume

97 Android tests passed on API 25/28; 48 Home Assistant integration tests passed. Release build passed; lint reports 0 errors and 158 warnings; Ruff checks passed.

The full API 25 runtime check passed using the actual integration bridge and a foreground video fixture without a MediaSession. It verified automatic pause before music, Sendspin Stop resuming video once, natural direct-media end resuming once, explicit Stop during a longer clip resuming once, and no second toggle when stopping an already-ended clip. Picker, BLE setup, live settings and overlay checks also passed. Evidence: release/validation/preview15-resume-final/runtime-results.json in the Android project.

The first runtime run passed Sendspin resume and natural clip-end resume, then failed an assertion that incorrectly expected another resume after the one-second clip had already finished. The revised test requires no second toggle and separately stops a twenty-second clip before its end. That earlier evidence is retained in preview15-resume.

Unit checks cover successful pause ownership, stale/failed acknowledgements, one-use resume tickets, foreground changes, music restarting, disabled/older integration, timeout, cancellation and settings persistence. No retries occur after uncertain remote delivery.

The feature requires both preview.15 and integration 1.0.4. The Cube APK was updated; the integration is provided for installation/restart by the user. Physical automatic resume with the updated HA pair remains to be tried. It still uses the Fire TV remote Play/Pause toggle over ADB; the key dispatch is acknowledged but exact video state cannot be proved for an app without a media session. Rapid user changes during delivery remain a limitation.

The new default is Resume Fire TV: After pause or stop; the picker also offers After stop/end and Off. App/HA restarts clear ownership; stopping the whole VACA service does not resume video. Audio rendering, buffer sizing and SDK protocol are unchanged from preview.14.

## Earlier validation

# Preview.14 / integration 1.0.3 audio validation — 15 September 2026

85 Android unit tests and 46 Home Assistant integration tests passed. Release build passed; Android lint reports 0 errors and 155 warnings. Ruff checks passed. Physical Gen3 Cube comparison showed that repeated hard timing corrections stopped; the user confirmed clear audio after preview.14 was installed. Detailed physical measurements are in the Android project docs/AUDIO-PREVIEW14.md. SDK protocol code was unchanged; its previous 169-test result was not rerun for this patch.

## Earlier validation

# VACA_Vision preview.13 / Overlay integration 1.0.2 validation — 15 September 2026

- **81 Android tests passed**, zero failures/errors, on API 25/28. Tests cover asynchronous preparation, fresh audio checks, cancellation, timeout/late replies, disabled/idle/own-playback cases, direct-media Stop during preparation and audio focus compatibility.
- **42 integration tests passed**, against the existing Home Assistant 2026.9.2 / Python 3.14.4 environment. New tests cover key 85, commit-before-send, missing/rejected ADB authorisation, expiry/cancellation, no retries on uncertain delivery, duplicate requests, picker/alarm window guards and persisted ADB ports. Ruff checks pass.
- Debug and signed release builds pass; minSdk **25**. Android lint: **0 errors, 155 warnings**.
- Physical **Gen3 Cube** (private address omitted during packaging): user approved one key test while their HBO video was playing. After stopping the old VACA app, `input keyevent 85` changed the foreground video's AudioTrack from **started to paused**. Evidence: `release/validation/preview13-firetv-pause.json`. The foreground player package was `com.leaguemod.leaguemodiptvbox`.

## Routing evidence and limits

The initial API 25 foreground-only run failed to receive media keys while the test had left the picker focused. That was not a valid test of the video app's key handling. A later isolated check with the fixture actually in front and no media session confirmed direct receipt of key 85 (`release/validation/preview13-foreground-only.txt`). The initial failure remains in `release/validation/preview13-api25`; no platform limitation is inferred from it.

The standard Android test explicitly enables the competing fixture's media session. It runs the actual integration ADB bridge against the emulator, checks automatic Pause before Sendspin/direct output, two physical remote key presses that resume/pause the competing player without Sendspin commands, and the live picker Off case. The final complete run **passed** and uses 15 seconds of PCM and checks existing overlay, alarm, settings and BLE setup behaviour; 90-second PCM playback had already completed in the earlier clean run. See `release/validation/preview13-final/runtime-results.json` and the fixture README.

Two interrupted UI runs are retained under `preview13-api25-standard` and `preview13-api25-final`. UIAutomator once failed to provide a readable hierarchy; the interrupted test then left a persistent alarm covering the next setup screen. The harness now verifies hierarchy creation and clears only the explicitly selected emulator test app's data before each run. Assertions about alarm persistence and pause receipt were not removed.

The `preview13-api25-clean` run completed 90-second PCM playback, automatic Pause for both sources and physical-key routing, then failed to reopen settings because blocking ADB UI commands starved the mock HA event loop and its heartbeat timed out. Emulator commands now run in worker threads; the actual HA integration already keeps ADB work off its event loop. The assertions and protocol heartbeat limits remain unchanged. Read-only UI hierarchy retrieval also retries transient ADB shell exit 255 failures; media/input commands are never retried.

The `preview13-api25-verified` run correctly skipped automatic Pause because the test had left the picker focused over the competing app. The harness now dismisses that picker before starting TV playback. The application's foreground guard is retained; the test still requires actual Pause receipt.

No APK or integration was installed on the production Cube or live Home Assistant. The full updated pair still needs the user's hardware trial; the physical test proves the remote key route, while the emulator tests the automatic protocol. The old VACA service was stopped for the approved Cube test. The key is a toggle, so external state changes at the instant of delivery and video without detectable audio remain limits. ADB result `sent` acknowledges dispatch, not the video app's playback state.

The SDK and PCM renderer were unchanged. Their earlier **169 SDK tests** were not rerun for this routing patch. The prior encrypted-protocol and acoustic multiroom limitations remain recorded in SENDSPIN-AUDIT.md. No white-screen/login changes were made.


## Earlier integration validation

# Overlay integration 1.0.1 / Android preview.9 validation — 15 September 2026

Paired with **VACA_Vision 1.0.1**, HA folder/domain **vaca_vision**.

- Android: **57 tests passed**, including API 25/28 coverage and idle-handshake status reporting.
- Sendspin SDK: **164 tests passed**, including corrected static-delay message serialisation and command handling.
- Integration: **33 tests passed** against Home Assistant **2026.9.2**, Python **3.14.4**, Wyoming **1.10.0**. New cases cover retained form values, authentication versus network errors, already-allowed permission and launch failure after permission succeeds.
- Debug and signed non-debuggable release builds passed. Minimum SDK **25**.
- Android lint: **0 errors, 151 warnings**. Integration Ruff lint and formatting checks passed.

## API 25 emulator

Command: `tools/runtime_smoke.py --serial emulator-5582 --release --fresh-login --first-remote-setup --sendspin-schema --output release/validation/preview9-api25`.

Passed automatic BLE setup, browser sign-in, quiet startup, clock timezone, dashboard transparency/fading, remote-dismissed persistent alarm, picker/settings using DPAD, BLE access, player toggles, direct URL audio, Sendspin group PCM playback, time sync and reconnect.

The test validates the APK's actual `client/state` messages with **aiosendspin 9.1.1**, the official server model used by current Music Assistant. It reproduces rejection of `set_output_delay`, accepts the corrected messages and checks a `set_static_delay` 250 ms command is acknowledged before resetting it to zero for playback. This is stronger than a permissive JSON fixture, but does not substitute for physical multiroom listening.

The actual integration ADB helper, using adb-shell and cryptography, changed emulator permission from denied to allowed, verified it and opened the app. Evidence: `adb-permission-preview9.log` and `release/validation/preview9-api25`.

## Diagnosis and limits

Read-only Music Assistant logs confirmed the user's disconnect: `ValueError: 'set_output_delay' is not a valid PlayerCommand`. The TV already reported overlay permission allowed when inspected. The previous flow swallowed the specific ADB error, so its original cause cannot be established retrospectively; the new flow reports the failed stage and retains retry inputs.

No live HA configuration or physical Cube/EchoLocal software was changed. Fire TV authorisation dialogs, BLE radio behaviour and acoustic synchronisation still need the hardware trial. The player retains the pinned SDK's legacy unencrypted handshake, which this Music Assistant instance accepts in transition mode. This patch does not implement the newer encrypted pairing protocol.

Run integration checks on Linux with `python -m pytest -q`, `ruff check custom_components tests` and `ruff format --check custom_components tests` using `requirements_test.txt`. Android evidence is in the sibling `V13Overlay` project.
