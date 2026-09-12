# 更新日志

> 发布时，按 module.prop 中的版本号自动读取对应「## x.x.x」段作为模块中心的更新说明；
> 找不到对应版本段时，回退使用「未发布」段。
> 每次在 debug 分支改动代码后，把中文说明补到当前版本段（发版前记得递增 versionCode / version）。

## 未发布

## 0.7.1
v0.7.1 - 撤回 v0.7.0 的「激励秒领」：实测无效，改为默认关闭
- 实测结论（番茄 7.3.7.32 / 73732，真机日志）：
  hook 能装上、`onAdShow` 会被调用、`onRewardVerifyCommon(true,false,0)` 也能成功执行且不抛异常，
  **但金币不增加** —— v0.7.0 的实现是无效的，已把 `ENABLE_INSTANT_REWARD` 改回 false
- 原因（用原始指令转储确认，不是推断）：`RewardDisplayImpl` 只是 `IRewardDisplayService` 的
  **桥接 + 埋点**实现。`onRewardVerifyCommon(Z Z I)V` 全方法只有 45 条指令：
  `iget-object → 拼日志 → 打日志(bs1.b#c) → 取单例(iv1.b#o) → iput → return`
  即它是日志/状态记录函数，调它只会写一条日志
- 真实链路（逐层核验）：
  App 任务层 → `lv1.s#b(Activity, uh.b, yh.e)`（拉起激励广告，`yh.e` 为回调接口）
  → `lv1.r$a implements yh.e` → `lv1.r$a#b(uh.k)`（读字段 / iput-boolean / 转发）
  → `RewardDisplayImpl`（埋点）。发奖落在 `yh.e` 回调的**调用方**，且金币任务由**服务端权威校验**
- 顺带修掉一个真 bug：`onAdClose(true, null)` 传 null 会抛 `InvocationTargetException`
  （方法内部会读第二个参数），现改为传入 hook 拿到的真实广告对象
- 代码保留但默认关闭，仅作调研记录；README 里写明了「为什么没做出来」与后续可行方向

## 0.7.0
v0.7.0 - 新增「激励秒领」（默认开启）
- 效果：激励视频一开始展示即按「发奖成功」走 App 自己的发奖实现，并立即退出广告视频界面，
  不再需要看满 30 秒
- 实现：挂 `RewardDisplayImpl#onAdShow(Z I Object Object)`（激励视频开始展示）——
  先放行让广告正常展示与上报，再反射调用 App 自己的两个回调：
  1. `onRewardVerifyCommon(true, false, 0)`（canReward / isMoreOne / rewardStage）→ 走 App 的发奖实现
  2. `onAdClose(true, null)` → 内部 `NsAdDepend.exitAdVideo("jili video")` 退出广告界面
  两处调用都包在 runCatching 中，失败只记日志，不影响广告自身流程
- 回调链（已在 73732 上核验）：
  `lv1.r$a#b` → `RewardDisplayImpl#onRewardVerify(Object,Object)` →
  `onRewardVerifyCommon(Z Z I)` ← 真正的发奖落点
- 覆盖面：仅限走 bytedance Tomato 激励服务（`RewardDisplayImpl`）的激励位；
  `com.bytedance.android.ad.reward.*` 等其他激励 SDK 不在范围内
- ⚠️ 风险：本功能是**代替广告平台上报「视频已完成」**。广告主按完成量付费，该行为在广告平台侧
  属于作弊，且番茄有服务端风控，**存在账号被风控的风险**。改为 false 重新构建即可恢复
  「必须真正看完」
- 顺带记录：同类模块用 `closeFragment(boolean)` 实现秒领，但在 73732 上
  `ExcitingVideoFragment.closeFragment` 已是**无参**且只做「通知监听器 + release + finish」，
  **本身不发奖**，因此那套写法在新版本上是失效的

## 0.6.3
v0.6.3 - 体积优化：APK 1.84 MB → 0.47 MB（-74%）
- 去掉无用 ABI：宿主（番茄 73532 / 73732、红果同基线）实测**只打包 arm64-v8a**，
  模块侧的 libdexkit.so 也只需要这一个 ABI，x86 / x86_64 / armeabi-v7a 纯属死重量
- 开启 R8 收缩 + 资源收缩：DEX 由 2.52 MB 降到 **570 KB**
  （模块自身代码仅 ~15 KB，其余是 Kotlin 标准库 ≈620 KB 与 DexKit ≈172 KB 的 code 字节）
- 保留规则要点（见 app/proguard-rules.pro）：
  - `META-INF/xposed/java_init.list` 是**按类名字符串**引用入口的，入口类必须整类保留
  - DexKit 的原生侧会通过 JNI 按类名 + 方法签名回调 Java 对象，JNI 符号查找不走 R8 映射表，
    因此该包必须整包保留（否则表现为运行期 UnsatisfiedLinkError 或静默返回空结果）
  - libxposed API 由框架运行时提供，需抑制警告并保留重写方法的签名
