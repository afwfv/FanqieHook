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
| 已验证应用 | 番茄小说 `com.dragon.read` v7.3.5.32（versionCode 73532）；红果免费短剧 `com.phoenix.read` 同基线（versionCode 73532）—— 双端实机（LSPosed v2.1.1 / Android 14）验证 26 个 hook 全部安装成功 |
| Android | 8.0（API 26）及以上 |
| 作用域 | `com.dragon.read`、`com.phoenix.read` |
| 模块包名 | `dev.operit.fanqiehook` |
| 模块版本 | v0.4.0（versionCode 15）|

> 模块针对 v7.3.5.32 的运行时结构适配（旧适配目标 v7.3.3.32 已由 73532 版本复核替换）。应用升级后，广告类名、方法名或调用链可能变化，届时需要重新适配。

## 安装与使用

1. 安装 LSPosed 框架。
2. 安装 `FanqieHook-v0.4.0-release.apk`。
3. 在 LSPosed 中启用模块，勾选作用域 `com.dragon.read` 与 `com.phoenix.read`。
4. 强制停止番茄小说 / 红果免费短剧后重新打开。

验证：

```bash
adb logcat -s LSPosedLogDaemon:V | grep FanqieHook
# 应看到 "hook installed:" 记录，实际使用时出现 "blocked ad position=..."
```

## 已验证

- 番茄侧与红果侧 hook 全部安装成功（含 DexKit 反查实现类：番茄 `fe3.a`、红果 `sa3.a`）
- 实测拦截：阅读器 banner、视频广告、翻页信息流广告、听书贴片广告、红果开屏与短剧 banner 广告
- v0.4.0 修正 `ExperimentUtil.p0()` → `p()`（p0 在 73532 中不存在），`q0()` 确认为横屏插入广告开关

## 免责声明

仅供学习研究，绕过广告可能违反番茄小说 / 红果《用户协议》。
