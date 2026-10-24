# Now Playing Art

Free Pebble Time 2 watchface: full-screen album art from whatever is playing on your Android phone.

## You need two apps on the phone

1. **Official [Pebble app](https://play.google.com/store/apps/details?id=coredevices.coreapp)** (Core Devices) — pair your watch. **Rebble is not required** and is not what this project targets.
2. **Now Playing Art companion** (`android/` APK) — reads media notifications and pushes art to the watch.

The watchface PBW alone only shows a placeholder.

## Install

**Watchface:**
```bash
pebble build
pebble install --cloudpebble   # or --phone <dev-ip>
```

**Companion (v1.2.4+):**
```bash
cd android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If the phone only says **App not installed** (no detail): uninstall any old copy first, then install with `adb` so you see the real error (`INSTALL_FAILED_*`). v1.0.4 fixes Pixel 16 KB page / native-library sideload issues.

Then: open **Now Playing Art** → enable **notification access** → select the watchface → play music.

Works with **Core Pebble** (PebbleKit2 + classic fallback). Also listed: legacy Pebble/Rebble packages if installed.
