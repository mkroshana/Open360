# Open360 Privacy Policy

**Effective date:** 2026-11-26  
**Canonical URL:** <https://github.com/mkroshana/Open360/blob/main/PRIVACY.md>  
**Source code:** <https://github.com/mkroshana/Open360>

Open360 is an independent, open-source companion app for Android and Wear OS built for controlling compatible Insta360 cameras and recording local GPS and sports-sensor telemetry.

## 1. Zero Cloud Collection, Tracking, or Ads

Open360 operates entirely on your own devices and over local, user-initiated connections to your camera and Bluetooth sports sensors:

- **Zero analytics** — no usage telemetry, event tracking, or behavioral profiling.
- **Zero crash reporting SDKs** — no third-party crash/error collection libraries.
- **Zero advertisements** — no ad networks, ad identifiers, or monetization SDKs.
- **Zero remote or cloud servers** — Open360 never uploads your location, health/sensor readings, camera identifiers, media files, or preferences to developer-operated or third-party servers.

## 2. On-Device Permissions & Data Usage

All data processed by Open360 remains strictly on your phone, watch, and camera (or in folders you explicitly choose to share or export into):

### Location (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE_LOCATION`)
- Used solely to acquire satellite GPS fixes (`GPS_PROVIDER`) for real-time GPS metadata injection (`UploadGps`) into camera recordings and to export local `.gpx` and `.fit` track files in `Download/Open360`.
- When **Background GPS Tracking** is enabled or while an active camera recording/link is maintained with the screen off, `GpsTrackerService` runs as a user-visible foreground service (`location`) so GPS updates continue uninterrupted.
- `BLUETOOTH_SCAN` and `NEARBY_WIFI_DEVICES` are declared with `neverForLocation` and are not used to derive location.

### Health / Body Sensors (`BODY_SENSORS`, `FOREGROUND_SERVICE_HEALTH`, BLE Sports Sensors)
- On Wear OS, if you grant `BODY_SENSORS` permission and enable watch heart rate logging, Open360 reads the watch's built-in heart rate sensor (`Sensor.TYPE_HEART_RATE`) only while the camera is active (on and awake) and records BPM readings locally into your `.fit` activity files.
- On both phone and watch, if you pair external Bluetooth Low Energy sports sensors (Heart Rate `0x180D`, Cycling Speed & Cadence `0x1816`, Cycling Power `0x1818`), those readings are kept in memory during the session and written locally into your `.fit` track files (`Download/Open360/*.fit`) for optional import into desktop video editors (such as Insta360 Studio).
- Health and fitness sensor data is **never** sent to cloud servers, analytics services, or third parties.

### Bluetooth, Nearby Wi-Fi, Local Network, & USB OTG
- **Bluetooth (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, legacy `BLUETOOTH` / `BLUETOOTH_ADMIN`)**: Direct local BLE discovery, camera wake-up authorization, shutter/mode control, GPS metadata streaming, optional Bluetooth Classic SPP (`ONE X`) / GATT remote emulation (`0xCE80` / `0xD0FF`), and external BLE sports sensor pairing.
- **Wi-Fi & Local Network (`NEARBY_WIFI_DEVICES`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_NETWORK_STATE`, `INTERNET`, `ACCESS_NETWORK_STATE`)**: Direct local connection to the camera's Wi-Fi hotspot (typically `192.168.42.1`) or user-configured local LAN/hotspot IP for Live View video streaming, file listing, and media downloads. Open360 makes no outbound internet requests.
- **USB Host (`android.hardware.usb.host`)**: Optional wired USB-C / OTG direct camera communication when attached by the user.

### Storage & Storage Access Framework (`OpenDocumentTree`, `MediaStore.Downloads`)
- **App-owned Downloads (`MediaStore.Downloads`)**: Recorded `.gpx` / `.fit` tracks and downloaded camera clips/photos are stored locally in `Download/Open360` (on Android 10+, scoped via `MediaStore.Downloads`; on Android 8.0–9, via app-scoped external storage or legacy `READ_EXTERNAL_STORAGE` where applicable).
- **User-selected folders (`ActivityResultContracts.OpenDocumentTree`)**: In the Footage tab's USB / Folder view, Open360 accesses only the specific directory tree you manually grant via Android's system folder picker to browse or import clips/files locally.
- **Share intents**: Sharing a clip or track uses Android `FileProvider` only when you explicitly tap Share.

### Notifications & Wake Lock (`POST_NOTIFICATIONS`, `WAKE_LOCK`)
- Used only to display the required foreground service status notification during active camera connections/GPS tracking and keep the CPU awake only while a camera link is actively answering.

### Wear OS Data Layer (`play` Flavor Only)
- In the Google Play distribution (`play` flavor + Wear OS app), Open360 uses the local **Wear OS Data Layer** (`play-services-wearable`, constrained to matching `applicationId` and signing key) exclusively to sync between your paired phone and watch:
  - Camera wake-up authorization ID and camera serial suffix (`SharedConnectionSettings`),
  - Live remote control commands and connection snapshots (`PeerLink`),
  - Watch-recorded `.gpx` and `.fit` track files (`TrackHandover` → `WatchTrackReceiver`), which are saved into `Download/Open360` on the phone and deleted from the Data Layer buffer once written.
- The **F-Droid (`foss`)** flavor removes `play-services-wearable` completely and contains no Google Play Services dependencies.

## 3. Data Retention & Deletion

All settings (wake-up authorization ID, camera serial suffix, connection preferences, saved sensor MAC addresses) and media/track files reside exclusively on your devices:
- Delete downloaded clips, `.gpx`, and `.fit` tracks directly inside the **Footage** tab or from `Download/Open360` using any file manager.
- Clear or replace your wake-up ID, camera serial, or paired sports sensors at any time under **Settings**.
- Revoke Storage Access Framework folder grants or runtime permissions (Location, Bluetooth, Nearby Devices, Body Sensors) in Android **App info → Permissions**.
- Clear app data or uninstall Open360 to remove all stored app preferences.

## 4. Children's Privacy

Open360 is a camera utility not directed at children under 13 (or the minimum age of digital consent in your jurisdiction) and does not knowingly collect personal information from anyone.

## 5. Policy Updates & Contact

Updates to this policy will be committed to this document in the public repository. For privacy questions, bug reports, or security disclosures, please open an issue at:

- <https://github.com/mkroshana/Open360/issues>
