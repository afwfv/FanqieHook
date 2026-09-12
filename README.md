# FanqieHook

面向番茄小说与红果免费短剧 Android 客户端的 LSPosed 去广告模块。

## 功能

- 拦截阅读器广告：底部 banner、视频广告、翻页信息流、文字链接、章节断开处广告
- 拦截番茄开屏 / 全屏广告
- 拦截首页与阅读器 TopView 广告
- 拦截听书信息流 / 贴片广告
- 拦截短剧暂停、短剧 banner、横屏插入广告
- 拦截评论列表、故事插页、创作者广告、短视频进度条插入广告
- 保留用户主动点击的激励视频 / 金币 / 看广告免广告入口

> 激励视频「秒领」不在支持范围内：番茄的发奖由服务端校验，客户端侧做不到。

## 兼容范围

| 项目 | 内容 |
|---|---|
| 已验证应用 | 番茄小说 `com.dragon.read` v7.3.7.32（73732）；红果免费短剧 `com.phoenix.read` v7.3.7.32（73732）。同时保留 v7.3.5.32（73532） |
| Android | 8.0（API 26）及以上 |
| LSPosed | Modern API 101+（102 已适配） |
| 作用域 | `com.dragon.read`、`com.phoenix.read` |
| 体积 | 0.47 MB |
| 模块包名 | `dev.operit.fanqiehook` |

## 安装与使用

1. 安装 LSPosed 框架
2. 安装 Release 里的 `app-release.apk`（或直接在 LSPosed 模块中心安装/更新）
3. 在 LSPosed 中启用模块，勾选作用域 `com.dragon.read` 与 `com.phoenix.read`
4. 强制停止番茄小说 / 红果免费短剧后重新打开

## 排查

模块日志写在 LSPosed 自己的日志文件里，不在 logcat：

```bash
adb shell su -c 'grep -a FanqieHook /data/adb/lspd/log/modules_*.log | tail -60'
```

正常情况下能看到 `target ready: ... versionCode=73732` 与 `hook installed: ...`；
实际使用时出现 `blocked ad position=...` 表示广告位被拦截。

如果你在用测试版，还可能看到 `unlisted ad position=...` —— 那表示遇到了模块尚未分类的
广告位（仅记录日志，不影响使用），报上来即可补进拦截名单。

## 免责声明

仅供学习研究，绕过广告可能违反番茄小说 / 红果《用户协议》。
安装使用前请自行评估风险，尤其是账号风险。
