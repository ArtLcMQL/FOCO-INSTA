# Claude Brief – OnlyDMs

## Mission
Ship and maintain an ultra-light Instagram Direct wrapper that:
- Locks navigation to `https://www.instagram.com/direct/inbox/` and other `/direct/*` paths.
- Blocks reels, ads, analytics, fonts, and heavyweight media to keep CPU/network usage tiny.
- Stays single-activity, single-WebView (no Compose/Fragments).
- Keeps hardware acceleration enabled and targets SDK 35 / min 26.

## Key Components
- `MainActivity` – owns the WebView, guards navigation, injects DOM scripts, and handles attachment uploads via `WebChromeClient.onShowFileChooser`.
- `NoReelLiteApp` – warms Chromium and prefetches a TLS connection to Instagram on process start.
- `network_security_config.xml` – enforces HTTPS only.
- `proguard-rules.pro` – preserves WebView classes when minified; keep this lean.

## Build / Test
- `./gradlew assembleDebug` – primary build.
- `./gradlew lint` – static checks (run before PRs).
- UI/integration tests are intentionally omitted because Instagram auth + DOM drift make them brittle.

## Coding Guardrails
- No new libraries beyond `androidx.activity:activity-ktx` and `androidx.webkit:webkit`.
- Prefer Kotlin 1.9 idioms but stay platform-aligned (no coroutines unless absolutely necessary).
- DOM injections should be tiny, self-contained functions; always guard repeated `setInterval` calls with clear timeouts.
- Resource filter lives in `HEAVY_PATTERNS`; update cautiously.

## Nice-To-Haves (when scoped)
- Document any warm-start tricks or caching tweaks in README changelog form.
- Keep APK diff small; avoid assets >100KB unless essential (e.g., icon layers).
- When adding user-facing switches (e.g., “Load images automatically”), use shared prefs and simple Material dialogs—no settings screens.

## Out of Scope
- General Instagram browsing, reels, stories, or feed features.
- Push notifications, background services, or multiple activities.
- Embedding third-party analytics/ads.

Ping maintainers before changing the navigation guard, dependency versions, or minSdk. This app survives by staying laser-focused on DMs. 
