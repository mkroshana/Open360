# Insta360 X4 — Bluetooth LE Protocol (Reverse-Engineered with GPS)

A community reverse-engineering writeup of how the Insta360 X4 talks to its
GPS remote over Bluetooth Low Energy: how a sleeping camera is woken with a
manufacturer advertisement that masquerades as an Apple iBeacon, how the
control link is structured, and how the remote streams 10 Hz GPS into the
camera as NMEA.

Everything here was confirmed on-air against a physical X4 and its GPS
remote using a passive nRF52840 sniffer. It very likely applies with minor
differences to the X3, X5, and ONE RS families, which share the same remote
ecosystem (see **Prior art**).

> **Disclaimer.** This documents an interoperability protocol observed on
> hardware the author owns, for the purpose of building compatible devices.
> "Insta360", "ORBIT", and product names are trademarks of their respective
> owners; this project is not affiliated with or endorsed by Insta360 or
> Apple. No camera firmware was modified, decompiled, or circumvented — this
> is purely observation of over-the-air BLE traffic. Provided as-is, without
> warranty. Bluetooth addresses, camera serials, and coordinates in the
> examples are illustrative.

---

## Method

- Passive BLE sniffing with an nRF52840 running Nordic's nRF Sniffer
  firmware, into Wireshark.
- Capture was filtered to the single camera↔remote connection (by access
  address) and exported to pcapng, then the ATT layer was extracted and
  decoded.
- The camera↔remote link is **not encrypted at the ATT layer**, so payloads
  are readable directly from a passive capture. (A phone app connecting to
  the camera or remote *does* negotiate encryption; that is a separate link.)

---

## Architecture

The remote is a **BLE peripheral**; the camera is the **central**. The flow:

1. The remote broadcasts a wake beacon (below) that encodes the target
   camera's serial.
2. A sleeping camera scans for that beacon and, on seeing its own serial,
   powers on.
3. The camera (central) connects back to the remote (peripheral).
4. The camera enables notifications on the remote's `ce82` characteristic.
5. The remote streams **GPS and button events** to the camera as
   notifications; the camera streams **status/settings** back as writes.

Two message directions are distinguished by a 3-byte magic prefix:

| Magic | Direction |
|---|---|
| `FC EF FE` | remote → camera (GPS, buttons) |
| `FE EF FE` | camera → remote (status, settings, handshake) |

Note the camera exposes a **separate** GATT service (`be80`, with `be81`
write / `be82` notify) that the official phone app uses for a different,
protobuf-based command protocol. This document covers the **remote** side
(`ce80`), which is simpler and carries the GPS.

---

## 1. The wake beacon (the "fake Apple iBeacon")

A sleeping X4 does not advertise. It scans for a manufacturer-specific
advertisement and powers on when it sees one carrying its own serial number.

The advertisement borrows **Apple's Bluetooth company identifier (`0x004C`)
and the iBeacon prefix (`0x02 0x15`)**, so every generic BLE scanner decodes
it as an Apple iBeacon. It is not one — the 16 bytes where a real iBeacon's
Proximity UUID would sit are actually Insta360's internal codename `ORBIT`
plus fixed bytes, followed by the target camera's serial as ASCII. The
iBeacon envelope is used purely as a convenient manufacturer-data container.

**Full 31-byte advertisement (connectable `ADV_IND`):**
```
02 01 05  1B FF  4C 00 02 15  09 4F 52 42 49 54 09 FF 0F 00  <SERIAL:6>  00 00 00 00 E4 01
```

| Bytes | Field | Notes |
|---|---|---|
| `02 01 05` | Flags AD | LE Limited Discoverable + BR/EDR Not Supported |
| `1B FF` | Manufacturer Specific Data | length 27, type `0xFF` |
| `4C 00` | Company ID | Apple, Inc. (`0x004C`), little-endian |
| `02 15` | iBeacon prefix | type `0x02`, length `0x15` |
| `09 4F 52 42 49 54 09 FF 0F 00` | `ORBIT` constant | ASCII `ORBIT` (`4F 52 42 49 54`) + fixed framing bytes |
| `<SERIAL:6>` | Camera serial | **6 ASCII bytes** — the variable, per-camera part |
| `00 00 00 00 E4 01` | iBeacon major/minor/TxPower | fixed |

