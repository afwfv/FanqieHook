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
| 已验证应用 | 番茄小说 `com.dragon.read` v7.3.7.32（73732）、v7.3.7.18（73718）、v7.3.5.32（73532）；红果免费短剧 `com.phoenix.read` v7.3.7.32（73732）、v7.3.5.32（73532） |
| 其他版本 | **可以直接用**。番茄升级后模块照常工作，个别规则失效时会在日志里列出来，不需要等新版本适配 |
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

有三个地方能看到模块日志，任选其一（不同 LSPosed 版本 / 机型可用的渠道不一样）：

```bash
# 1) logcat（最方便）
adb logcat -s FanqieHook

# 2) LSPosed 自己的模块日志（标准渠道）
adb shell su -c 'grep -a FanqieHook /data/adb/lspd/log/modules_*.log | tail -60'

# 3) 宿主 cache 下的状态文件（LSPosed 不写日志、logcat 也被关闭时的兜底）
adb shell su -c 'cat /data/data/com.dragon.read/cache/fanqiehook.log'
# 红果：/data/data/com.phoenix.read/cache/fanqiehook.log
```

正常情况下能看到 `target ready: ... versionCode=73732`、一串 `hook installed: ...`，
最后一行是 `install summary: hooks installed=N skipped=M`；
实际使用时出现 `blocked ad position=...` 表示广告位被拦截。

排查要点：

- **`install summary` 里 `skipped` 不为 0** → 番茄这次升级动掉了列出的那几条规则，只有那几条
  暂时失效，其余照常工作。把这一行报上来即可精确适配，不需要重新分析整个模块。
- **`unverified host version: versionCode=...`** → 你装的番茄比模块记录的适配版本新。属于正常提示，
  模块会继续逐条安装，实际效果看紧跟其后的 `install summary`。
- **只有 `target ready` 没有任何 `hook installed`** → 宿主结构变化过大，把日志报上来。
- `unlisted ad position=...` → 遇到了模块尚未分类的广告位（仅记录，不影响使用），报上来即可补进名单。

## 免责声明

仅供学习研究，绕过广告可能违反番茄小说 / 红果《用户协议》。
安装使用前请自行评估风险，尤其是账号风险。
