# Open360 — Project Overview & Architecture Guide

## 1. Purpose & Scope

Open360 is an open-source Android application (`com.open360`, Jetpack Compose + Kotlin) designed to replace the official Insta360 GPS Action Remote and commercial watch remote apps (`com.kotlin.insta360gpswatchremote`, `com.aktuna.wear.insta360btctrl`) for Insta360 cameras (GO 3, GO 3S, X3, X4, Ace).

Its primary objective is embedding real-time GPS coordinates directly into recorded video metadata (`.insv`/`.mp4`) while providing camera wake-up, shutter, mode switching, and live recording/battery status tracking over BLE without requiring Wi-Fi live preview.

## 2. Core Protocol Architecture

Insta360 cameras have two completely separate BLE architectures. Open360 uses Architecture B on the GO 3S:

| Mode | Role | GATT Service | GPS Injection Behavior |
| --- | --- | --- | --- |
| Architecture A (GPS Remote) | Phone = GATT Server (Peripheral)<br>Camera = Central Client | `0xCE80`, `0xD0FF` | What the real remote does: the camera subscribes to `0xCE82` and stores the streamed NMEA. Not usable from a phone on the GO 3S: the camera never opened a link to the phone. The server code is kept but not started. |
| Architecture B (Direct Control) | Phone = Central Client<br>Camera = Peripheral Server | `0xBE80` (`0xBE81`/`0xBE82`) | Works. `UploadGps` (command `0x35`) over `0xBE81` is stored in the clip. This is how the official app does it. |

### Status on the GO 3S (Verified 2026-10-12, Firmware v9.0.59)

- **Architecture B stores GPS.** Three clips recorded while Open360 was connected each contain GPS records (trailer record `0x700`) matching what the app sent. Clips recorded without it contain none.
- **`UploadGps` format.** Protobuf with one field, `bytes gps = 1`, holding 53-byte little-endian records: unix seconds (8), milliseconds (2), status `A`/`V` (1), latitude (8), `N`/`S` (1), longitude (8), `E`/`W` (1), speed m/s (8), track degrees (8), altitude m (8). An earlier attempt that sent three raw doubles as the payload stored nothing.
- **How the camera and Studio treat the stream.** The camera keeps every GPS record it receives from the start command to the stop command, in arrival order (a clip held exactly the 435 records sent in that window). The first video frame comes about 1.8 s after the start command (GPS kept minus video length was 1.75 to 1.81 s on five clips). Insta360 Studio shows the first kept record on the first frame and the rest 100 ms apart by counting: its Date & Time overlay equals `first_gps_timestamp` plus the video time on every clip checked. Arrival times after the first do not matter.
- **What the app sends.** Nothing while idle. For a recording, record k is the position at `first frame + k * 100 ms`, interpolated between the real fix before and after that moment (`GpsTimeline`, `GpsStreamSchedule`), with that moment as its timestamp. Records go out in a batch each time a new fix arrives, up to four per write. A moment with no fix within 2.5 s on both sides is sent void, after waiting at most 4 s. Nothing is predicted, so the moments after the last fix that arrived before the stop, about 1 to 2 s, have no records.
- **Finding the first frame.** 1.85 s after the app sends the start command, or 1.70 s after the camera's start notice arrives (notification `0x2026`, field 1 = 2), which also covers recordings started on the camera. The recording ends at field 1 = 5 or 6, or capture state 0. A recording already running when the app connects is not seen.
- **Fix source.** Satellite fixes only (`GPS_PROVIDER`), one a second. Altitude is converted to height above sea level on Android 14 and later.
- **Session.** The camera sends a sync packet right after notifications are enabled on `0xBE82`; if it sends nothing it is asleep. A keep-alive (`07 00 00 00 05 00 00` in the BLE envelope) every 2 s keeps the link up; without it the camera dropped the link at about 90 s.
- **Capture status.** Notification `0x2010` carries `CameraCaptureState` in field 1: `1` when a video starts, `0` when it stops. It arrives about 1 s after the clip really starts.
- **Architecture A.** The phone-side server was verified end to end with a PC as the central, but the GO 3S never connected to it. Android advertises from a private address that changes; the real remote and working clones use a fixed one. That is the suspected cause, not a proven one.
- **Fallback.** The app also writes a GPX track to `Download/Open360` on the phone for import into Insta360 Studio.

## 3. Architecture A GATT Layout & Wire Protocol

