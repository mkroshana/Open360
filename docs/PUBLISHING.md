# Open360 Publishing & Release Guide

Operator checklist for signing, distributing, and maintaining Open360 across **Google Play** (Phone + Wear OS), **GitHub Releases**, and **F-Droid**.

---

## 1. Shared Upload Keystore & Signing Setup

Because Open360's phone app (`play` flavor) and Wear OS app (`:wear`) communicate over the **Wear OS Data Layer**, Google Play Services enforces that both APKs/AABs share the **exact same `applicationId` (`com.mkroshana.open360`) and signing certificate**. Use a single upload keystore for both `:app` and `:wear`.

### Generate the Upload Keystore

```bash
keytool -genkeypair \
  -v \
  -keystore open360-upload.jks \
  -alias open360 \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

Keep `open360-upload.jks` backed up securely outside the repository (`*.jks`, `*.keystore`, and `keystore.properties` are git-ignored).

### Option A: Local `keystore.properties`

Create `keystore.properties` at the repository root:

```properties
storeFile=/absolute/path/to/open360-upload.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=open360
keyPassword=YOUR_KEY_PASSWORD
```

### Option B: CI Environment Variables

Release builds also read signing credentials from environment variables when `keystore.properties` is absent:

- `OPEN360_KEYSTORE_PATH` — absolute path to the `.jks`/`.keystore` file
- `OPEN360_KEYSTORE_PASSWORD` — keystore password
- `OPEN360_KEYSTORE_ALIAS` — key alias (e.g., `open360`)
- `OPEN360_KEY_PASSWORD` — key password

### Local Wear OS Debug-Sign Override

During local development on a watch alongside a debug-signed phone install, pass:

```bash
./gradlew :wear:installRelease -Popen360LocalWearDebugSign=true
```

This keeps release builds safely requiring release keys by default while allowing local debug-key matching for Data Layer testing on hardware.

---

## 2. Google Play Console Checklist

### 2.1 App Creation & Play App Signing

1. Create app `Open360` (`com.mkroshana.open360`) in Google Play Console.
2. Enroll in **Play App Signing** using the certificate from `open360-upload.jks` as the upload key (or let Play manage the app signing key while uploading with `open360-upload.jks`).
3. Enable the **Wear OS** form factor under **Setup → Advanced settings → Form factors → Add form factor → Wear OS**.

### 2.2 Build & Upload Both App Bundles

Always build and upload **both** `play` phone and Wear OS bundles (with distinct, non-overlapping version codes):

```bash
./gradlew :app:bundlePlayRelease :wear:bundleRelease
```

| Module / Flavor | Artifact | `versionCode` Range | Play Console Track |
| --- | --- | --- | --- |
| `:app` (`play`) | `app/build/outputs/bundle/playRelease/app-play-release.aab` | `10001+` | Standard Android (Phone/Tablet) release |
| `:wear` | `wear/build/outputs/bundle/release/wear-release.aab` | `20001+` | Wear OS form factor release |

### 2.3 Store Listing & Privacy Policy URL

- **Title, descriptions, changelog, icon, feature graphic:** synced under `fastlane/metadata/android/en-US/`.
- **Privacy Policy URL:**  
  `https://github.com/mkroshana/Open360/blob/main/PRIVACY.md`
- **Important disclaimer:** Ensure the Play Store listing clearly states that Open360 is an **unofficial** third-party open-source companion app not affiliated with Insta360 / Arashi Vision Inc.

### 2.4 Data Safety Form Answers

