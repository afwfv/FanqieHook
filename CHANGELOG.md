# 更新日志

> 发布时，按 module.prop 中的版本号自动读取对应「## x.x.x」段作为模块中心的更新说明；
> 找不到对应版本段时，回退使用「未发布」段。
> 每次在 debug 分支改动代码后，把中文说明补到当前版本段（发版前记得递增 versionCode / version）。

## 未发布

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
