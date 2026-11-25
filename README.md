# Metro Launcher

A Windows Phone 8–style home screen for Android 8.0+. It's flat, typographic and bold, with multi-colored tiles.

## Get the APK
Every push is built by GitHub Actions (`.github/workflows/build.yml`), which runs the tests,
builds a signed release APK, and publishes it under **Releases** as `MetroLauncher.apk`.
All builds share one signing key (`keystore/`), so a new build installs over the previous one.

Build locally: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`

## First run
1. Install, open **Metro**, press Home and pick **Metro → Always**.
2. Open **metro settings** (a small tile on Start, or in the app list) to grant live-tile data:
   notifications, photos, people, calendar and weather (approximate location).

## What's in it
- **Start screen**: 4-column grid with 8dp gaps. Tiles come in three sizes: small 1×1, medium 2×2 and wide 4×2. Corners are square, and the background is pure black (or white in the light theme).
- **Colors**: multi-color by default. New pins take the next palette color, and two tiles of the same color never sit
  side by side. Modes are multi-color, single accent (stock WP; the accent also tints headers and toggles) and shuffle daily.
- **Edit mode**: long-press a tile. The other tiles shrink and wobble. You can drag to reorder (the grid reflows live and scrolls at the edges),
  use ⊗ to unpin, the arrow to cycle medium → small → wide, or the mosaic button to set the tile's color (palette or any hex) and turn its live updates on or off.
- **Live tiles**: a vertical 3D flip every 5–10 s, staggered so tiles never flip together. Supported tiles:
  - Messages and any app with notifications: unread count and the latest message.
  - Photos: recent pictures, full-bleed.
  - People: a mosaic of contact photos.
  - Calendar: your next event.
  - Weather: temperature from Open-Meteo.
- **App list**: swipe left or tap the arrow. Letters are colored squares; tap one for the jump grid. Long-press an app to pin, uninstall or see info. The search box is at the top.
- **Motion**: tiles tilt toward your finger, the turnstile plays when you open an app (and reverses when you come back), and tiles fly in on entry.
  Everything runs on the cubic-bezier(0.1, 0.9, 0.2, 1) curve. Scrolling uses parallax over an optional background photo.
- **Navigation bar**: back, start (Windows logo) and search. It replaces the system bar on the start screen; swipe up to reveal the system one. You can turn it off in settings.
- **Lock screen** (optional): big light clock, date and unread counts. Tap to see it bounce, swipe up to unlock.
  It needs the "display over other apps" permission and shows a quiet ongoing notification.

## Metro apps
Built in and pinned to Start, all in the same pivot style (big lowercase page headers you swipe between):
- **phone**: history (missed calls in the accent color, repeat calls folded as "missed (3)"), a keypad with
  key tones and contact matching, and contacts with favourites and the letter jump grid. The tile shows missed calls.
  Metro can also be picked as the dialer when another app opens a phone number. Android's own in-call screen still handles the call.
- **music**: follows whatever Spotify (or any player) is playing through notification access, so no login is needed.
  It shows album art, title, artist and a seek bar, with previous, play/pause and next plus Spotify's own actions (like, shuffle and so on).
  "recent" lists songs you've played, and tapping one plays it again. The wide tile shows the album art and flips to the song name.
- **weather**: today (details, sunrise and sunset), the next 24 hours, and the week, from Open-Meteo. It uses your location or a city you pick,
  so the tile works without location access too.
- **live tiles setup**: shown once on first run, and it asks for each permission with a note on what it brings to the start screen.

## Notes
- Font: Open Sans Light (SIL OFL, `app/src/main/assets/fonts/OFL.txt`) stands in for Segoe WP.
- Icons are white glyphs from each app's monochrome icon. If an app has no usable glyph, its first letter is used instead.
  You can switch to original icons in settings.
- Android doesn't let a launcher replace the real system lock screen. The Metro lock screen sits on top of it,
  so a PIN or fingerprint still applies after you swipe.