The scan response (optional, cosmetic) carries the appearance (`0x0180`,
remote control) and the complete local name `Insta360 GPS Remote`.

**Serial encoding.** The camera's serial suffix is placed verbatim as ASCII
at offset 14–19 of the manufacturer data. Example — a camera named
`X4 34UQG5` uses serial `34UQG5`:

```
'3'  '4'  'U'  'Q'  'G'  '5'
33   34   55   51   47   35
```

so its wake advertisement is:
```
02 01 05 1B FF 4C 00 02 15 09 4F 52 42 49 54 09 FF 0F 00 33 34 55 51 47 35 00 00 00 00 E4 01
```

The serial is the **only** per-camera variable; every other byte is constant
across cameras. This is also why the beacon only wakes *your* camera — it is
scanning for its own serial. Waking an arbitrary camera requires knowing its
serial (shown on the camera's info screen).

Practical notes for reproducing it:
- Advertise **connectably** with **legacy** advertising (not BLE-5 extended);
  the sleeping camera's scanner expects a legacy `ADV_IND`.
- The company ID must be little-endian `4C 00`. If your stack takes the
  company ID as a separate field, don't also embed it in the payload.
- Flags(3) + Manufacturer(28) fill the entire 31-byte legacy payload, so the
  name must go in the scan response, not the main advertisement.
- Interval ~100 ms; the camera scans on a duty cycle and a slow interval will
  miss it.

---

## 2. GATT layout & handles

The remote (peripheral) exposes a primary service **`ce80`**
(`0000ce80-0000-1000-8000-00805f9b34fb`):

| Characteristic | UUID | Properties | ATT handle* | Role |
|---|---|---|---|---|
| Command in | `ce81` | Write, Write No Response | `0x0010` | camera → remote status/settings/handshake |
| Events | `ce82` | Notify (CCCD `0x2902`) | `0x0013` | **remote → camera: GPS + buttons** |
| Info | `ce83` | Read | — | static remote info (not exercised) |

\* Handles observed on this unit; discover them rather than hard-coding across
firmware revisions. The `ce82` CCCD was at `0x0014`.

---

## 3. Frame structure

All application frames share a common shape:

```
<MAGIC:3>  <TYPE:1>  <B4:1>  <LEN:1>  <PAYLOAD:LEN>
```

- `MAGIC` — `FC EF FE` (remote→camera) or `FE EF FE` (camera→remote).
- `TYPE` — message type (see below).
- `B4` — type-specific: a **sequence counter** for button events, otherwise a
  flag/reserved byte (`0x00` for GPS).
- `LEN` — length of `PAYLOAD` in bytes.

Message types:

| Magic | Type | Meaning |
|---|---|---|
| `FC EF FE` | `0x83` | GPS / sensor data (§5) |
| `FC EF FE` | `0x86` | button event (§4) |
| `FE EF FE` | `0x07` | camera serial handshake |
| `FE EF FE` | `0x10` | remote display string — mode / battery / **recording timer** (§6.1) |
| `FE EF FE` | `0x02` | status (battery/state) |
| `FE EF FE` | `0x05` | status / ack |
| `FE EF FE` | `0x0e`, `0x0f` | rare (slow keep-alive / event) |

---

## 4. Button events (remote → camera, `ce82`)

```
FC EF FE 86 <SN> 03 01 <BTN> <STATE>
```

- `SN` — 1-byte sequence, **incremented by 2 on every button event**, reset to
  0 on each new connection. (GPS frames leave this byte at 0.)
- `03` — payload length; `01` — constant.
- `BTN` — `0x02` shutter, `0x01` mode, `0x00` power.
- `STATE` — `0x00` momentary click, `0x03` held.

Behaviour:

| Action | BTN | STATE | Frames |
|---|---|---|---|
| Record start/stop (shutter) | `02` | `00` | one |
| Cycle mode | `01` | `00` | one |
| Screen on/off (power click) | `00` | `00` | one |
| **Power off (power hold)** | `00` | `03` | **repeated** ~every 20 ms for ~2 s, SN incrementing |

The key non-obvious detail: **power-off is a sustained held gesture, not a
discrete command.** A single power event only toggles the screen. The camera
powers off only after receiving a continuous stream of held-power frames
(each with a fresh SN) for ~1–2 seconds — reusing one SN risks the camera
de-duplicating the repeats.

---

## 5. GPS telemetry (remote → camera, `ce82`) — 10 Hz NMEA

The remote streams GPS to the camera at **~10 Hz**, wrapped around a
mostly-standard NMEA `RMC` sentence.

```
FC EF FE 83 00 <LEN>  ,26.7,<07>,  $G?RMC,...*CS
└── header (6) ─────┘ └ prefix (7) ┘ └── NMEA ──┘
```

- Header: magic, type `0x83`, `B4 = 0x00`, `LEN` = payload length (~82–84,
  varies with ASCII field widths).
- Prefix: a leading empty field, then `26.7` (constant in captures — likely
  temperature °C or a fixed field), then a single raw byte `0x07` (constant —
  likely satellite count / fix indicator).
- NMEA: an `RMC` sentence with the standard NMEA-0183 XOR checksum
  (XOR of all characters between `$` and `*`, two hex digits).

**NMEA quirks — replicate exactly, don't "fix" them:**
- **Longitude is signed and the hemisphere letter is always `E`.** West is
  encoded as a negative number with a literal `E`. (e.g. 74°W →
  `-7400.0000,E`.)
- **An extra field `V`** sits between the mode character and the checksum:
  `...,A,V*CS`.
- Coordinates are `ddmm.mmmm` (degrees + decimal minutes), not decimal
  degrees.

**Field order:**
```
$G?RMC, UTC(hhmmss.sss), status(A/V), lat(ddmm.mmmm), N/S,
        lon(SIGNED dddmm.mmmm), E, speed(knots), course, date(ddmmyy),
        magvar, W, mode(A), V *CHECKSUM
```

**Worked example** (synthetic coordinates 40°00.0000′N, 74°00.0000′W):

```
$GNRMC,120000.000,A,4000.0000,N,-7400.0000,E,0.00,0.00,010126,0.0,W,A,V*6E
```
wrapped into a complete frame:
```
FC EF FE 83 00 52 2C 32 36 2E 37 2C 07 2C 24 47 4E 52 4D 43 2C 31 32 30 30 30 30 2E 30 30 30 2C 41 2C
34 30 30 30 2E 30 30 30 30 2C 4E 2C 2D 37 34 30 30 2E 30 30 30 30 2C 45 2C 30 2E 30 30 2C 30 2E 30 30 2C
30 31 30 31 32 36 2C 30 2E 30 2C 57 2C 41 2C 56 2A 36 45
```

**Rate.** ~10 Hz (100 ms). The embedded UTC field only ticks once per second,
but latitude/longitude/speed change every frame, so it is genuinely 10 Hz
fixes with a coarse timestamp field. The official app displays it at 1 Hz,
decimating 9 of every 10 frames.

Frame builder (pseudocode):
```
deg_min(v):                      # decimal degrees -> ddmm.mmmm, sign preserved
    d = trunc(|v|); m = (|v| - d) * 60
    return sign(v) * (d*100 + m)

body = "$GNRMC,%s,%s,%.4f,%s,%.4f,E,%.2f,%.2f,%s,0.0,W,A,V" %
       (utc, status, deg_min(lat), NS, deg_min(lon), speed_kn, course, ddmmyy)
cs = XOR(ord(c) for c in body without leading '$')
frame = b"\xFC\xEF\xFE\x83\x00" + byte(len(payload))
        + (",26.7,\x07," + body + "*" + hex2(cs)).encode()
notify(0x0013, frame)            # 10 Hz
```

---

## 6. Camera → remote status channel (`ce81`, `FE EF FE`)

The camera sends **Write Requests** to the remote's `ce81`; the ATT-layer
Write Response is the acknowledgement (no application-layer reply is
expected). Observed types:

| Type | Cadence | Content |
|---|---|---|
| `0x07` | at connect + periodic | camera serial as ASCII (handshake) |
| `0x10` | ~1 Hz | remote **display string** — mode / battery / recording timer (see §6.1) |
| `0x02` | ~1 Hz | status word (battery/state; not a reliable recording flag — §6.1) |
| `0x05` | occasional | status / ack |
| `0x0f` / `0x0e` | rare (~50 s) | slow keep-alive / event |

Example serial handshake for `34UQG5`:
```
FE EF FE 07 00 06 33 34 55 51 47 35
```

A device emulating the remote can accept and ACK these; parsing them is only
needed to drive a remote-side display or to mirror camera state.

### 6.1 Camera state (including recording)

The camera exposes its state **implicitly through the `0x10` display string**
— the text the physical remote shows on its screen — rather than as a
dedicated status flag. The `0x10` payload ends in an ASCII string that changes
with what the camera is doing:

| Camera state | `0x10` ASCII string | Notes |
|---|---|---|
| Idle — current mode | `4K\|30\|UW` | resolution / fps / lens; ~1 Hz |
| Idle — battery / runtime | `13h09m` | alternates with the mode string |
| **Recording** | `.HH:MM:SS` | live elapsed-time counter, increments at 1 Hz |

So **recording is detectable**: while recording, `0x10` carries an elapsed
`HH:MM:SS` timer that counts up once per second; when idle it carries the mode
or battery string. Across an ~18 s clip the string stepped
`.00:00:00 → .00:00:01 → … → .00:00:16`, then reverted to `4K|30|UW` at stop.

Sample `0x10` frames (header `FE EF FE 10`, the flag byte, a length, a few
control bytes, then the ASCII string):
```
FE EF FE 10 81 0C 01 1C 5E 00 34 4B 7C 33 30 7C 55 57     ->  "4K|30|UW"    (idle: mode)
FE EF FE 10 80 0B 01 12 46 01 20 31 33 68 30 39 6D         ->  " 13h09m"     (idle: battery)
FE EF FE 10 80 0D 01 0E 46 01 2E 30 30 3A 30 30 3A 30 35   ->  ".00:00:05"   (recording, t=5 s)
```

The `0x02` status word is **not** a reliable recording indicator — its few
transitions in the capture landed *after* record stop and track battery/state,
not the shutter. (A clean boolean record flag was not found; the `0x10` timer
is the dependable signal.)

> For emulation: a device that *sends* the shutter command already knows its
> own record state and needn't parse this. It matters only if you want to
> mirror camera state on your own device, or to confirm a recording actually
> started.

---

## 7. Emulating the remote (summary)

To present a device to the X4 as its GPS remote:

1. **Advertise** the wake beacon (§1) connectably, with a fixed static-random
   address so a bonded camera can reconnect.
2. **Expose** the `ce80` service with `ce81` (write), `ce82` (notify + CCCD),
   `ce83` (read).
3. **Accept the incoming connection** from the camera; support LE bonding
   (Just Works is the likely method — the remote has no display/keypad).
4. On CCCD subscribe, reset the button SN to 0.
5. **Stream GPS** on `ce82` at 10 Hz (§5), and send **button events** (§4) on
   demand. Accept + ACK the camera's `ce81` writes (§6).
6. **Liveness:** never let the GPS stream go silent while connected — if there
   is no fix, keep emitting `RMC` frames with status `V` (void) rather than
   stopping, so the camera doesn't treat the remote as gone.

---

## Appendix — reference values

- **Connection parameters** negotiated by the camera: 48.75 ms interval,
  latency 0, 7000 ms supervision timeout.
- **Roles:** camera = central/initiator (its BLE module reports, e.g., an
  AMPAK OUI); remote = peripheral/advertiser (static-random address).
- **Encryption:** none at the ATT layer on the camera↔remote link. A phone
  app connecting separately does negotiate AES.

---

## Prior art & credits

This builds directly on earlier community work, which established the `ORBIT`
manufacturer payload, the serial-at-offset-14 wake, and the `be80`/`ce80`
service families on the X3 / RS:

- `pchwalek/insta360_ble_esp32` — ESP32 wake + control
- `btittelbach/esphome_config_examples` — ORBIT payload + status parsing
- `tsunghowu/insta360_ble_rc_rpi_pico_w` — Pico W remote implementation
- Hackaday, "Insta360 X3 BLE remote control with ESP32" — command frame format

This document adds X4 confirmation, the **10 Hz GPS `RMC` framing** (type
`0x83`), the **`ce82` button/power-hold protocol** (type `0x86`), the
**camera→remote status channel**, and the concrete ATT handle map.

---

*Contributions and corrections welcome. Verify handles and pairing behaviour
against your own firmware revision.*
