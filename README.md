# FanqieHook

Removes ads inside the Fanqie (番茄小说) Android app at the **business-logic layer**, before
they ever reach the rendering pipeline. Implemented as an LSPosed / libxposed API 102 module.

> **Target**: `com.dragon.read` v7.3.3.32 (`versionCode=73332`)
> **Framework**: LSPosed IT v2.1.1+ (KernelSU / ZygiskSU)
> **API**: libxposed `io.github.libxposed:api:102.0.0`

## Why

Fanqie (番茄小说) is heavily monetised with Pangle / 穿山甲 and OneStop / MUnion ads. The
existing community hooks (e.g. HookFanqie) are pinned to old class names and crash on the
current APK. This module was reverse-engineered from scratch against the current version
(73332) — every hook target is smali-verified and will fail closed on version mismatch.

## What it does

14 hooks in `NsAdImpl`, `ReaderAdManager`, `NsVipImpl`, and `SeriesPauseAdImpl`, intercepting:

| Hook ID | Target | Position blocked |
|---|---|---|
| `read-flow-ad-line` | `NsAdImpl.needReadFlowAdLine(ReaderClient)` | 阅读流广告位 |
| `reader-video-ad` | `NsAdImpl.canReaderVideoAdShow()` | 阅读器视频广告 |
| `reader-ad-for-sati` | `ReaderAdManager.canLoadAd(String)` | 阅读器激励位 |
| `topview-main` | `NsAdImpl.checkCanShowTopViewInMainPage(AbsActivity)` | 首页 TopView |
| `topview-reader` | `NsAdImpl.checkCanShowTopViewInReader(AbsActivity, ReaderClient, String)` | 阅读器 TopView |
| `series-pause-enable` | `SeriesPauseAdImpl.enablePauseAd()` | 短剧暂停广告-总开关 |
| `series-pause-show` | `SeriesPauseAdImpl.canShowPauseAd(h)` | 短剧暂停广告-展示 |
| `position-filter:NsAdImpl` | `NsAdImpl.checkAdAvailable(String, String)` | 广告位白名单（已对照 `BLOCKED_POSITIONS`） |
| `position-filter:h83.a` | `h83.a.checkAdAvailable(String, String)` | 同上的混淆类版本 |
| `hide-vip-entrance` | `NsVipImpl.canShowVipEntranceHere(VipEntrance)` | VIP 入口 |
| `hide-vip-entrance-in-ad` | `NsVipImpl.canShowVipEntranceInAd()` | 广告内的 VIP 入口 |
| `reader-fetch-intercept` | `ReaderAdManager.needInterceptFetchAd(String)` | 阅读器广告预取 |
| `inspire-disable-ad-gift` | `NsAdImpl.disableAdGift()` | 激励尾部礼物动画 |
| `inspire-disable-banner-dismiss-anim` | `NsAdImpl.disableBannerDismissAnimation()` | 激励 Banner 关闭动画 |

`BLOCKED_POSITIONS` whitelist (matching `checkAdAvailable` first arg):

```
splash_ad, page_front_ad, page_middle_ad, page_end_ad,
reader_banner, reader_text_link_ad, reader_disconnected_ad,
reader_ad_for_sati, video_reader_ad,
topview_main, topview_reader, series_pause_ad
```

**User-initiated reward / coin flows are NOT blocked.**

## Build

Requires JDK 17/21 (NOT 25 — Kotlin 2.0.21 rejects JDK 25 as `25.0.1`) and Android SDK with
`platforms;android-35` + `build-tools;35.0.0`.

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/AndroidSDK
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
./gradlew :app:assembleRelease --no-daemon --console=plain
# Output: app/build/outputs/apk/release/app-release.apk
```

## Install

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Then in **LSPosed Manager** → 模块 (Modules):
1. Enable **FanqieHook**
2. Scope: tick **`com.dragon.read`**
3. Soft-reboot or restart `com.dragon.read`

## Safety

* `staticScope=true` + a `scope.list` containing only `com.dragon.read`
* Process gate: only the **main process** `com.dragon.read` installs hooks; child processes
  (`:push`, `:widgetProvider`, `:miniappX`, `:download`, `:privileged_process*`,
  `:sandboxed_process*`, …) load the module but return at `onPackageReady` after process check.
* Version gate: refuses to install if `versionCode != 73332`. On Android 14+ where
  `ActivityThread.currentApplication()` is greylisted, this can falsely trip — see "Failure
  modes" below.

## Failure modes

The module attempts `PackageManager.getPackageArchiveInfo()` (static, no Context) and falls
back to `ActivityThread.currentApplication()` to read `versionCode`. On Android 14+ both may
fail (greylist on `currentApplication`, no Context available at `PackageReadyParam`). In
that case a compile-time `FAIL_OPEN = true` flag lets hooks install anyway with a strong
warning logged. Verify hook installation manually via `adb logcat -s LSPosedLogDaemon:V`
and look for 14 `hook installed:` lines.

## Architecture

```
┌────────────────────────────────────────────────────────────────┐
│  LSPosed IT (KernelSU/Zygisk) — zygote-level injection        │
└────────────────────────────────────────────────────────────────┘
        │
        ▼  onModuleLoaded → onPackageReady
