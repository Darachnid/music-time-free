# Now Playing Art

Free **Pebble Time 2** watchface: shows album art, time, and what’s playing from your Android phone.

You need **two things on your phone**: the official Pebble app (to talk to the watch) and a small **companion app** (to send album art). The watchface file goes on the watch through the Pebble app.

---

## Quick setup

### 1. Pebble app on your phone

Install **[Core Pebble](https://play.google.com/store/apps/details?id=coredevices.coreapp)** from the Play Store and pair your watch if you have not already.

### 2. Companion app on your phone

**[Download Now Playing Art (Android APK)](https://github.com/Darachnid/music-time-free/releases/latest/download/now-playing-art-companion.apk)**

1. Tap the link on your phone (Chrome works well).
2. If Android asks, allow **Install unknown apps** for your browser or **Files** — that is normal for apps outside the Play Store.
3. When the download finishes, open it and tap **Install**.

### 3. Watchface on your watch

**[Download the watchface](https://github.com/Darachnid/music-time-free/releases/latest/download/now-playing-art-watchface.pbw)**

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

New builds: **[All releases](https://github.com/Darachnid/music-time-free/releases/latest)**

---

## For developers

Clone this repo, then:

```bash
pebble build
pebble install --cloudpebble   # watchface

cd android && ./gradlew :app:assembleDebug   # companion APK in app/build/outputs/apk/debug/
```
