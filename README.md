# OnlyDMs
OnlyDMs is an ultra-thin instagram client that only allows DMs. No reels, no posts, no stories.
This aggressively strips reels, ads, and tracking resources by redirecting to the DMs page.

## Install
Android only - Download and install the OnlyDMs.apk from Releases

## Highlights
- **DM-only navigation** – any attempt to browse outside `/direct/*` transparently reroutes to `https://www.instagram.com/direct/inbox/`.
- **Lean** – blocks video streams, fonts, ads, trackers, and non-essential hosts
- **Faster load** – warm WebView & TLS prefetch from the `Application`, defer image loading until the first inbox render, and lazy-load media afterward.
- **Attachment support** – the in-WebView file picker hands images or short clips to Instagram’s composer, just like the native client.


## [DEVELOPERS ONLY] Build & Run
1. Install JDK 17+ and Android SDK Platform 35.
2. From the repo root run `./gradlew assembleDebug` (or `./gradlew installDebug` with a device connected).
3. Install `app/build/outputs/apk/debug/app-debug.apk` via Android Studio, `adb install`, or drag-drop onto an emulator.
4. Log in with your Instagram account; the app accepts cookies and third-party cookies, so sessions persist across launches.

## Security & Privacy Notes
- `android:usesCleartextTraffic="false"` and `network_security_config.xml` enforce HTTPS only.
- The app never stores credentials; all auth flows happen inside Instagram’s own pages.

## Known Gaps
- No automated UI tests – Instagram requires live auth and changes its DOM frequently.
- No offline support – if the app boots without a network, it simply shows the WebView error page.

Contributions are welcome: keep the APK tiny, avoid extra libraries, and maintain the DM-only contract.
