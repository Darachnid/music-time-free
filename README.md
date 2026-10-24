# Now Playing Art

**Android only.**

Now Playing Art is a free, open-source Pebble Time 2 watchface with a small Android companion app. It shows full-screen album art from whatever is playing on your phone, plus time, date, battery, song progress, and artist and title at the bottom.

Now Playing Art is inspired by the Apple iPod classic now playing screen—the big cover, the track info, that feeling of living inside the music. Now Playing Art brings album art back to your wrist on Pebble.

The watchface alone cannot load album art. You **must** install the free companion app on your Android phone:

**[Download the Now Playing Art companion (Android APK)](https://github.com/Darachnid/music-time-free/releases/latest/download/now-playing-art-companion.apk)**

You need an Android phone, the Core Pebble app, the Now Playing Art watchface, and the companion app linked above. Turn on notification access, pick Now Playing Art on your watch, and play music on your phone. Album art appears on the watch within a few seconds.

Now Playing Art works with common Android music apps that send media notifications. Not for iPhone. **Android only.**

This project is **open source** and **free**—no cost, no subscription. Source, watchface, and companion builds live in this repo. **Free** downloads are on the [releases page](https://github.com/Darachnid/music-time-free/releases/latest).

---

## Quick setup

### 1. Pebble app on your phone

Install **[Core Pebble](https://play.google.com/store/apps/details?id=coredevices.coreapp)** from the Play Store and pair your watch if you have not already.

### 2. Companion app on your phone (required)

**[Download Now Playing Art companion (Android APK)](https://github.com/Darachnid/music-time-free/releases/latest/download/now-playing-art-companion.apk)**

1. Tap the link on your phone (Chrome works well).
2. If Android asks, allow **Install unknown apps** for your browser or **Files** — that is normal for apps outside the Play Store.
3. When the download finishes, open it and tap **Install**.

### 3. Watchface on your watch

**[Download the watchface (PBW)](https://github.com/Darachnid/music-time-free/releases/latest/download/now-playing-art-watchface.pbw)**

1. Open the downloaded file.
2. Choose **Core Pebble** (or **Pebble**) when asked which app to use.
3. Confirm sending it to your watch.

### 4. Allow notifications

Open **Now Playing Art** on your phone and turn on **notification access** when prompted (Settings → Apps → Now Playing Art → Notifications, if needed).

### 5. Select the watchface

On the watch, pick **Now Playing Art** as your watchface, then play music on your phone. Album art should appear within a few seconds.

---

## Troubleshooting

| Problem | Try this |
|--------|-----------|
| **Install blocked** | Settings → Apps → your browser → **Install unknown apps** → Allow. Download the APK again. |
| **App not installed** | Remove an older “Now Playing Art” from the phone, then install again from the link above. |
| **No album art** | Companion installed? Notification access on? Music actually playing? Watch connected in the Pebble app? |
| **Placeholder only** | Install the **companion APK** — the watchface alone cannot fetch art by itself. |

**Free, open-source releases:** [github.com/Darachnid/music-time-free/releases/latest](https://github.com/Darachnid/music-time-free/releases/latest)

---

## For developers

This repo is open source. Clone it, then:

```bash
pebble build
pebble install --cloudpebble   # watchface

cd android && ./gradlew :app:assembleDebug   # companion APK in app/build/outputs/apk/debug/
```
