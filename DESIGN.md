# Open360 Design Rules

Project UI, layout, motion, and verification rules learned from design reviews and device checks on Google Pixel hardware.

## Surfaces

- **Every `SegmentedListItem` passes `colors = settingsSegmentColors()` (defined in `app/src/main/java/com/open360/ui/settings/SettingsScreen.kt`), never raw `ListItemDefaults.segmentedColors()` defaults.**
  Why: Material 3 `1.5.0-beta02` defaults render unchecked segmented rows near-transparent against the dark canvas while checked rows turn bright `secondaryContainer`; explicit `surfaceContainer` on both states keeps grouped settings cards legible and consistent. Enforced by `DesignRulesCheckTest`.
  <!-- 2026-11-24: "Get screenshots from the app running on my phone" / "Do what you prefer" -->

## Spacing & layout

- **Primary app destinations live as top-level `MainNavTab` entries in `ShortNavigationBar` (`app/src/main/java/com/open360/ui/Open360App.kt`), never nested as cards inside `Remote` or hidden behind top-bar icon actions.**
  Why: keeps camera remote controls focused while giving live preview, footage, and settings equal single-tap access.
  <!-- 2026-11-24: "Live view should be on a separate tab" / "also move the settings to a tab" -->

- **Top bar is always a persistent `CameraStatusTopBar` (`app/src/main/java/com/open360/ui/camera/CameraStatusTopBar.kt`) across every `MainNavTab` — never showing `"Open360"` — with a locked 2-line identity/status header plus live camera telemetry chips (battery, Action Pod, storage space, time/photos left, and warnings).**
  Why: keeps connected device identity, battery life, storage, and hardware warnings immediately visible on all screens without repeating a large status card inside `Remote` or shifting the header baseline across tabs.
  <!-- 2026-11-24: "camera status bar should be shown on top, dont need the app name, show the connected device all the battery life and storage and etc" -->

## Typography

- **All live numeric readouts (timers, speed, battery, FPS, telemetry) merge `TabularFigures`, and long camera/route filenames (`VID_...mp4`, `.gpx`) use `middleEllipsize()` from `app/src/main/java/com/open360/ui/theme/UiPolish.kt`.**
  Why: prevents horizontal digit jitter during updates and preserves distinguishing trailing clip numbers and extensions.
  <!-- 2026-11-24: "Go through the app UI and make the UI and UX user friendly and use $ask-emil $break-ui  $emil-design-eng" -->

## Motion

- **UI transitions stay under `300ms` using `EaseOutCubic`, `EaseInOutCubic`, or `EaseDrawer` from `UiPolish.kt` — never `FastOutSlowInEasing` or `ease-in` curves on UI entrances/exits — and press feedback uses `rememberPressScale()`.**
  Why: built-in weak easings and `ease-in` curves feel sluggish; strong custom ease-out curves feel responsive and consistent across controls. Enforced by `DesignRulesCheckTest`.
  <!-- 2026-11-24: "Go through the app UI and make the UI and UX user friendly and use $ask-emil $break-ui  $emil-design-eng" / "do the next step" -->

- **Animate transforms, opacity, and recording pulses on the GPU (`Modifier.graphicsLayer`, `Modifier.drawBehind`, `rememberRecordingPulseAlpha()`) — never read pulse alpha in a root composable or pass animated alpha to `.background(...copy(alpha = ...))`, and never animate layout `size` for GPU-capable morphs.**
  Why: avoids whole-screen recomposition storms and layout/measure thrash during recording or shutter morphs. Enforced by `DesignRulesCheckTest`.
  <!-- 2026-11-24: "do the next step" / "Do what you prefer" -->

- **All motion checks `rememberReducedMotion()`: drop position, scale, bounce, and infinite pulse animations while keeping gentle `120–150ms` opacity/color fades.**
  Why: respects Android "Remove animations" without stripping comprehension of state changes.
  <!-- 2026-11-24: "Go through the app UI and make the UI and UX user friendly and use $ask-emil $break-ui  $emil-design-eng" / "do the next step" -->

## Interaction

- **Verify non-trivial layout, theming, or navigation changes with real-device screenshots across affected tabs before signing off.**
  Why: runtime M3 defaults, scroll clipping, and cross-tab alignment jumps show immediately on hardware even when unit tests and lint pass.
  <!-- 2026-11-24: "Get screenshots from the app running on my phone" -->
