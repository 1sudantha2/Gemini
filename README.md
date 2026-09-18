# Gemini — ultra-light Android wrapper for gemini.google.com

A tiny Kotlin app (`com.gemini.webapp`) that opens **https://gemini.google.com/app** in a
hardware-accelerated WebView tuned for low RAM / low CPU and instant loads.

## How it stays fast
| Technique | Where |
|---|---|
| Static site "shell" (JS/CSS/fonts/images/wasm) stored **permanently on disk** and served from disk on every launch, revalidated in the background every 6 h | `ShellCache.kt` |
| Dynamic content (conversation API / `batchexecute` / JSON) always goes straight to the network — never cached | `ShellCache.isStatic()` |
| Splash screen held only until the first WebView paint (max 1.5 s) | `MainActivity.kt` |
| JS timers paused when backgrounded → ~0 % CPU in background | `onPause()` |
| Renderer-crash recovery instead of app crash on memory pressure | `onRenderProcessGone()` |
| R8 minify + resource shrinking, no Compose/Material, single Activity | `app/build.gradle.kts` |
| Algorithmic dark mode, pull-to-refresh, camera/mic & file upload support, Google sign-in kept in-app | `MainActivity.kt` |

## Get the APK from GitHub
1. Push to any branch → **Actions → Build APK** runs automatically.
2. Download the `Gemini-apk` artifact from the workflow run (or the GitHub Release created on `main`).
3. Install `Gemini.apk` (release build, signed with the debug key — replace `signingConfig` for Play-store distribution).

Or trigger manually: **Actions → Build APK → Run workflow**.

## Local build
```bash
gradle wrapper --gradle-version 8.7   # first time only
./gradlew :app:assembleRelease
```
