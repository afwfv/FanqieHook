# Changelog

## [v0.2.0] - 2026-08-23 — 听书广告拦截 + DexKit 深度防线

- 新增听书广告拦截：hook `NsAdImpl.enableRequestAudioInfoFlowAd()` / `enableRequestAudioPatchAd()`（v73332 smali 5248-5302），xref 验证 `BsAudioAdService` 仅由 `NsAdImpl` 调用，无绕过路径
- 修复 DexKit native 库在 LSPosed 注入进程中不加载的问题：DexKit 2.0.4 自身从不调用 `System.loadLibrary`，现在 `ClassResolver` 会先显式加载 `libdexkit.so`（loadLibrary → 模块 APK 提取双策略）
- 恢复 `h83.a#checkAdAvailable` 深度防线 hook（此前因 DexKit 加载失败静默缺失）
- 引入 DexKit 2.x 用于跨版本混淆类反查（`NsAdConfigManagerApi` 实现）
- 实测 17/17 hook 全部生效；听书贴片、翻页信息流广告均已消失（POCO dada / HyperOS 2 / Android 16 + LSPosed IT v2.1.1）
- 作者：afwfv

## [v0.1.0] - 2026-08-22 — 首个正式版

- 14 个 hook 全部验证通过（POCO dada / HyperOS 2 / Android 16 + LSPosed IT v2.1.1）
- 实际拦截 `reader_banner` / `video_reader_ad`
- 作者：afwfv