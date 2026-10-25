# VACAVision

VACAVision connects an Android display to Home Assistant and View Assist. On an Echo Show 8 Gen 2, Echo Dot Assist supplies Amazon wake-word detection, audio processing, Assist audio and Sendspin playback; VACAVision supplies the display companion and HA integration.

## Install the Home Assistant integration with HACS

View Assist and Music Assistant, including its official Home Assistant integration, must already be operational for the Echo Show setup.

1. Open **HACS → ⋮ → Custom repositories**.
2. Add `https://github.com/baileyboy0304/VACAV` with type **Integration**.
3. Find **VACAVision**, download it and restart Home Assistant.
4. Install the Android app using your device installer. HACS installs the HA integration; it does not unlock the Echo or install the Android app.

For the Echo Show 8 Gen 2, follow the [ES8_Athena installer guide](https://github.com/baileyboy0304/ES8_Athena). That repository currently requires access from its owner. Unlocking and Echo Dot Assist installation are documented by [Echo Dot Assist, checkers branch](https://github.com/Gamer92000/echo-dot-assist/tree/checkers).

## Connect an installed Echo Show

1. Pair the Echo's Sendspin player in Music Assistant if you have not already done so. The pairing token is on the Echo Dot Assist settings page at `http://DEVICE_IP:28931/`.
2. Open **Settings → Devices & services** in HA and select **Add** on the discovered **VACA_Vision** card.
3. Confirm the device name. The integration detects the Echo Show role, voice port and physical Sendspin player. It enables that player's exposure to HA and sets up encrypted voice access.
4. Select the Assist pipeline on the new device page and sign into HA on the Echo Show touchscreen.

If discovery does not appear, choose **Add integration → VACA_Vision → Connect an installed device by IP address** and enter the Echo's IP. The integration tries the Echo Show app port automatically. Custom ports are available through advanced options.

The Linux installer passes the device name and startup dashboard to HA. Without that installer handoff, the display starts at `/lovelace/0`; change it through the integration's configuration options.

If the integration cannot uniquely match a player by device IP or MAC address, it asks you to select a **named physical Sendspin player**. It never guesses from similar device names or selects a sync group. If none is available, pair the Echo in Music Assistant, then retry. No raw player ID is required.

Encryption is automatic for a new device, and a stored key is reused. Only a device already secured with an unknown key needs a key entered manually. Obtain that existing API encryption key from the Echo Dot Assist settings or your original setup records. HA stores adopted keys privately; include HA's configuration in your backups. Keys are not printed into installer logs.

## View Assist audio settings

Use the **official Music Assistant entity for this physical Echo Show** for View Assist's music player and media/alerts player. Do not select VACAVision's Music proxy or a similarly named player belonging to another device. A sync group can include the physical player and a lyrics player; retain the physical player in View Assist's settings so group-aware automations can resolve its membership.

Set the View Assist microphone to this device's `assist_satellite` entity and the display to the value of its display sensor. On the Echo Show, VACAVision's own Sendspin engine is disabled: Echo Dot Assist owns the Sendspin endpoint.

## Source and acknowledgements

The integration is in `custom_components/vaca_vision/`, including its installation payloads. Android source is in `app/`.

This variant is based on [MSP1974's View Assist Companion App](https://github.com/msp1974/ViewAssistCompanionApp) and works with [Dinki's View Assist](https://github.com/dinki/View-Assist) ([documentation](https://dinki.github.io/View-Assist/)). Original authors do not maintain or endorse these variant-specific changes. See `LICENSE`, `NOTICE`, and the retained app/component licenses.

Run setup-flow tests with `pip install -r requirements-dev.txt` followed by `python -m unittest discover -s tests -v`.
