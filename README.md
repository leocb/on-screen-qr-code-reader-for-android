# Screen QR

[![CI](https://github.com/leocb/on-screen-qr-code-reader-for-android/actions/workflows/ci.yml/badge.svg)](https://github.com/leocb/on-screen-qr-code-reader-for-android/actions/workflows/ci.yml)
[![Release](https://github.com/leocb/on-screen-qr-code-reader-for-android/actions/workflows/release.yml/badge.svg)](https://github.com/leocb/on-screen-qr-code-reader-for-android/actions/workflows/release.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

*On Screen QR Code Reader for Android*

An Android app that reads QR codes **shown on your screen** — no second device, no “scan a
screenshot” round trip — and also scans with the camera.

Press a Quick Settings tile, the code is decoded in place, and a small card appears on top of the
app you were looking at: copy the raw value, or open whatever the code points to.

---

## What it does

- **Two Quick Settings tiles** — `Screen QR` and `Cam QR`. Both are instant.
- **Screen reading with two engines**
  - **MediaProjection** with a **persistent session**: the capture permission is asked once, then
    every scan is a plain frame grab (see [below](#does-android-really-need-that-dialog-every-time)).
  - **Optional accessibility service**: a single screenshot, no system dialog at all.
- **In-app “Read QR on this screen”** starts a **10 second timer** so you can switch to the app that
  shows the code. The tile skips the timer.
- **Camera scanning** — CameraX + ML Kit, on device, with a viewfinder and torch.
- **Result card** with typed actions:
  - links, `tel:`, `mailto:`, `sms:`, `geo:`, Play Store and any other app scheme;
  - **Pix / EMVCo payment codes** (merchant, Pix key, city, amount) and **UPI** payments;
  - **crypto payment URIs** (`bitcoin:`, `ethereum:`, `lightning:`, …);
  - **“Open with…”** — a chooser that overrides the default app, for codes that a specific bank,
    wallet or store app should handle;
  - `intent://` links fall back to their `browser_fallback_url` when the target app isn’t installed;
  - Wi-Fi networks (reveal/copy password), vCards, `otpauth://`, plain text.
- **History** of the 20 most recent scans, tap to reopen, one button to clear.
- **English and Brazilian Portuguese** (default English, pt-BR translation), including the per-app
  language picker on Android 13+.
- Copy-after-scanning option, dark/light theme, no network permission, nothing leaves the device.

## Build and install

Requirements: JDK 17+ (JDK 21 recommended), Android SDK with platform 36 and build-tools 35+.

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio and press Run. `local.properties` points at the SDK; Android
Studio rewrites it if your path is different.

- minSdk 26 (Android 8), targetSdk 36.
- Decoding: ZXing core for screenshots, ML Kit barcode scanning for the camera (bundled model, works
  without Google Play Services).

## Using it

1. **Tiles.** App → *Quick Settings tiles* → **Add** next to *Read screen* and *Scan with camera*.
   On Android 13+ the system asks for confirmation; on older versions drag the tile in from the
   Quick Settings edit screen.
2. **Accessibility fallback (optional).** *Accessibility fallback* → **Enable** → *Settings →
   Accessibility → Screen QR → On*. *Reading method* then decides whether the fallback is preferred
   (*Automatic*), required (*Accessibility only*) or unused (*Screen capture only*).
3. **In-app scan.** *Read QR on this screen* → grant the capture permission once → the 10 second
   timer starts → switch to the app with the code. The result arrives as a notification and in the
   history.

## Does Android really need that dialog every time?

No. Android asks for screen-capture consent per **MediaProjection session**, not per screenshot. A
session can grab any number of frames, so keeping one alive means paying the dialog once.

That is exactly what this app does:

- The first scan requests the session; `ScreenCaptureService` (a foreground service of type
  `mediaProjection`) keeps it and its virtual display alive.
- Every later scan — tile or in-app — just copies the newest buffered frame. No dialog.
- While the session lives you see the system screen-casting chip and a notification with a **Stop**
  action. Nothing is stored; frames are decoded and dropped.
- The session ends when you tap Stop, close the app, or after 10 minutes without a scan. Turning the
  *Keep the capture session alive* switch off restores the one-prompt-per-scan behaviour.

The accessibility fallback is the other way to avoid the dialog: it needs no permission per scan,
and it never puts a window over the app you are reading.

## How a screen scan works

```
tile tap / in-app button (10 s timer)
   │
   ├─ ScanActivity (fully transparent, not focusable, not touchable)
   │     the app showing the QR stays visible and keeps input focus
   │
   ├─ engine
   │    ├─ live session: copy the newest buffered frame (no dialog)
   │    ├─ first projection scan: one system consent → foreground service
   │    └─ accessibility: takeScreenshot(), retried for ~1 s until a frame decodes
   │
   ├─ decode: ZXing (multi-scale, inverted, multi-barcode) → ML Kit fallback
   │
   └─ result card on top of the same app
         Copy · Open · Open with… · Share · Scan again · Close
```

A tile press starts with the Quick Settings shade still collapsing, so the first frame can be the
panel. Both engines therefore decode-and-retry instead of trusting the first frame.

If a scan finishes while our activity is not in the foreground (the 10 second timer case), the
result is delivered as a notification with **Copy** and **Open** actions — Android 10+ does not let
a background app open activities.

## Payment codes and special actions

Every payload is classified before any action is offered:

| Payload | What you get |
| --- | --- |
| `000201...br.gov.bcb.pix...` | **Pix payment**: merchant, city, Pix key, description, amount, plus a note explaining that the code is pasted into the bank app |
| `upi://pay?...` | **UPI payment**: payee, amount, note, opened with the banking app you pick |
| `bitcoin:` / `ethereum:` / `lightning:` / … | address, amount, label, opened with any installed wallet |
| `intent://…#Intent;…;S.browser_fallback_url=…;end` | opens the app, or the web page if it isn’t installed |
| other `scheme://` | “App link”, opened by the system, with **Open with…** to pick a specific app |
| `https://` | normal link |

Pix is deliberately **copy-only**: a Pix “copia e cola” payload is an EMVCo TLV blob, not a URI,
and there is no public Android link scheme that starts a payment. Banks accept it through their own
paste field, so the app parses the useful fields, copies the raw code and says so. Anything that
*does* have a scheme (`upi:`, `bitcoin:`, custom bank links, `intent:`) is handed to the system so
the owning app opens it.

## About “apps that block screenshots”

When an app sets `FLAG_SECURE` on its window, Android returns a black frame to **every** capture
method, including accessibility screenshots and MediaProjection. That is a platform guarantee, not
something an app can work around without root. Screen QR detects the black frame and says so, and
offers the camera instead.

If a screen scan comes back empty, the app also offers any long, code-like text it can read from the
accessibility tree (useful for “paste” style payment strings) and a **Camera** shortcut.

## Permissions

| Permission | Why |
| --- | --- |
| `CAMERA` | camera scanning (asked when you open the camera) |
| `FOREGROUND_SERVICE` + `..._MEDIA_PROJECTION` | required by Android for a MediaProjection capture session |
| `POST_NOTIFICATIONS` | results of scans that finish while the app is in the background, and the session notice |
| `VIBRATE` | short confirmation buzz |
| Accessibility service (opt-in) | one screenshot per scan, nothing else |

No `INTERNET` permission is declared.

## Project layout

```
app/src/main/java/com/leocb/screenqr/
├── App.kt                        application, foreground tracking
├── Prefs.kt                      settings + scan history (SharedPreferences + StateFlow)
├── MainActivity.kt               Compose home screen, history, settings
├── ScanActivity.kt               invisible capture host + result / error cards
├── CameraScanActivity.kt         CameraX + ML Kit preview and scanning
├── camera/CameraSession.kt       CameraX binding, torch, analysis pause/resume
├── capture/
│   ├── ProjectionSession.kt      long-lived MediaProjection + newest-frame buffer
│   ├── ScreenCaptureService.kt   foreground service that hosts the session
│   └── ScreenQrAccessibilityService.kt  optional fallback: screenshot + on-screen text
├── decode/QrDecoder.kt           ZXing multi-pass + ML Kit fallback, blank-frame detection
├── model/QrPayload.kt            payload classification, Pix/UPI/crypto, intents
├── tiles/TileServices.kt         the two Quick Settings tiles
├── ui/QrResultCard.kt            shared result sheet
├── ui/theme/Theme.kt             Material 3 colours
└── util/                         clipboard, haptics, notifications, notification receiver
```

## Development notes

- Android 14+ defaults the projection dialog to “share one app”; `ScanActivity` asks for
  `MediaProjectionConfig.createConfigForDefaultDisplay()` so the whole display is captured.
- A foreground service must be running before `getMediaProjection()` on Android 14+, and a
  `MediaProjection` instance can only create one virtual display. The session therefore creates it
  once and reuses it; the image reader keeps the newest frame and the capture is a copy.
- With the 10 second timer the service is started while the activity is still visible (Android 12+
  forbids starting foreground services from the background) and the countdown runs in the activity.
- Debug builds dump the last captured frame to
  `/data/data/com.leocb.screenqr/cache/last-capture.png`:

  ```bash
  adb shell run-as com.leocb.screenqr cat /data/data/com.leocb.screenqr/cache/last-capture.png > cap.png
  ```

- Handy while testing:

  ```bash
  adb shell cmd statusbar add-tile com.leocb.screenqr/.tiles.ScreenQrTileService
  adb shell cmd statusbar add-tile com.leocb.screenqr/.tiles.CameraQrTileService
  adb shell settings put secure enabled_accessibility_services \
      com.leocb.screenqr/com.leocb.screenqr.capture.ScreenQrAccessibilityService
  adb shell settings put secure accessibility_enabled 1
  ```

- Icons are Material Symbols (`photo_camera`, `qr_code_scanner`) converted from `assets/*.svg` to
  vector drawables; the app icon reuses the QR glyph.
- Every user-facing string is a resource (`values/strings.xml` is English, `values-pt-rBR/strings.xml`
  is Brazilian Portuguese). Payload titles, field labels and notes are `@StringRes` ids on
  `QrPayload`, so history entries and notifications follow the language too.
- There is an in-app **Language** dropdown at the bottom of the home screen (*System default*,
  *English*, *Português (Brasil)*). On Android 13+ it writes the system per-app locale (the same
  value as *Settings → Apps → Screen QR → Language*, declared in `res/xml/locales_config.xml`); on
  older versions it is stored in preferences and applied by wrapping each activity/service context
  (`AppLocales.wrap`), so tiles and notifications follow it too. Switch languages while testing with:

  ```bash
  adb shell cmd locale set-app-locales com.leocb.screenqr --locales pt-BR
  adb shell cmd locale set-app-locales com.leocb.screenqr --locales en
  ```

## Releasing

- **CI** (`.github/workflows/ci.yml`) runs on every push to `main` and on pull requests: debug
  build, lint, and the debug APK as an artifact.
- **Release** (`.github/workflows/release.yml`) runs on tags, signs the APK and publishes it:

  ```bash
  git tag v1.0.0
  git push origin v1.0.0
  ```

  The version name comes from the tag (`v1.0.0` → `1.0.0`) and the version code is derived from it
  (`v1.2.3` → `10203`), so every Play upload increases. The workflow verifies the signature with
  `apksigner` and attaches
  `ScreenQR-v1.0.0.apk` to the GitHub release.

Signing material is read from environment variables (`ANDROID_KEYSTORE_FILE`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`) or, locally, from
`keystore.properties` (git-ignored). The release workflow expects these repository secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -i ~/.android-keys/screenqr-upload.jks \| tr -d '\n'` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | `screenqr` |
| `ANDROID_KEY_PASSWORD` | key password |

Keep the keystore and its password backed up **outside** the repository: without them Google Play
will not accept updates and the upload key would have to be reset. For Play, build the bundle with
`:app:bundleRelease` (`app/build/outputs/bundle/release/app-release.aab`).

## Known limitations

- `FLAG_SECURE` content is unreadable by design (see above).
- A few apps blank their UI in `onPause`; because the transparent capture activity is resumed during
  the shot, such apps can defeat the screen scan. The camera always works.
- The live session shows Android’s screen-casting chip for as long as it runs. That is the cost of
  not asking every time; **Stop** is one tap away in the notification.
- After a rotation the mirrored frame is letterboxed until the session is restarted (the virtual
  display is sized when the session starts).
- Only QR/Data Matrix/Aztec/PDF417 are attempted on screenshots and QR on camera. Animated or
  multi-frame codes are not stitched together.