| Play Data Safety Question | Answer | Notes |
| --- | --- | --- |
| Does your app collect or share any of the required user data types? | **No** | Open360 runs zero analytics, zero crash SDKs, zero ads, and zero cloud servers. All GPS fixes, heart rate / sports-sensor readings, wake-up IDs, and clips stay on-device (or transfer locally between the user's own paired phone & watch via Wear OS Data Layer). |
| Is all of the user data collected by your app encrypted in transit? | *(N/A when "No" data collected)* | — |
| Do you provide a way for users to request that their data is deleted? | *(N/A when "No" data collected; users control all local files)* | Users can delete `Download/Open360` files, reset settings, or uninstall. |

### 2.5 Health Apps Declaration (Wear OS `BODY_SENSORS`)

Because `:wear` declares `android.permission.BODY_SENSORS` to log watch heart rate into local `.fit` activity tracks:

1. In Play Console → **App content → Health**, select **Activity and fitness** (activity/workout telemetry export).
2. Clarify that Open360 is **not** a medical device and does **not** diagnose, treat, cure, or prevent any disease.
3. State that heart rate data (`Sensor.TYPE_HEART_RATE`) is read only when actively enabled by the user while a camera session is active, and is saved solely to local `.fit` files on the user's own device (never uploaded or shared with third parties).

### 2.6 Foreground Service Declarations & Demo Video Script

Google Play requires a declaration and short demonstration video for every declared `foregroundServiceType`:

| Service & Module | `foregroundServiceType` | Justification |
| --- | --- | --- |
| `com.open360.core.gps.GpsTrackerService` (`:app`) | `location` | Keeps satellite GPS acquisition and BLE camera connection active with the screen off during action-camera recording so 10 Hz GPS metadata is continuously embedded into video clips and saved to `.gpx`/`.fit`. |
| `com.open360.core.gps.GpsTrackerService` (`:wear`) | `location\|health` | Maintains standalone watch BLE camera link, watch satellite GPS stream, and on-wrist heart rate sensor capture (`BODY_SENSORS`) into local `.fit` tracks while recording or in ambient mode. |

#### Short Demo Video Script (~35–45 seconds, unedited screen + camera capture)

1. **0:00–0:08 (Phone Permissions & Camera Link):** Open Open360 on phone, grant Bluetooth + Precise Location permissions, and connect to the Insta360 camera over BLE.
2. **0:08–0:18 (Phone `location` Foreground Service):** Enable Background GPS Tracking / start recording, pull down the Android notification shade showing the active Open360 GPS/camera foreground notification, lock the screen for ~5 seconds, wake the phone, stop recording, and show the saved `.gpx`/`.fit` file in Footage → Downloaded.
3. **0:18–0:35 (Wear OS `location` + `health` Foreground Service):** Open Open360 on the Wear OS watch, grant Location + Body Sensors permissions, connect to the camera, start a recording showing live timer + heart rate BPM, show the active ongoing/foreground status (and ambient screen dim), then stop recording and confirm the `.fit`/`.gpx` handover.

### 2.7 Wear OS Standalone Review Checklist

Before submitting `:wear` (`wear-release.aab`) to Play review, verify on physical/emulated round and square Wear OS targets:

- [ ] `<meta-data android:name="com.google.android.wearable.standalone" android:value="true" />` is present in `wear/src/main/AndroidManifest.xml`.
- [ ] **Round & square screens:** No clipped buttons, headers, or `EdgeButton` targets on circular (e.g., Pixel Watch 4) and rectangular Wear OS layouts.
- [ ] **Font scaling:** Test largest Wear OS accessibility font scale; verify labels wrap/ellipsize cleanly without overlapping icons or controls.
- [ ] **Ambient (Always-On) mode:** `AmbientRemote` renders pure black `#000000` background, high-contrast minimal text, and updates no more frequently than once per minute.
- [ ] **Swipe-to-dismiss:** Every sub-screen (`Modes`, `Settings`, `Add Sensor`, `Sensor`) inside `SwipeDismissableNavHost` exits cleanly on right-swipe without fighting horizontal pagers.
- [ ] **Standalone functionality:** Watch app launches, connects to the camera via BLE, acquires GPS fixes, logs watch heart rate, and records without a paired phone in Bluetooth range.

### 2.8 Closed Testing Requirement (Personal Developer Accounts)

If publishing under a **personal Google Play Developer account** created after November 2023, Google Play requires a **closed test with at least 12 opted-in testers continuously for 14 days** before you can apply for production access. Run both phone (`app-play-release.aab`) and Wear OS (`wear-release.aab`) tracks during that closed test window.

---

## 3. GitHub Releases Setup

Release builds are automated in [`.github/workflows/release.yml`](../.github/workflows/release.yml) on tags matching `v*` (e.g., `v0.2.0-beta.1`) or manual `workflow_dispatch`. Tags containing `-` (such as `-beta.1` or `-rc.1`) are automatically marked as GitHub Pre-releases.

### Required Repository Secrets

Add these secrets under **GitHub Repository → Settings → Secrets and variables → Actions**:

| Secret Name | Value |
| --- | --- |
| `OPEN360_KEYSTORE_BASE64` | Base64-encoded contents of `open360-upload.jks` (single line, no line breaks: `base64 -w0 open360-upload.jks`) |
| `OPEN360_KEYSTORE_PASSWORD` | Keystore password |
| `OPEN360_KEYSTORE_ALIAS` | Key alias (e.g., `open360`) |
| `OPEN360_KEY_PASSWORD` | Key password *(optional if identical to `OPEN360_KEYSTORE_PASSWORD`; `release.yml` falls back to `OPEN360_KEYSTORE_PASSWORD` when omitted)* |

### Triggering a Release

```bash
git tag v0.2.0-beta.1
git push origin v0.2.0-beta.1
```

The workflow builds:
- `app-play-release.aab`
- `app-play-release.apk`
- `app-foss-release.apk`
- `wear-release.aab`
- `wear-release.apk`
- `SHA256SUMS.txt`

and attaches them to the GitHub Release via `softprops/action-gh-release@v2`.

---

## 4. F-Droid Submission Guide (`foss` Flavor)

F-Droid builds the `:app` **`foss`** flavor directly from source, which completely excludes `com.google.android.gms:play-services-wearable` and disables APK signing-block dependency metadata (`dependenciesInfo { includeInApk = false; includeInBundle = true }`) for reproducible APK builds while keeping Play App Bundle metadata intact.

### Pre-Submission Verification

1. Confirm `:app:assembleFossRelease` succeeds cleanly in CI (`.github/workflows/ci.yml`).
2. Verify the resulting `app-foss-release.apk` contains **no** `com.google.android.gms` classes or Play dependency metadata block (`fdroid scanner` / `apkanalyzer`).
3. Ensure Fastlane metadata under `fastlane/metadata/android/en-US/` (including `images/icon.png`, `images/featureGraphic.png`, and `changelogs/10001.txt`) is committed on the release tag.

### Ready-to-Use `fdroiddata` Recipe (`metadata/com.mkroshana.open360.yml`)

```yaml
Categories:
  - Multimedia
  - Navigation
License: Apache-2.0
AuthorName: Open360 Contributors
SourceCode: https://github.com/mkroshana/Open360
IssueTracker: https://github.com/mkroshana/Open360/issues
Changelog: https://github.com/mkroshana/Open360/releases

AutoName: Open360
Summary: Unofficial GPS remote, live view & companion for Insta360 cameras
Description: |-
  Open360 is an independent, open-source companion app for controlling compatible
  Insta360 cameras and injecting real-time GPS metadata directly into recorded
  clips. It is not affiliated with, endorsed by, or sponsored by Insta360 /
  Arashi Vision Inc.

  Features:
  * BLE direct camera remote: wake-up, capture mode switching, shutter control,
    and live battery/storage/pod telemetry (hardware-verified on Insta360 GO 3S).
  * 10 Hz real-time GPS metadata injection synced to the camera clock, plus
    local GPX and FIT activity export to Download/Open360.
  * External BLE sports sensor support (Heart Rate, Cycling Cadence, Cycling Power).
  * Wi-Fi Live View viewfinder, footage download, and USB-C/SAF folder import.
  * Experimental catalog support for 19 additional Insta360 camera models and
    USB-C / Hotspot LAN / GPS Remote Server / Classic SPP transports.
  * Zero analytics, zero crash reporting SDKs, zero ads, and zero cloud servers.

  Note: The F-Droid (foss) build excludes proprietary Google Play Services and
  Wear OS Data Layer synchronization; use the Google Play or GitHub release
  builds if you need Wear OS phone-watch pairing.

RepoType: git
Repo: https://github.com/mkroshana/Open360.git

Builds:
  - versionName: 0.2.0-beta.1
    versionCode: 10001
    commit: v0.2.0-beta.1
    subdir: app
    gradle:
      - foss
    srclibs: []

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 0.2.0-beta.1
CurrentVersionCode: 10001
```

Submit this recipe via merge request to `fdroid/fdroiddata` after tagging (note: F-Droid main-repo reviewers generally prefer a non-prerelease tag such as `v0.2.0` or `v1.0.0` for initial inclusion) and verifying locally with `fdroid build -v -l com.mkroshana.open360:10001`.