### GATT Services & Handles

- Primary Service `0xCE80` (`0000ce80-0000-1000-8000-00805f9b34fb`):
  - `0xCE81` (Write / WriteNoResp): Camera writes status packets (`0xFE 0xEF 0xFE ...`).
    - Type `0x07`: Camera serial handshake (e.g. `8JY3HF`).
    - Type `0x10`: Camera display string containing live recording timer (e.g., `.00:00:05`) or idle state (e.g., `4K|30|UW,  13h09m`).
  - `0xCE82` (Notify + CCCD `0x2902`): Phone notifies camera with:
    - 10 Hz GPS stream (Type `0x83`).
    - Button events (Type `0x86`).
  - `0xCE83` (Read): Static remote identifier (`0x01`, `0x02`).
- Secondary Service `0xD0FF` (`0000d0ff-3c17-d293-8e48-14fe2e4da212`):
  - Characteristics `FFD1` through `FFE0` exposing device info and firmware version (`1.0.0`).

### 10 Hz GPS NMEA Sentence Encoding (0xCE82)

- Frame format: `0xFC 0xEF 0xFE 0x83 0x00 <len> ,26.7,\x07,$GNRMC,...*CS`
- Wire quirks:
  - Longitude is signed and the hemisphere indicator is always literal `'E'` (e.g. 74°W → `-7400.0000,E`).
  - Extra field `'V'` sits between mode character and checksum: `...,A,V*CS`.
- Liveness keep-alive: When GPS fix is not yet acquired, the app continuously streams `$GNRMC` sentences with status `'V'` (void). If streaming stops, the camera drops connection.

### Remote Button Commands (0xCE82)

- Frame format: `0xFC 0xEF 0xFE 0x86 <SN> 0x03 0x01 <BTN> <STATE>`
  - `SN`: Increments by 2 on every button event (resets to 0 upon camera connection).
  - `BTN`: `0x02` Shutter, `0x01` Mode cycle, `0x00` Power.
  - `STATE`: `0x00` Click, `0x03` Held.
- Power off quirk: Power off requires streaming held-power frames (`STATE = 0x03`) every 20 ms for ~1.5 seconds with advancing `SN`. A single click only toggles the screen.

### Camera Wake-Up Beacon (ORBIT iBeacon)

- Sleeping cameras do not advertise. They wake upon detecting an Apple manufacturer advertisement (`0x004C`, iBeacon envelope `0x02 0x15`).
- Payload (24 bytes): `0x02 0x15 0x09 'O' 'R' 'B' 'I' 'T' 0x09 0xFF 0x0F 0x00 <6-byte ASCII Camera Serial Suffix> 0x00 0x00 0x00 0x00 0xE4 0x01`.

## 4. Key Codebase Files & Responsibilities

- `InstaGpsRemoteGattServer.kt`: Hosts the BLE GATT server (`0xCE80` & `0xD0FF`), manages BLE advertising as "Insta360 GPS Remote", handles camera connections, runs the 10 Hz NMEA transmission loop, and dispatches remote button commands.
- `Go2BleProtocol.kt`: Pure Kotlin protocol encoders and decoders (`buildGpsNmeaFrame`, `buildRemoteButtonCommand`, `parseCameraStatusPacket`, CRC-16/MODBUS envelope framing).
- `GpsTrackerEngine.kt` & `GpsTrackerService.kt`: Android GPS location provider and Foreground Service ensuring continuous GPS tracking and BLE broadcasting when the phone screen is off.
- `CameraViewModel.kt`: Coordinates the GATT server, location fixes, recording duration timer, and UI state.
- `CameraScreen.kt`: Jetpack Compose UI showing live speed gauge, altitude, heading, satellite fix, camera status, and tactile shutter.
- `Go2BleProtocolTest.kt`: Unit test suite covering synthetic capture vectors, NMEA sentence formats, and status packet decoders.

## 5. Connection Workflow

1. Wake the camera.
2. Open Open360. It scans for the camera's `0xBE80` advertisement and connects on its own; tap Connect if it was opened before the camera woke.
3. Once the phone has a GPS fix, the app uploads it continuously while connected. Any clip recorded in that time, from the app or the camera's own button, gets the GPS.
4. Keep the app on screen. GPS stops when the app is in the background unless the foreground service is running, which today only starts once a recording is detected.

The Bluetooth Remote menu on the camera is not used.