┌────────────────────────────────────────────────────────────────┐
│  FanqieModule                                                   │
│    • process gate (only main com.dragon.read)                   │
│    • version gate (fail-closed / fail-open via compile flag)    │
│    • instantiates HookManager                                   │
└────────────────────────────────────────────────────────────────┘
        │
        ▼  AdHooks.installAll()
┌────────────────────────────────────────────────────────────────┐
│  HookManager + ClassResolver                                    │
│    • replaceBooleanFalse/True for boolean returns              │
│    • try/catch per hook — one failure cannot stop the rest     │
└────────────────────────────────────────────────────────────────┘
        │
        ▼
┌────────────────────────────────────────────────────────────────┐
│  Target classes (smali-verified at known line numbers)         │
│    com.dragon.read.component.biz.impl.NsAdImpl                 │
│    com.dragon.read.component.biz.impl.NsVipImpl                │
│    com.dragon.read.reader.ad.ReaderAdManager                    │
│    com.dragon.read.ad.onestop.seriespause.impl.SeriesPauseAdImpl│
│    h83.a (obfuscated mirror)                                    │
└────────────────────────────────────────────────────────────────┘
```

## Verification (real device, POCO dada / HyperOS 2 / Android 16)

```text
[INFO] module loaded: process=com.dragon.read api=102 framework=LSPosed v2.1.1-it
[INFO] target ready: package=com.dragon.read process=com.dragon.read versionCode=...
[INFO] hook installed: read-flow-ad-line -> NsAdImpl#needReadFlowAdLine
[INFO] hook installed: reader-video-ad -> NsAdImpl#canReaderVideoAdShow
[INFO] hook installed: reader-ad-for-sati -> ReaderAdManager#canLoadAd
[INFO] hook installed: topview-main -> NsAdImpl#checkCanShowTopViewInMainPage
[INFO] hook installed: topview-reader -> NsAdImpl#checkCanShowTopViewInReader
[INFO] hook installed: series-pause-enable -> SeriesPauseAdImpl#enablePauseAd
[INFO] hook installed: series-pause-show -> SeriesPauseAdImpl#canShowPauseAd
[INFO] hook installed: position-filter:com.dragon.read.component.biz.impl.NsAdImpl -> NsAdImpl#checkAdAvailable
[INFO] hook installed: position-filter:h83.a -> h83.a#checkAdAvailable
[INFO] hook installed: hide-vip-entrance -> NsVipImpl#canShowVipEntranceHere
[INFO] hook installed: hide-vip-entrance-in-ad -> NsVipImpl#canShowVipEntranceInAd
[INFO] hook installed: reader-fetch-intercept -> ReaderAdManager#needInterceptFetchAd
[INFO] hook installed: inspire-disable-ad-gift -> NsAdImpl#disableAdGift
[INFO] hook installed: inspire-disable-banner-dismiss-anim -> NsAdImpl#disableBannerDismissAnimation
[INFO] blocked ad position=reader_banner source=AT via h83.a.checkAdAvailable
[INFO] blocked ad position=video_reader_ad source=null via h83.a.checkAdAvailable
```

14 of 14 hooks installed; multiple `blocked ad position=` entries observed during normal use.

## Notes for other versions

The hook targets are tied to **versionCode 73332**. When Fanqie publishes a new build:

1. Pull the new APK and re-run the round-1 reverse analysis (jadx + apktool + smali verify)
2. Bump `TARGET_VERSION_CODE` in `FanqieModule.kt`
3. Re-verify each hook target — class names and method signatures can be obfuscated
4. The fail-closed gate is your friend here; do not enable `FAIL_OPEN` without verifying

## License

MIT — see `LICENSE`.

## Disclaimer

This module is for personal use and educational purposes. Bypassing in-app advertising may
violate Fanqie's Terms of Service. Use at your own risk.