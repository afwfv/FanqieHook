# Changelog

All notable changes to FanqieHook.

## [v0.4.0] - 2026-08-22

### Added
- `AttributionManager` is still in the codebase as `experimental-splash-attribution`,
  gated behind `ENABLE_ATTRIBUTION_SPLASH_BYPASS = false` (defaults to off).
- `versionCode` reader now uses two strategies in priority order:
  1. `PackageManager.getPackageArchiveInfo(sourceDir, flags)` — public static, no Context.
  2. `ActivityThread.currentApplication()` reflection — legacy fallback.
- `FAIL_OPEN` compile-time flag in `FanqieModule.kt`. When `true` AND every strategy
  returns `-1`, hooks install with a strong warning instead of refusing.
  **Default**: `true` (Android 14+ greylist blocks both strategies on some devices).

### Fixed
- `versionCode` always returned `-1` on Android 14+ devices with hidden API greylists,
  causing fail-closed gate to refuse hooks even on the correct APK version.

### Verified
- Real-device deployment on POCO dada (Snapdragon 8 Gen 3 / HyperOS 2 / Android 16).
- LSPosed IT v2.1.1 (7842) successfully loaded module.
- 14 of 14 hooks installed.
- `blocked ad position=reader_banner source=AT via h83.a.checkAdAvailable`
- `blocked ad position=video_reader_ad source=null via h83.a.checkAdAvailable`

## [v0.3.0] - 2026-08-21 — first runnable build

- Modern libxposed API 102 lifecycle: `onModuleLoaded → onPackageReady → onHotReloading`.
- 14 hooks targeting `NsAdImpl`, `ReaderAdManager`, `NsVipImpl`, `SeriesPauseAdImpl`,
  and the `h83.a` obfuscated mirror.
- `HookManager.installBooleanFilter` whitelisting `BLOCKED_POSITIONS` to avoid blocking
  user-initiated reward flows.
- `ClassResolver` with two overloads: `vararg String` and non-vararg `Array<Class<*>>`.