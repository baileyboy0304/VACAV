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

Pair the Echo's Sendspin player in Music Assistant if you have not already done
so. The pairing token is on the Echo Dot Assist settings page at
`http://DEVICE_IP:28931/`.

**1. Add the discovered device.** Open **Settings → Devices & services**.
Under **Discovered**, select **Add** on the **VACA_Vision** card.

<img src="docs/images/setup/00-discovered-device.png" alt="VACA_Vision discovered card with Add button" width="618">

If you already have VACA_Vision devices, you can also open the integration and
select **Add** on its **Discovered** row.

<img src="docs/images/setup/01-discovered-integration.png" alt="Discovered VACA_Vision device within the integration page" width="593">

**2. Confirm the device name**, then select **Submit**. The Echo Show role,
dashboard and voice connection are selected automatically. VACAVision connects
the matching Music Assistant player and exposes it to Home Assistant.

<img src="docs/images/setup/02-confirm-discovered.png" alt="Add this Echo Show: confirm the device name and submit" width="313">

<details>
<summary>If the Echo Show does not appear under Discovered</summary>

Check that VACAVision is running and HA can reach the Echo Show's IP address.
Select **Add integration**, search for `VACA`, and choose **VACA_Vision**.
If you already have the integration, open it and select **Add device**.

<img src="docs/images/setup/01-select-integration.png" alt="Add integration search results: choose VACA_Vision" width="338">

Choose **Connect an installed device by IP address**.

<img src="docs/images/setup/03-connect-menu.png" alt="Connect VACAVision menu with the IP-address connection option" width="314">

Enter the Echo Show's IP address printed by the installer and select **Submit**.
If **Vision port** is shown, leave it blank for automatic detection; only enter
a port for a custom installation. The screenshot shows the usual Echo Show port,
`26900`.

<img src="docs/images/setup/04-manual-address.png" alt="Enter the Echo Show IP address; Vision port can be left blank" width="311">

Confirm the device name and select **Submit**.

<img src="docs/images/setup/05-confirm-manual.png" alt="Confirm the device name after connecting by IP address" width="314">

Continue with selecting the voice assistant below.

</details>

**3. Select the voice assistant.** Open the new device page and select its Assist
pipeline. Then sign into HA on the Echo Show touchscreen.

The Linux installer writes non-secret choices into the Echo’s private app folder
over USB. VACAVision reads those choices when you add the Echo: name, dashboard
names, selected streaming services and public Sendspin identity. No HA credentials
are sent to the installer or the Echo through this handoff.

After adding the Echo, sign into HA on its touchscreen and complete its voice
assistant setup. Open **VACAV Setup** in the HA sidebar. This administrator-only
page uses your existing browser session to check connections, configure View
Assist and install the example dashboards. It detects the MA sidebar path and
inherits weather where possible. It backs up existing dashboards before asking
HA to replace them; replacement needs your confirmation. Dashboard frontend assets
are included and served by this integration. Install `custom:button-card` first.

Initial browser sign-in opens HA’s built-in `/lovelace/0` dashboard. Finished
Athena dashboards are selected only after setup. An existing device added before
0.4.0 needs the current Linux installer’s USB handoff; the setup page offers to
read those choices without removing the existing device.

If no physical player matches, select it by name after pairing it in Music
Assistant. No raw player ID is required. Encryption is provisioned automatically;
a stored key is checked against the device before reuse. A wiped Echo can be
adopted again only after its hardware identity and initial-provisioning state are
verified. Connectivity failures never cause a key reset. Key storage is private.

The bundled app also supports a double tap to open Home/Keep on/Brightness over
other apps outside playback. Taps still reach the app underneath. Single-tap
playback controls and automatic hiding remain available.

## View Assist audio settings

Use the **official Music Assistant entity for this physical Echo Show** for View Assist's music player and media/alerts player. Do not select VACAVision's Music proxy or a similarly named player belonging to another device. A sync group can include the physical player and a lyrics player; retain the physical player in View Assist's settings so group-aware automations can resolve its membership.

Set the View Assist microphone to this device's `assist_satellite` entity and the display to the value of its display sensor. On the Echo Show, VACAVision's own Sendspin engine is disabled: Echo Dot Assist owns the Sendspin endpoint.

## Updating older Android builds

The prototype.11 APK uses the Linux build’s signing certificate. The previously
bundled prototype.10 APK used a different certificate. Android cannot install this
APK over that build; the installer stops rather than deleting app data. An upgrade
needs the previous signing key or an explicitly chosen reinstall and HA browser
sign-in. Fresh installations can use the new APK directly.

## Source and acknowledgements

The integration is in `custom_components/vaca_vision/`, including its installation payloads. Android source is in `app/`.

This variant is based on [MSP1974's View Assist Companion App](https://github.com/msp1974/ViewAssistCompanionApp) and works with [Dinki's View Assist](https://github.com/dinki/View-Assist) ([documentation](https://dinki.github.io/View-Assist/)). Original authors do not maintain or endorse these variant-specific changes. See `LICENSE`, `NOTICE`, and the retained app/component licenses.

Run setup-flow tests with `pip install -r requirements-dev.txt` followed by `python -m unittest discover -s tests -v`.
