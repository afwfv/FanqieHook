# FanqieHook

面向番茄小说与红果免费短剧 Android 客户端的 LSPosed 去广告模块，26 个 hook 拦截广告展示。

## 功能

- 拦截阅读器广告：底部 banner、视频广告、翻页信息流、文字链接、章节断开处广告
- 拦截首页与阅读器 TopView 广告
- 拦截听书信息流 / 贴片广告
- 拦截短剧暂停广告
- 拦截红果短剧 banner、贴片、横屏插入广告
- 拦截红果热启动开屏广告（Activity 级阻断）
- 拦截番茄开屏 / 全屏福利广告：挂在公共判定点 `NsUtilsDependImpl.canShowScreenAd` +
  全屏广告管理器 `IActivityScreenAdManager` 的判定方法（v0.6.2 新增，见下）
- 拦截评论列表 / 短剧评论、故事插页、创作者广告、短视频进度条插入广告（v0.6.0 新增，见下）
- 保留用户主动点击的激励视频 / 金币 / 看广告免广告按钮

### 激励秒领：**做不到**（已完整证伪，默认关闭）

「看 30 秒激励视频 → 秒领金币」这个功能在番茄 7.3.7.32 上**客户端无法实现**。
三轮真机实验的完整证据链写在这里，避免后来者重复投入：

**第 1 轮（错误结论，已证伪）**：以为 `RewardDisplayImpl#onRewardVerifyCommon(Z Z I)` 是发奖实现，
调它 → 成功、无异常、金币不增加。原始指令转储显示该方法只有 45 条指令
（`iget-object → 拼日志 → 打日志 → 取单例(iv1.b#o) → iput → return`）——**它只是埋点函数**；
`RewardDisplayImpl` 整体只是 `IRewardDisplayService` 的桥接实现。

**第 2 轮（采到真实样本）**：用 `installProbe` 抓完整看完一次激励视频的真实成功回调：

```
probe[reward-open]    ATInspireOpenerImpl#showInspire(Activity, ATParams, yh.e)
                      → 第三个参数就是回调实例（真实类型 lv1.r$a）
probe[reward-result]  uh.k = InspireVerifyResult(rewardType=2, rewardStage=0, customRewardType=1,
                           isMoreOne=false, adSource=AT, moreOneTime=0, passThroughParams=null)
                      混淆字段：a=2 b=0 c=1 d=false e=AT f=0 g=null
probe[reward-cb-e]    e(1,false,false)   (yh.e#e(IZZ) = onAdClose(?, 能否得奖励, 是否再得))
```

**第 3 轮（合成派发，两种变体都失败）**：合成与真实样本**逐字段一致**的对象，反射调用
`lv1.r$a#b(结果)`（SDK 真正回传成功时调的就是它）：

- 立即派发 + 补发 `e(...)` + 关广告 → App 提示**「活动繁忙」**，广告继续播放，金币不到账
- 最小实验：延后 3 秒、只派发 `b(结果)`、不补 `e`、不关广告 → 广告正常播放，金币仍不到账

两次派发都被日志与探针确认执行成功（零异常），对象与真实样本完全相同。

**根本原因**：发奖申领走 RPC `com.dragon.read.rpc.model.ReaderAdRewardRequest`，
它的**全部字段**只有三个：

```
fieldTypeClassRef : java.lang.Class
serialVersionUID  : long
reqType           : ReaderAdReawrdType      ← 只有一个「奖励类型」枚举
```

**请求里没有任何来自广告 SDK 的完成凭证。** 客户端只能说"给我这类奖励"，发不发完全由服务端
依据自己的广告完成记录决定。伪造客户端回调改变不了服务端记录，因此必然被拒（即「活动繁忙」）。

除非去伪造/重放服务端的发奖响应（只会得到本地假象，且极可能触发风控），否则这条路走不通。

### 开屏 / 全屏广告（v0.6.2）

番茄的开屏广告**不走** `NsAdImpl` 的开屏位置，**也不走** `OpeningScreenADActivity`
（后者是红果的 Activity 路径）—— 这是 v0.6.1 之前一直拦不住它的原因。
实际的决策点是：

| 闸门 | 说明 |
|---|---|
| `NsUtilsDependImpl.canShowScreenAd(Object)Z` | 依赖层公共判定点，接口 `NsUtilsDepend` 声明；全库 287353 个类中仅此一个实现 |
| `IActivityScreenAdManager` 实现类上的零参 boolean 方法 | 全屏福利广告管理器；73732 上实现类唯一（`ua3.f`），判定方法为 `a()` 与 `onScreenAdDialogShow()` |

两者都按「DexKit 接口反查 + 返回类型」挂载，不写死混淆名，因此不受改名影响。

### 广告位覆盖（v0.6.0）

`BLOCKED_POSITIONS` 由 10 项扩至 **21 项**。名单不是从字符串池猜的，而是对广告闸门
`checkAdAvailable(position, source)` 做**反向可达性 + 常量流分析**——包括 position 作为参数
透传的包装方法——提取出真正流入闸门的 position 常量，再逐个反汇编调用点归类。73532 与 73732
流入闸门的常量集合完全相同（29 个），因此这是长期覆盖缺口而非版本回归。

