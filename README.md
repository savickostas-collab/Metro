# Metro Launcher

A Windows-Phone-style home screen for Android (8.0+). No dependencies, plain Kotlin + framework Views.

## Build
**Android Studio:** open this folder, press Run (or Build > Build APK).
**No PC / no Android Studio:** push this folder to a GitHub repo. The included workflow
(`.github/workflows/build.yml`) builds the APK; download it from the run's Artifacts.
**Command line:** `./gradlew assembleDebug` -> `app/build/outputs/apk/debug/app-debug.apk`

## Use
1. Install the APK, open "Metro" once, then press Home and choose Metro > Always.
   (Or Settings > Apps > Default apps > Home app.) Switch back to One UI the same way.
2. Start screen: tap a tile to open (turnstile animation). Long-press a tile for edit mode:
   drag to reorder, ⊗ to unpin, arrow to cycle small / medium / wide.
3. Swipe left or tap → for the app list. Tap a letter to jump. Long-press an app to pin, info, uninstall.
4. "···" menu: accent colour (20 WP8 colours), dark/light, live-tile previews, add clock tile.
5. Live tile notifications: "···" > enable live tile notifications and grant access.
   Tiles then show a count and flip to the latest notification. Turn previews off any time.

## Notes
- Apps with a monochrome themed icon (Android 13+) render as white glyphs on the accent colour; others show their normal icon.
- Portrait only. Not yet built or run on a device by the author of this code: expect to fix small things.
