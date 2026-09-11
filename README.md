# FanqieHook

面向番茄小说与红果免费短剧 Android 客户端的 LSPosed 去广告模块，26 个 hook 拦截广告展示。

## 功能

- 拦截阅读器广告：底部 banner、视频广告、翻页信息流、文字链接、章节断开处广告
- 拦截首页与阅读器 TopView 广告
- 拦截听书信息流 / 贴片广告
- 拦截短剧暂停广告
- 拦截红果短剧 banner、贴片、横屏插入广告
- 拦截红果热启动开屏广告（Activity 级阻断）
- 保留用户主动点击的激励视频 / 金币 / 看广告免广告按钮

## 兼容范围

| 项目 | 内容 |
|---|---|
| 当前支持 | LSPosed Modern API（101+，102 已适配）|
| 已验证应用 | **番茄小说 `com.dragon.read` v7.3.7.32（versionCode 73732）**；**红果免费短剧 `com.phoenix.read` v7.3.7.32（73732）**；同时保留 v7.3.5.32（73532）双端支持 |
| 静态复核结果 | 73732 番茄 25/26 命中（缺 `HongguoBannerServiceImpl` 为红果专属）；73732 红果 26/26；全部 hook 调用点数量与 73532 一致 |
| 实机复核 | OnePlus 9R / Android 14 / LSPosed v2.2.0：番茄 7.3.7.32 与红果 7.3.7.32 均通过版本门禁（versionCode=73732），hook 安装成功 |
| Android | 8.0（API 26）及以上 |
| 作用域 | `com.dragon.read`、`com.phoenix.read` |
| 模块包名 | `dev.operit.fanqiehook` |
| 模块版本 | v0.5.0（versionCode 16）|

> 模块针对 v7.3.7.32 / v7.3.5.32 的运行时结构适配。两个版本共用同一套 hook 实现——73732 未移动任何 hook 目标，
> 仅混淆参数类型（`ti4.h` → `vq4.i`）与 DexKit 反查的实现类名（`fe3.a` → `lf3.a` / `yb3.a`）发生变化，
> 两者都由既有的版本容错机制自动吸收。应用再次升级后，广告类名、方法名或调用链可能变化，届时需要重新适配。

## 安装与使用

1. 安装 LSPosed 框架。
2. 安装 `FanqieHook-v0.5.0-release.apk`。
3. 在 LSPosed 中启用模块，勾选作用域 `com.dragon.read` 与 `com.phoenix.read`。
4. 强制停止番茄小说 / 红果免费短剧后重新打开。

验证：

```bash
adb logcat -s LSPosedLogDaemon:V | grep FanqieHook
# 应看到 "hook installed:" 记录，实际使用时出现 "blocked ad position=..."
```

## 已验证

- 番茄侧与红果侧 hook 全部安装成功（含 DexKit 反查实现类：番茄 `fe3.a`/`lf3.a`、红果 `yb3.a`）
- 实测拦截：阅读器 banner、视频广告、翻页信息流广告、听书贴片广告、红果开屏与短剧 banner 广告
- v0.4.0 修正 `ExperimentUtil.p0()` → `p()`（p0 在 73532 中不存在），`q0()` 确认为横屏插入广告开关
- v0.5.0 新增 73732：DEX 级复核 26 个目标类/方法签名、逐 hook 的调用点数量比对、位置字符串存在性复核
- v0.5.0 修复版本门禁：Android 14 上两条反射路径全部失败会让门禁静默放行（FAIL_OPEN），
  现改为自行解析宿主 APK 的二进制 AndroidManifest.xml（`ApkVersion.kt`），实机读到 versionCode=73732

## 适配工具链

`apk-reverse` 之外，本仓库的版本适配使用一套 DEX 级核验脚本（位于分析工作区 `FANQIE/ADAPT_73532/`）：

| 脚本 | 作用 |
|---|---|
| `dexindex.py` | 极简 DEX 索引器（类 → 方法名 → 原型/access flags，含接口与父类） |
| `verify_targets.py` | 对某个 APK 逐个核验 26 个 hook 目标是否存在且签名一致 |
| `xref.py` | 逐 hook 统计其类型族内的 `invoke-*` 调用点数量，并 diff 两版本的 ad 命名空间类 |
| `check_positions.py` | 核验 `BLOCKED_POSITIONS` 的每个位置字符串在两个版本的字符串池中仍存在 |

适配新版本的流程：`verify_targets.py` 全绿 → `xref.py` 确认没有 hook 变成死开关 → `check_positions.py` 无缺失
→ 更新 `SUPPORTED_VERSION_CODES` 与版本号 → 实机复核 logcat。

## 免责声明

仅供学习研究，绕过广告可能违反番茄小说 / 红果《用户协议》。
