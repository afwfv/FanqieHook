# FanqieHook

番茄小说去广告模块 — 14 个 hook，拦截广告展示。

- **目标应用**：`com.dragon.read` v7.3.3.32（versionCode 73332）
- **框架**：LSPosed IT v2.1.1+（KernelSU / ZygiskSU）
- **作者**：afwfv

## 安装使用

```text
1. 安装 LSPosed（KernelSU / ZygiskSU + LSPosed IT）
2. 装 APK：adb install -r FanqieHook-v0.1.0-release.apk
3. 打开 LSPosed Manager → 模块 → 勾选 FanqieHook → 作用域选 com.dragon.read
4. 强制停止番茄，重新打开
```

验证：

```bash
adb logcat -s LSPosedLogDaemon:V | grep FanqieHook
# 应看到 14 行 "hook installed:" + 实际使用时 "blocked ad position=..."
```

## 拦截的广告位

| 广告位 | 出现位置 |
|---|---|
| `splash_ad` | 启动开屏 |
| `page_front_ad` | 阅读页头部 |
| `page_middle_ad` | 阅读页中部 |
| `page_end_ad` | 阅读页末尾 |
| `reader_banner` | 阅读器底部 banner |
| `reader_text_link_ad` | 阅读器文字链接广告 |
| `reader_disconnected_ad` | 章节断开处广告 |
| `reader_ad_for_sati` | 阅读器激励位 |
| `video_reader_ad` | 阅读器视频广告 |
| `topview_main` | 首页 TopView |
| `topview_reader` | 阅读器 TopView |
| `series_pause_ad` | 短剧暂停广告 |

**用户主动点击的激励视频 / 金币 / 看广告免广告按钮不会被屏蔽。**

## 已验证

- POCO dada / HyperOS 2 / Android 16 + LSPosed IT v2.1.1 (7842)
- 14/14 hook 安装成功
- 实测拦截 `reader_banner` / `video_reader_ad`

## 更新版本

模块硬绑定 versionCode 73332。番茄更新后需重新逆向：

```text
1. 拉新 APK → jadx + apktool 重新分析
2. 在 FanqieModule.kt 更新 TARGET_VERSION_CODE
3. 重新核对 14 个 hook 目标的类名/方法签名
4. 重新 build + 发版
```

## 免责声明

仅供学习研究，绕过广告可能违反番茄《用户协议》。