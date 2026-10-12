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
- **How the camera and Studio treat the stream.** The camera keeps every GPS record it receives from the start command to the stop command, in arrival order (a clip held exactly the 435 records sent in that window). The first video frame comes about 1.8 s after the start command (GPS kept minus video length was 1.75 to 1.81 s on five clips). Insta360 Studio shows the first kept record on the first frame and the rest 100 ms apart by counting: its Date & Time overlay equals `first_gps_timestamp` plus the video time on every clip checked. Arrival times after the first do not matter, but the pace does: the camera drops records that arrive in a burst. After 20 records in 2 s it took 20 more in half a second and then lost the next 33, which shifted the rest of that clip's GPS by 3.3 s.
- **What the app sends.** Nothing while idle. For a recording, record k is the position at `first frame + k * 100 ms`, interpolated between the real fix before and after that moment (`GpsTimeline`, `GpsStreamSchedule`), with that moment as its timestamp. Records become ready each time a new fix arrives and are sent at no more than 12 a second, at most four together (`GpsSendPacer`), so the backlog after a GPS outage does not arrive as a burst. The pacing has not yet been tested on the camera after an outage. A moment with no fix within 2.5 s on both sides is sent void, after waiting at most 4 s. Nothing is predicted, so the moments after the last fix that arrived before the stop, about 1 to 2 s, have no records.
- **Finding the first frame.** Counted from the camera's start notice (notification `0x2026`, field 1 = 2), and it depends on who started the recording. Started from the app, the notice comes about 0.2 s after the start command and the first frame 1.64 s after the notice (1.61 and 1.67 s on two clips). Started with the camera's button, the notice comes well into the start-up and the first frame is taken as 0.55 s after it (0.2 to 0.4 s on four clips, raised by 0.2 s after the ride check below); using the app figure there put the GPS about 1.3 s ahead of the picture. Both figures are the stop minus the video length, so they take the video to end at the stop. That has not been checked against a filmed clock. The recording ends at field 1 = 5 or 6, or capture state 0. A recording already running when the app connects is not seen.
- **Checked against the camera's motion sensors (ride, 2026-10-12).** A clip holds the camera's own motion, timed against the frames by `first_frame_timestamp`: in Video mode record `0x1900`, one orientation per frame (40 bytes: `u64` microseconds, then a quaternion as 4 doubles); in PRO Video mode record `0x300`, raw accelerometer and gyroscope at 1 kHz (20 bytes: `u64` microseconds, then six `u16` with 32768 as zero, accelerometer first). Lining up its turns and speed changes with the GPS as Studio places it showed the GPS 0.55 s behind the picture on a clip started from the app and about 0.8 s behind on one started with the button. Each video moment is now given the GPS from 0.55 s later (`GPS_BEHIND_PICTURE_MS`) and the button figure was raised by 0.2 s. This rests on two clips and is good to about 0.2 s. Measured again after the change on one clip started with the button (PRO Video, 172 s, 1707 records with none lost): the GPS was 0.04 s behind the picture on turns. A clip started from the app has not been measured since the change. Whether the phone's GPS reports late or the video starts later than worked out is not known. The phone's reported speed agrees with the speed worked out from its own positions to within 0.1 s. Neither clip lost a record: 2097 records for 210.2 s and 1066 for 106.9 s.
- **Capture mode.** Recordings started by the app's start command were all saved as Video (`VID_`, capture state 1), and those started with the camera's button as PRO Video (`PRO_VID_`, capture state 17), with the camera left in PRO Video mode throughout. The app's command does not use the mode set on the camera.
- **Camera status shown in the app.** Decoded in `Go2BleProtocol`, held in `CameraDetails`, worded in `CameraStatusText`. Tested against messages captured from the GO 3S: recording time left (notification `0x2021`, field 1 in seconds; the code is not in the published definitions and the meaning is inferred from how the value behaves), storage state (`0x2006`), the Action Pod's battery and whether it is connected (`0x201E`, `ChargeboxConnectedStatus`), the recording mode (capture state in `0x2010` and field 4 of the `0x2026` start notice), and the saved clip's name and length (reply to the stop command, so only for clips stopped from the app). Written from the published definitions only, never seen from the camera: battery low (`0x2004`), shutdown with its reason (`0x2005`), storage full (`0x2007`) and temperature (`0x2016`).
- **Recording already running on connect.** On connecting the app sends a capture status request (command `0x0F`). The camera answers it over Bluetooth with its capture state and the time recorded so far in whole seconds. If it is recording, the first frame is taken as that many seconds plus half a second before the reply, and the stream starts from there: the missed part is sent first, as records without a fix (or with real positions where the phone has fixes remembered from then, up to 10 minutes back), then the live GPS, and a GPX is saved from the same point. Samples are made only as fast as they can be sent (`MAX_GPS_BACKLOG`), so none is dropped, and at 12 records a second the stream catches up in about five times the delay: a clip joined 12 s late is caught up after a minute, and a clip that ends before then is missing GPS at its end. A clip joined late is placed to within about half a second, not the tenth of a second of one whose start was seen. Before this was added, a ride clip started 12 s before the app reconnected got no GPS at all. The filling-in has not yet been tried on the camera.
- **Command `0x19` must not be sent.** Earlier versions sent it on every connect, and from a Refresh button, as a "battery query". The published definitions name it `CALIBRATE_GYRO`. Its reply was `100, 1` all 16 times in one day, including five times when the camera's own battery updates said 96% or 98%, and the camera answered it with status 500 while recording. The battery level comes from the camera's own updates (`0x2003`), which arrive when it wakes and when a recording starts or stops, and from the status request below.
- **Status request on connect.** The app sends `GET_OPTIONS` (command `0x08`) with a packed list of option types 9, 11, 20 and 85, and the GO 3S answers over Bluetooth with an `Options` message: recording time left in seconds (field 9), its `BatteryStatus` (11), `StorageState` (20, with free and total bytes; 66.7 GB free of 128 GB when captured) and `ChargeboxConnectedStatus` (85). Option 9 read 5652 where notification `0x2021` had last read 5668 two short clips earlier, which confirms what `0x2021` carries. Free and total space are received but not shown.
- **A camera that is switched off still connects.** A powered-off GO 3S goes on advertising `0xBE80` and accepts a connection, with or without the wake beacon, but sends no sync packet and acts on nothing. Writes are held and answered when it is next switched on: a photo command got status 500 (`msg execute err.`) 56 s after it was sent. The app treats a connected camera as off until it hears from it, and again after 7 s with no answer to the keep-alive (`isCameraAwake`); while it is off the shutter is disabled and no capture command is sent.
- **The wake beacon does not wake the GO 3S.** Broadcast non-connectable and then connectable (three times, 10 s each), it left the camera off both times. Insta360's manual says its own app can wake a powered-off GO series camera.
- **How the Insta360 app appears to wake a GO camera.** Its code is hidden (the dex files are stored without their headers, index tables and method bodies), but the strings are readable. They show a class `Go2BleWakeupCmd` that gets the GATT services and logs `send notify to wake up go2 success`, a wake-up authorization UUID sent during the handshake (`sendWakeUpAuthorization`, `wake up authorization failed`), and exactly two fixed packets with valid checksums: the keep-alive, and `ff0640080008000000b00000016d90` (envelope type `06`, inner type `0xB0`, value 1). Wake Up now sends that packet on `0xBE81` (`buildWakeUpPacket`, `wakeCamera`), then on `0xB001`, then subscribes to `0xB002` to `0xB004`, stopping when the camera answers. Tried on a camera that was off, on a fresh link with the packet sent first: the camera's Bluetooth chip sent the same 15 bytes back 0.2 s later, the first thing a switched-off camera has ever answered, but the camera did not switch on in the following minute. That echo is not counted as the camera being on (`isWakeUpAcknowledgement`). Wake Up now follows the packet with a sync packet from the phone, the packet as a request (type `07`), and the second-service steps; those are untried. The newer part of the Insta360 app has a `WakeUpFrame(payload=…)` and logs `sending wake authorization uuid len=` before its own sync, so the camera probably wants an authorization this app does not have. Asking the camera for options 38 (authorization id) and 97 (wake-up switch) returned an empty `Options`.
- **GATT services on the GO 3S.** `0x1800`, `0x1801`, `0xBE80` (`0xBE81` write, `0xBE82` notify), `0xB000` (`0xB001` write, `0xB002` to `0xB004` notify; the Insta360 app has the strings `b000` to `b004`, purpose unknown), `0x180A` (software revision), and `9e5d1e47-5c13-43a0-8635-82ad38a1386f`, the Bluetooth chip maker's firmware update service.
- **Fix source.** Satellite fixes only (`GPS_PROVIDER`), one a second. Altitude is converted to height above sea level on Android 14 and later.
- **Session.** The camera sends a sync packet right after notifications are enabled on `0xBE82`; if it sends nothing it is asleep. A keep-alive (`07 00 00 00 05 00 00` in the BLE envelope) every 2 s keeps the link up; without it the camera dropped the link at about 90 s.
- **Capture status.** Notification `0x2010` carries `CameraCaptureState` in field 1: `1` when a video starts, `0` when it stops. It arrives about 1 s after the clip really starts.
- **Architecture A.** The phone-side server was verified end to end with a PC as the central, but the GO 3S never connected to it. Android advertises from a private address that changes; the real remote and working clones use a fixed one. That is the suspected cause, not a proven one.
- **Local GPX.** Each recording gets its own GPX file in `Download/Open360` on the phone, holding the real fixes taken between its first frame and its stop. The camera's reply to the stop command carries the video path (field 1, then field 1), so a recording stopped from the app is saved as the video's name with `.gpx`, for example `VID_20261012_163049_00_066.gpx`, with the video name and the start and end times inside. A recording stopped on the camera has no reply to read, so its file keeps the name `Open360_<date>_<time>.gpx`.

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
3. Wait for a GPS fix, then record from the app or with the camera's own button. The app sends GPS only while a recording is running and saves a GPX for it on the phone.
4. Once the app shows the camera as connected, the display can be turned off. A foreground service and a wake lock run for as long as the camera stays connected (the Background GPS Tracking switch, on by default), so fixes keep arriving, the keep-alive keeps going and recordings started on the camera are still seen. Android only lets the service start while the app is on screen, so connect before locking the phone. If the link drops the app does not reconnect on its own. Checked on 2026-10-12: with the display off for 57, 14 and 105 s a fix arrived in every second and the keep-alives stayed 2 s apart, and two clips started on the camera with the phone locked got their GPS.

The Bluetooth Remote menu on the camera is not used.