| 分类 | 位置 |
|---|---|
| 阅读器 / 首页 | `splash_ad`、`page_front_ad`、`page_middle_ad`、`page_end_ad`、`reader_banner`、`reader_text_link_ad`、`reader_disconnected_ad`、`reader_ad_for_sati`、`video_reader_ad`、`series_pause_ad` |
| v0.6.0 新增 | `comment_list_ad`、`series_comment_ad`、`story_ad`、`creator_ad`、`processed_ad`、`landscape_short_series_ad`、`landscape_short_series_pause_ad`、`short_series_ad`、`short_series_banner`、`audio_info_flow_ad`、`audio_patch_ad` |
| **显式保留**（用户主动激励） | `reader_gold_coin_popup`、`video_tts_ad`、`video_voice_ad`、`video_reward_gift_ad`、`video_reader_end_urge_update` |

模块同时会把**未分类**的广告位记一条日志（每进程每位置一次，仅打日志、不影响返回值），
便于下一轮适配直接从实机数据扩名单：

```bash
adb shell su -c 'grep -a "unlisted ad position" /data/adb/lspd/log/modules_*.log'
```

## 兼容范围

| 项目 | 内容 |
|---|---|
| 当前支持 | LSPosed Modern API（101+，102 已适配）|
| 已验证应用 | **番茄小说 `com.dragon.read` v7.3.7.32（versionCode 73732）**；**红果免费短剧 `com.phoenix.read` v7.3.7.32（73732）**；同时保留 v7.3.5.32（73532）双端支持 |
| 静态复核结果 | 73732 番茄 25/26 命中（缺 `HongguoBannerServiceImpl` 为红果专属）；73732 红果 26/26；全部 hook 调用点数量与 73532 一致 |
| 实机复核 | OnePlus 9R / Android 14 / LSPosed v2.2.0：番茄 7.3.7.32 与红果 7.3.7.32 均通过版本门禁（versionCode=73732），hook 安装成功 |
| Android | 8.0（API 26）及以上 |
| 作用域 | `com.dragon.read`、`com.phoenix.read` |
| 体积 | **0.47 MB**（v0.6.3 起；此前 1.84 MB）。宿主只打包 arm64-v8a，模块侧 DexKit 同理；DEX 经 R8 收缩后 570 KB |
| 模块包名 | `dev.operit.fanqiehook` |
| 模块版本 | v0.6.3（versionCode 20）|

> 模块针对 v7.3.7.32 / v7.3.5.32 的运行时结构适配。两个版本共用同一套 hook 实现——73732 未移动任何 hook 目标，
> 仅混淆参数类型（`ti4.h` → `vq4.i`）与 DexKit 反查的实现类名（`fe3.a` → `lf3.a` / `yb3.a`）发生变化，
> 两者都由既有的版本容错机制自动吸收。应用再次升级后，广告类名、方法名或调用链可能变化，届时需要重新适配。

## 安装与使用

1. 安装 LSPosed 框架。
2. 安装 Release 里的 `app-release.apk`（或直接在 LSPosed 模块中心安装/更新）。
3. 在 LSPosed 中启用模块，勾选作用域 `com.dragon.read` 与 `com.phoenix.read`。
4. 强制停止番茄小说 / 红果免费短剧后重新打开。

验证：

```bash
adb logcat -s LSPosedLogDaemon:V | grep FanqieHook
# 应看到 "hook installed:" 记录，实际使用时出现 "blocked ad position=..."
```

> 模块日志默认写进 LSPosed 自己的日志文件而非 logcat，用这条更准：
> `adb shell su -c 'grep -a FanqieHook /data/adb/lspd/log/modules_*.log | tail -60'`

> **开发者注意：仓库里有两套签名密钥。** CI 发布版用固定 keystore（SHA-1 `b5671e7e…`），
> 而本机 `./gradlew :app:assembleRelease` 未设 `KEYSTORE_PATH` 时会回退 `~/.android/debug.keystore`
> （SHA-1 `539f6674…`）。两者签名不同，**不能互相覆盖安装**，混装前需先卸载。
> 对外发布请一律用 CI 产物，否则模块中心老用户会因签名冲突装不上。

## 已验证

- 番茄侧与红果侧 hook 全部安装成功（含 DexKit 反查实现类：番茄 `fe3.a`/`lf3.a`、红果 `yb3.a`）
- 实测拦截：阅读器 banner、视频广告、翻页信息流广告、听书贴片广告、红果开屏与短剧 banner 广告
- v0.4.0 修正 `ExperimentUtil.p0()` → `p()`（p0 在 73532 中不存在），`q0()` 确认为横屏插入广告开关
- v0.5.0 新增 73732：DEX 级复核 26 个目标类/方法签名、逐 hook 的调用点数量比对、位置字符串存在性复核
- v0.5.0 修复版本门禁：Android 14 上两条反射路径全部失败会让门禁静默放行（FAIL_OPEN），
  现改为自行解析宿主 APK 的二进制 AndroidManifest.xml（`ApkVersion.kt`），实机读到 versionCode=73732
- v0.6.1 修正开屏链路：此前按红果的 Activity 路径实现，实测番茄走的是 `SplashActivity` 而非
  `OpeningScreenADActivity`，故补上 `BrandTopViewDisplayStrategy.c()` 与 `enableSeriesFeedTopViewAd()` 两道闸门

## 免责声明

仅供学习研究，绕过广告可能违反番茄小说 / 红果《用户协议》。
