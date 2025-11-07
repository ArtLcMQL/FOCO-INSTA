# Repository Guidelines

## Project Structure & Module Organization
Module layout is intentionally lean: the single `:app` module drives the Android client, with Compose-first UI and state logic in `app/src/main/java`, theming and drawable assets in `app/src/main/res`, JVM tests in `app/src/test`, and instrumented scenarios in `app/src/androidTest`. Dependency versions are centralized in `gradle/libs.versions.toml`, while the Gradle wrapper and `settings.gradle.kts` lock tooling and repo name (`InstaDMs`) so every contributor builds against the same stack.

## Build, Test, and Development Commands
- `./gradlew :app:assembleDebug` – compile and package a debuggable APK; run this before sharing artifacts.
- `./gradlew :app:lint` – execute Android Lint plus Compose-specific checks; fix warnings before a PR.
- `./gradlew :app:testDebugUnitTest` – run JVM tests under Robolectric-free JUnit.
- `./gradlew :app:connectedAndroidTest` – instrumented UI tests on an emulator or device; ensure one is online first (`adb devices`).

## Coding Style & Naming Conventions
`gradle.properties` enforces the Kotlin “official” code style; keep 4-space indentation, trailing commas for multiline parameters, and favor expression bodies for lightweight functions. Compose screens and navigation surfaces should follow `FeatureScreen`/`FeatureRoute` naming, while view models stick to `FeatureViewModel`. Resource IDs use snake_case prefixes (`ic_dm_send`, `color_primary`). Update `libs.versions.toml` when touching dependencies rather than hard-coding coordinates.

## Testing Guidelines
Unit suites rely on JUnit 4 (`libs.junit`), so place files under `app/src/test/java/...` and name them `FeatureTest`. Instrumented flows use AndroidX Test, Espresso, and Compose UI test APIs (`androidTestImplementation` stack); name files `FeatureAndroidTest`. Aim to cover branching logic in state holders and at least one navigation path per screen. Always verify JVM tests (`testDebugUnitTest`) plus either `lint` or `connectedAndroidTest` before tagging reviewers.

## Commit & Pull Request Guidelines
No Git history ships with this workspace, so adopt Conventional Commits (`feat:`, `fix:`, `chore:`) to build a consistent log going forward; keep subject lines under 72 characters and describe scope. Every PR should include: purpose summary, screenshots or screen recordings for UI tweaks, linked issue or task ID, and a checklist noting which Gradle tasks were run (`assembleDebug`, `lint`, `testDebugUnitTest`). Keep diffs focused—spin up follow-on PRs for cleanup to reduce review load.

## Security & Configuration Tips
Never commit `local.properties` or API keys; reference them via Gradle properties or encrypted CI secrets. The project expects Android SDK 36/26 (compile/min); ensure your local SDK Manager matches to avoid mismatched manifests. When adjusting manifest permissions or network security config, document the rationale in the PR to help downstream audits.
