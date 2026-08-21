# FanqieHook

针对番茄小说 (com.dragon.read) 主进程，在**业务逻辑层**拦截广告，使其永远不会进入渲染管线。基于
LSPosed / libxposed API 102 实现。

> **目标版本**：`com.dragon.read` v7.3.3.32（`versionCode=73332`）
> **框架要求**：LSPosed IT v2.1.1+（KernelSU / ZygiskSU）
> **API**：`io.github.libxposed:api:102.0.0`

## 为什么需要这个模块

番茄小说重度依赖穿山甲（Pangle）、OneStop、MUnion 广告 SDK。社区现存的 HookFanqie 等模块绑定了
旧版本的类名，在新版本 APK 上会崩溃。本模块从零逆向当前版本（73332），所有 hook 目标都经过
smali 行号验证，版本不匹配时自动 fail-closed 拒绝安装。

## 它做什么

14 个 hook 注入到 `NsAdImpl`、`ReaderAdManager`、`NsVipImpl`、`SeriesPauseAdImpl`，拦截：

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

**用户主动触发的奖励 / 金币流程不会被屏蔽。**

## 编译

依赖 JDK 17/21（**不可用 JDK 25** —— Kotlin 2.0.21 会把 `25.0.1` 抛 `IllegalArgumentException`），
以及 Android SDK（`platforms;android-35` + `build-tools;35.0.0`）。

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/AndroidSDK
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
./gradlew :app:assembleRelease --no-daemon --console=plain
# 产物：app/build/outputs/apk/release/app-release.apk
```

## 安装

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

然后打开 **LSPosed Manager** → 模块：
1. 勾选 **FanqieHook**
2. 作用域：勾选 **`com.dragon.read`**
3. 软重启或强制重启 `com.dragon.read`

## 安全性

* `staticScope=true` + `scope.list` 仅声明 `com.dragon.read`
* **进程守门**：仅在 `com.dragon.read` **主进程**安装 hook；子进程（`:push`、`:widgetProvider`、
  `:miniappX`、`:download`、`:privileged_process*`、`:sandboxed_process*` …）会加载模块但在
  `onPackageReady` 进程检查后直接返回
* **版本守门**：`versionCode != 73332` 时拒绝安装。Android 14+ 上 `ActivityThread.currentApplication()`
  被 hidden API greylist 屏蔽，可能误触发——见下文"失败模式"

## 失败模式

模块优先尝试 `PackageManager.getPackageArchiveInfo()`（公开静态 API，无需 Context），失败时回退
到 `ActivityThread.currentApplication()` 反射读取 `versionCode`。Android 14+ 两个策略都可能被
greylist 阻挡，此时编译开关 `FAIL_OPEN = true` 会让 hook 仍然安装并留下强警告日志。可通过
`adb logcat -s LSPosedLogDaemon:V` 验证，看到 14 条 `hook installed:` 即表示所有 hook 已生效。

## 架构

```
┌────────────────────────────────────────────────────────────────┐
│  LSPosed IT（KernelSU/Zygisk）—— zygote 层注入                 │
└────────────────────────────────────────────────────────────────┘
        │
        ▼  onModuleLoaded → onPackageReady
┌────────────────────────────────────────────────────────────────┐
│  FanqieModule                                                   │
│    • 进程守门（仅主进程 com.dragon.read）                       │
│    • 版本守门（fail-closed / 编译开关切 fail-open）             │
│    • 实例化 HookManager                                          │
└────────────────────────────────────────────────────────────────┘
        │
        ▼  AdHooks.installAll()
┌────────────────────────────────────────────────────────────────┐
│  HookManager + ClassResolver                                    │
│    • replaceBooleanFalse/True 拦截布尔返回值                    │
│    • 每个 hook 独立 try/catch，单点失败不影响其他 hook          │
└────────────────────────────────────────────────────────────────┘
        │
        ▼
┌────────────────────────────────────────────────────────────────┐
│  目标类（smali 行号验证）                                       │
│    com.dragon.read.component.biz.impl.NsAdImpl                  │
│    com.dragon.read.component.biz.impl.NsVipImpl                 │
│    com.dragon.read.reader.ad.ReaderAdManager                     │
│    com.dragon.read.ad.onestop.seriespause.impl.SeriesPauseAdImpl│
│    h83.a（混淆类镜像）                                          │
└────────────────────────────────────────────────────────────────┘
```

## 真机验证（POCO dada / HyperOS 2 / Android 16）

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

14 / 14 hook 全部安装；正常使用中观察到多条 `blocked ad position=` 拦截日志。

## 升级到新版本的步骤

所有 hook 目标**硬绑定** `versionCode=73332`。番茄发新版时：

1. 拉新 APK 重新跑第一轮逆向分析（jadx + apktool + smali 行号验证）
2. 在 `FanqieModule.kt` 中更新 `TARGET_VERSION_CODE`
3. 重新核对每个 hook 目标——类名、方法签名可能被混淆
4. 谨慎使用 fail-closed 守门；不要盲目开启 `FAIL_OPEN`

## 许可证

MIT —— 见 `LICENSE`。

## 免责声明

本模块仅供个人使用和学习研究。绕过应用内广告可能违反番茄小说《用户协议》，使用风险自担。