- 清理：移除 v0.6.1 为定位开屏链路临时加的 4 个只读探针与 `HookManager.installLogger`
  （链路已由 `canShowScreenAd` 定位完成，不再需要）
- 实机验证（OnePlus 9R / Android 14 / LSPosed v2.2.0 / 番茄 7.3.7.32）：
  R8 版本下 DexKit 接口反查（`lf3.a` / `ua3.f`）与全部 hook 正常，
  `blocked fullscreen-ad gate: NsUtilsDependImpl.canShowScreenAd` 照旧命中

## 0.6.2
v0.6.2 - 找到并挂上开屏/全屏广告的「根闸」（canShowScreenAd）
- 结论修正：番茄的开屏与全屏福利广告**既不经过** NsAdImpl 的开屏位置，**也不经过**
  OpeningScreenADActivity（那是红果的 Activity 路径），而是由**专门的全屏广告管理器**决策
- 新增两道闸门：
  - 依赖层公共判定点 `NsUtilsDependImpl#canShowScreenAd(Object)Z` → false
    （接口 `com.dragon.read.NsUtilsDepend` 声明；全库 287353 个类中**仅此一个实现**，
    是干净的公共判定点。实机日志已确认它在启动时被调用并被本模块拦下）
  - 接口 `com.dragon.read.ad.screen.IActivityScreenAdManager`（全屏福利广告管理器）实现类上的
    **零参 boolean 方法** → false。73732 上实现类唯一（`ua3.f`），两个判定方法为
    `a()Z` 与 `onScreenAdDialogShow()Z`
- 为什么按「DexKit 接口反查 + 零参 boolean 方法」而不是写死名字：类名与方法名都是混淆的，
  且 73732 相对更早版本已改过名（更早版本的判定方法叫 `b`/`c`），写死必然在某次更新后失效；
  按接口 + 返回类型挂则与混淆名无关
- 证据来源：同类模块目标表交叉核对 + 本机 73732 DEX 核验（接口实现类唯一、
  boolean 方法签名一一对应），并已实机确认闸门在启动路径上被调用

## 0.6.1
v0.6.1 - 修正番茄开屏广告的链路假设，并补上品牌开屏闸门
- 问题：v0.1.0~v0.6.0 的「开屏阻断」是按红果的 Activity 路径做的
  （NsAdImpl#openOpeningScreenAdActivity → OpeningScreenADActivity）。实机取证发现**番茄根本不走这条路**：
  热启动时启动的是 `com.dragon.read/.pages.splash.SplashActivity`，且全程不调用
  `checkAdAvailable("splash_ad")`，所以「Activity 阻断 + 位置过滤」两层对番茄品牌开屏都是空的
  —— 日志会显示 hook installed，但广告照旧
- 静态取证：`NsAppNavigator` 接口的唯一实现类是 `com.dragon.read.component.j`（不是 NsAdImpl）；
  `NsAdImpl#openOpeningScreenAdActivity` 方法体内为 `new Intent(ctx, OpeningScreenADActivity.class)` +
  `startActivity`，即它只管红果那条 Activity 路径
- 新增 hook：
  - `BrandTopViewDisplayStrategy.c(AbsActivity)Z` → false —— 品牌开屏真正的展示决策
    （反汇编可见其依次校验 Activity 未 finishing、有网络、非基础模式、checkAdAvailable("splash_ad","Brand")）
  - `NsAdImpl.enableSeriesFeedTopViewAd()Z` → false —— 系列 / 单列 TopView 开屏总开关
- 新增 4 个**只打日志、不改变行为**的探针（`SplashActivity#onCreate`、`BrandTopViewDisplayStrategy#a/#b`、
  `component.j#openOpeningScreenAdActivity`），用于在实机日志里定位实际执行的开屏链路；
  由 `SPLASH_PROBE` 开关控制，确认链路后再关闭
- 状态：修复已发出，但**实机效果尚待确认**；探针保留在 v0.6.1 内以便下一轮定位

## 0.6.0
v0.6.0 - 拦截覆盖优化：按常量流证据扩充广告位名单（73532 / 73732 通用）
- 缺口来源：对 `checkAdAvailable(position, source)` 做**反向可达性 + 常量流分析**
  （含 position 作为参数透传的包装方法），提取出真正流入广告闸门的全部 position 常量，
  再逐个反汇编调用点判断「被动广告位」还是「用户主动激励」
  - 结论：73532 与 73732 流入闸门的常量集合完全相同（29 个），属长期覆盖缺口，不是 73732 回归
