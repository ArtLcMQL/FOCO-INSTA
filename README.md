# OnlyDMs

OnlyDMs (a.k.a. *NoReel Lite*) is an ultra-slim Android WebView wrapper that locks Instagram to the `/direct/*` surface. It aggressively strips reels, ads, and tracking resources so you can get straight to messages with minimal battery, CPU, and bandwidth overhead.

## Highlights
- **DM-only navigation** – any attempt to browse outside `/direct/*` transparently reroutes to `https://www.instagram.com/direct/inbox/`.
- **Lean networking** – `shouldInterceptRequest` blocks video streams, fonts, ads, trackers, and non-essential hosts, shaving seconds off first paint.
- **Faster first contentful paint** – warm WebView & TLS prefetch from the `Application`, defer image loading until the first inbox render, and lazy-load media afterward.
- **Dark-mode aware** – delegates to `WebSettingsCompat.setForceDark` when the device supports it.
- **Attachment support** – the in-WebView file picker hands images or short clips to Instagram’s composer, just like the native client.

## Project Layout
```
app/
 ├─ src/main/java/com/example/onlydms    # MainActivity + Application
 ├─ src/main/res                        # Launcher icons, strings, themes, manifest
 ├─ proguard-rules.pro                  # Keeps WebView classes when minified
 └─ build.gradle.kts                    # Kotlin-only, no Compose/Fragments
```
Tooling lives in the Gradle wrapper (`./gradlew`) with AGP 8.5.2, Kotlin 1.9.24, and SDK 35/26/35 (compile/min/target).

## Build & Run
1. Install JDK 17+ and Android SDK Platform 35.
2. From the repo root run `./gradlew assembleDebug` (or `./gradlew installDebug` with a device connected).
3. Install `app/build/outputs/apk/debug/app-debug.apk` via Android Studio, `adb install`, or drag-drop onto an emulator.
4. Log in with your Instagram account; the app accepts cookies and third-party cookies, so sessions persist across launches.

### Debug tips
- To inspect the DOM, enable WebView debugging with `WebView.setWebContentsDebuggingEnabled(true)` in `MainActivity` and attach Chrome DevTools.
- If you need to toggle the resource blocklist, edit `HEAVY_PATTERNS` in `MainActivity` and rerun `./gradlew assembleDebug`.

## Icon Workflow
The default launcher art ships from Android Studio’s template. To use the DM glyph:
1. Convert the provided SVG via `scripts/convert.py` (see `svgtopng/` helper) to produce `dm_icon_1024px.png`.
2. In Android Studio, choose **File ▸ New ▸ Image Asset → Adaptive** and feed the PNG as the foreground layer.
3. Replace `@mipmap/ic_launcher` references only after checking every density folder (`mipmap-*`).

## Security & Privacy Notes
- `android:usesCleartextTraffic="false"` and `network_security_config.xml` enforce HTTPS only.
- The app never stores credentials; all auth flows happen inside Instagram’s own pages.
- If you need to reset a session, clear cookies via Android settings or call `CookieManager.getInstance().removeAllCookies(null)`.

## Known Gaps
- No automated UI tests – Instagram requires live auth and changes its DOM frequently.
- No offline support – if the app boots without a network, it simply shows the WebView error page.
- Localized strings beyond English are not provided yet.

Contributions are welcome: keep the APK tiny, avoid extra libraries, and maintain the DM-only contract. Ideas that don’t respect those principles likely belong in a fork. 
