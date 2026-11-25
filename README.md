# Open360

An open-source Android and Wear OS companion app for controlling Insta360 cameras, embedding real-time GPS metadata into recorded clips, and capturing local GPX/FIT telemetry.

> **Unofficial App Disclaimer**  
> Open360 is an independent, community-developed open-source companion app. It is **not** affiliated with, endorsed by, authorized by, or sponsored by **Insta360** or **Arashi Vision Inc.** All product names, camera model names, logos, and trademarks (including Insta360, GO 3S, Wear OS, Pixel Watch, and Garmin FIT) belong to their respective owners.

---

## Features

- **Phone BLE Remote & Real-Time GPS Metadata Injection**  
  Connects directly to the camera over Bluetooth Low Energy (`0xBE80`), switches between Video/Photo and camera sub-modes, triggers capture, monitors battery/Action Pod/storage/remaining runtime, and streams paced 10 Hz `UploadGps` records synchronized against the camera's own clock so Insta360 Studio aligns GPS telemetry directly with video frames.
- **Standalone Wear OS Companion (`play` flavor)**  
  Control your camera directly from your wrist without carrying your phone on rides or runs—complete with watch GPS streaming, watch heart rate logging (`BODY_SENSORS`) exported to `.fit`, low-power Always-On ambient display, and seamless camera link + GPX/FIT track handover when paired with your phone.
- **Dedicated Live View**  
  Stream live camera viewfinder previews over Wi-Fi (`192.168.42.1:6666`) or experimental USB-C H.264 feeds with full-screen viewing.
- **Footage Browser & Wi-Fi / USB-C / SAF Import**  
  Browse clips and photos on the camera over Wi-Fi Camera AP (or experimental Hotspot/LAN), download full-resolution clips to `Download/Open360`, or import media from USB-C drives/SD cards via Android's Storage Access Framework (`OpenDocumentTree`), with built-in playback alongside companion `.gpx` routes.
- **External BLE Sports Sensors**  
  Pair standard Bluetooth Low Energy Heart Rate (`0x180D`), Cycling Speed & Cadence (`0x1816`), and Cycling Power (`0x1818`) sensors and export local `.fit` activity files alongside `.gpx` tracks in `Download/Open360`.
- **Material 3 Expressive UI**  
  Editorial dark (`#0E0E0D`) and warm light (`#F6F3EC`) Material 3 Expressive design with optional Android 12+ dynamic wallpaper theming, persistent camera status top bar, tabular numeric telemetry, and reduced-motion accessibility support.

---

## Hardware Verification Matrix

Open360 exposes a catalog of **20 Insta360 camera models** and **6 transport modes**, but **real hardware verification has only been performed on the Insta360 GO 3S and Google Pixel Watch 4**. Treat all other camera models and non-BLE/Camera-AP transports as experimental until community testing confirms them.

### Camera Models

| Status | Model(s) | Notes |
| --- | --- | --- |
| **Hardware-Verified** | **Insta360 GO 3S** | Verified end-to-end on physical hardware (phone + **Pixel Watch 4**) for wake-up auth, all 10 capture modes, 10 Hz `UploadGps` embedding, clock sync, Wi-Fi file listing/download, Live View, and phone-watch handover. |
| **Experimental / Untested** *(19 catalog models)* | **GO Series:** GO Ultra, GO 3, GO 2<br>**X Series:** X6, X5, X4 Air, X4, X3, ONE X2, ONE X<br>**ONE R / RS:** ONE RS, ONE R<br>**Ace Series:** Ace Pro 3, Ace Pro 2, Ace Pro, Ace<br>**Specialty:** Sphere, Luna Ultra, Luna Pro | Model identifiers, BLE/Wi-Fi prefixes, iBeacon prefixes, and transport flags are derived from device configuration metadata in [`Insta360CameraModel.kt`](core/src/main/java/com/open360/core/model/Insta360CameraModel.kt). Not verified on physical cameras. |

### Transports & Companion Hardware

