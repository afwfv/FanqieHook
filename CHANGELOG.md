# 更新日志

> 发布时，按 module.prop 中的版本号自动读取对应「## x.x.x」段作为模块中心的更新说明；
> 找不到对应版本段时，回退使用「未发布」段。
> 每次在 debug 分支改动代码后，把中文说明补到当前版本段（发版前记得递增 versionCode / version）。

## 未发布

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