- BLOCKED_POSITIONS 由 10 项扩至 21 项，新增 11 个已确认为被动广告位的位置：
  - `comment_list_ad`（评论列表原生广告频控）、`series_comment_ad`（短剧评论广告）
  - `story_ad`（故事/短篇插页）、`creator_ad`（创作者广告）
  - `processed_ad`（短视频进度条插入广告，埋点名 pos=progress_ad）
  - `landscape_short_series_ad`、`landscape_short_series_pause_ad`（横屏短剧插入/暂停）
  - `short_series_ad`、`short_series_banner`（短剧信息流与 banner）
  - `audio_info_flow_ad`、`audio_patch_ad`（听书信息流/贴片）
- 新增 PRESERVED_POSITIONS 白名单，显式保留用户主动激励/金币入口：
  `reader_gold_coin_popup`、`video_tts_ad`、`video_voice_ad`、`video_reward_gift_ad`、
  `video_reader_end_urge_update`
- 新增「未分类广告位发现日志」（LOG_UNLISTED_POSITIONS=true，每进程每位置仅记一次，仅打日志不改变返回值）：
  广告位命名由服务端下发、版本间可能静默增加，该日志让每台设备都能为下一轮适配提供真实数据
- 实机验证（OnePlus 9R / Android 14 / LSPosed v2.2.0，番茄 7.3.7.32）：
  `blocked ad position=creator_ad source=AT via lf3.a.checkAdAvailable` —— 新增位置实测生效

## 0.5.0
v0.5.0 - 适配番茄小说 / 红果免费短剧 7.3.7.32（versionCode 73732）
- 修复版本门禁在 Android 14 上完全失效的问题（新增 ApkVersion：直接解析宿主 APK 的
  AndroidManifest.xml 二进制清单读取 versionCode）
  - 实机实测原有两条反射路径全部失败：getPackageArchiveInfo 抛 NullPointerException、
    ActivityThread.currentApplication 返回 null，导致 versionCode=-1 并走 FAIL_OPEN 放行
  - 结果：门禁在最常见的机型上形同虚设；改为自解析后实机读到 versionCode=73732
- 新增支持 73732，同时保留 73532：SUPPORTED_VERSION_CODES 由单值改为版本集合，门禁改为「属于已审计版本集合」
- DEX 级全量复核 26 个 hook 目标（类存在 + 方法签名 + 声明在目标类上）：
  - 番茄 73732：25/26 命中，唯一缺失仍是红果专属 HongguoBannerServiceImpl（预期 WARN 跳过）
  - 红果 73732：26/26 命中（73532 遗留的「同基线待复核」项本次补齐）
- 调用点（invoke-site）比对：25 个 hook 目标在其类型族内的调用点数量 73532 → 73732 完全一致，
  无 hook 变成死开关；新增的 412 个 ad 命名空间类未改变既有闸门链路
- 混淆漂移（均由既有版本容错机制自动吸收，无需硬编码改动）：
  - SeriesPauseAdImpl.canShowPauseAd 参数类型 ti4.h（73532）→ vq4.i（73732），走 findMethodIgnoringParams
  - NsAdConfigManagerApi 实现类 fe3.a（番茄 73532）→ lf3.a（番茄 73732）/ yb3.a（红果 73732），走 DexKit 接口反查
- 位置字符串复核：11 个 BLOCKED_POSITIONS 在 73732 全部仍存在；移除 topview_main / topview_reader
  两个在任何版本字符串池中都不存在的死条目（TopView 实际由 checkCanShowTopView* 结构性拦截）
- 实机复核（OnePlus 9R / Android 14 / LSPosed v2.2.0，2026-09-11）：
  - 番茄 7.3.7.32：versionCode=73732 通过门禁，24 个 hook 安装成功 + 1 个红果专属预期 WARN 跳过
  - 红果 7.3.7.32：versionCode=73732 通过门禁，26 个 hook 全部安装成功
  - DexKit 反查实现类实机结果与静态预测一致（番茄 lf3.a、红果 yb3.a）

## 0.4.0
v0.4.0 - 适配番茄小说 7.3.5.32（versionCode 73532）
- 全量复核 24 个 hook 目标类与方法签名，与 73532 APK 逐一比对全部命中
- 唯一缺失类 HongguoBannerServiceImpl 为红果专属，番茄侧预期 WARN 跳过，不影响功能
- SUPPORTED_VERSION_CODES 同步升至 73532（com.dragon.read 已实测验证；com.phoenix.read 同基线待 APK 复核）

## 0.3.0
v0.3.0 - 红果短剧 + 开屏广告拦截
新增红果短剧广告拦截（banner、贴片、横屏插入）与热启动开屏广告 Activity 级阻断
作用域覆盖 com.dragon.read 与 com.phoenix.read
番茄侧与红果侧 hook 全部安装成功；红果开屏、短剧 banner/贴片广告消失
安装：LSPosed -> 模块 -> 勾选 FanqieHook -> 作用域选 com.dragon.read 与 com.phoenix.read