| Transport / Device | Status | Details |
| --- | --- | --- |
| **BLE Direct** (`0xBE80`) | **Hardware-Verified** | Verified on Insta360 GO 3S + Android phone & Pixel Watch 4. |
| **Wi-Fi Camera AP** (`192.168.42.1`) | **Hardware-Verified** | Verified on Insta360 GO 3S for file browsing, downloads, and Live View (`WIFI_INFO` option 36 credential retrieval over BLE). |
| **Wear OS Watch** (Pixel Watch 4) | **Hardware-Verified** | Verified standalone BLE + GPS + watch heart rate FIT export + Data Layer handover (`play` flavor). |
| **USB-C Direct** | **Experimental / Untested** | Direct USB OTG bulk endpoint protocol (`0x81`/`0x01`); unverified across physical camera models. |
| **Wi-Fi Hotspot / LAN** (`192.168.49.1` / custom IP) | **Experimental / Untested** | Station-mode Wi-Fi control & media access; unverified across physical cameras. |
| **GPS Remote Server** (`0xCE80` / `0xD0FF`) | **Experimental / Untested** | Emulated Insta360 GPS Preview Remote GATT server; dormant on GO 3S (camera did not initiate central connection to phone's rotating BLE address). |
| **Bluetooth Classic SPP** | **Experimental / Untested** | Legacy RFCOMM serial tunnel intended for Insta360 ONE X; untested on hardware. |

---

## Distribution Channels & Product Flavors

Open360 builds two phone flavors (`com.mkroshana.open360`) and a standalone Wear OS app (`:wear`):

| Channel | Variant / Modules | Proprietary Dependencies | Notes |
| --- | --- | --- | --- |
| **Google Play** | `:app` (`play` flavor) + `:wear` | `play-services-wearable` | Full phone + Wear OS experience with Wear OS Data Layer settings, remote handover, and watch track sync. |
| **GitHub Releases** | Signed APKs (`play`, `foss`, and `wear`) | `play` / `wear` include Wearable Data Layer; `foss` has none | Direct sideloadable signed APKs with `SHA256SUMS.txt`. |
| **F-Droid** | `:app` (`foss` flavor) | **Zero** proprietary Google Play Services / GMS blobs | Pure open-source phone build (`foss` omits `play-services-wearable` and Wear Data Layer services); reproducible APK metadata disabled (`dependenciesInfo.includeInApk = false`). |

---

## Building from Source

### Prerequisites

- **JDK 17**
- **Android SDK** (`compileSdk = 37`, phone `targetSdk = 36` / `minSdk = 26`, Wear OS `targetSdk = 35` / `minSdk = 30`)
- Gradle wrapper (`./gradlew` — Gradle 9.6.0 / AGP 9.5.3)

### Common Gradle Commands

```bash
# Phone APKs & App Bundle
./gradlew :app:assemblePlayRelease
./gradlew :app:bundlePlayRelease
./gradlew :app:assembleFossRelease

# Wear OS APK & App Bundle
./gradlew :wear:assembleRelease
./gradlew :wear:bundleRelease

# Unit tests & lint
./gradlew :core:testPlayDebugUnitTest :core:testFossDebugUnitTest \
          :app:testPlayDebugUnitTest :app:testFossDebugUnitTest :wear:testDebugUnitTest \
          :app:lintPlayDebug :app:lintFossDebug :wear:lintDebug
```

### Release Signing Configuration

For Wear OS Data Layer communication (`play` + `:wear`), both apps must share the same `applicationId` (`com.mkroshana.open360`) and signing certificate. Configure signing in **either** of two ways:

1. **Local `keystore.properties` (git-ignored)** in the repository root:
   ```properties
   storeFile=/absolute/path/to/open360-release.jks
   storePassword=your-store-password
   keyAlias=open360
   keyPassword=your-key-password
   ```
2. **Environment variables** (used by CI):
   - `OPEN360_KEYSTORE_PATH`
   - `OPEN360_KEYSTORE_PASSWORD`
   - `OPEN360_KEYSTORE_ALIAS`
   - `OPEN360_KEY_PASSWORD`

For local Wear OS development against a debug-signed phone build, pass `-Popen360LocalWearDebugSign=true` when building `:wear`.

---

## Permissions Overview

Open360 requests only permissions required for local device-to-device camera control, GPS/sensor telemetry, and user-chosen file storage:

- **Bluetooth (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`)**: BLE camera discovery, control, GPS upload, optional GATT remote server, and external sports sensors (`neverForLocation` on scan).
- **Location (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE_LOCATION`)**: Satellite GPS fixes injected into video metadata and exported locally to `.gpx`/`.fit` files (including background tracking when enabled).
- **Nearby Wi-Fi (`NEARBY_WIFI_DEVICES`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_NETWORK_STATE`)**: Direct local camera hotspot connection for Live View and footage downloads (`neverForLocation`).
- **Body Sensors (`BODY_SENSORS`, `FOREGROUND_SERVICE_HEALTH` — Wear OS)**: Optional on-watch heart rate logging into local `.fit` activity tracks while the camera is active.
- **Storage & USB (`OpenDocumentTree` SAF, `MediaStore.Downloads`, USB Host)**: Local storage of GPX/FIT tracks and clips in `Download/Open360`, plus user-selected USB-C/SD card folder import.

Open360 includes **zero analytics, zero crash reporting SDKs, zero ads, and zero cloud servers**.

---

## Documentation & Legal

- [Privacy Policy (`PRIVACY.md`)](PRIVACY.md)
- [Store & F-Droid Publishing Guide (`docs/PUBLISHING.md`)](docs/PUBLISHING.md)
- [Apache License 2.0 (`LICENSE`)](LICENSE)
