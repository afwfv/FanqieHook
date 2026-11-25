# 番茄小说 7.4.5.32 协议与算法笔记

本页记录 Android 版 `com.dragon.read`（`versionCode=74532`）的协议栈、代表性接口和算法验证结果。接口来自该版本客户端的静态分析，实际路由、字段和服务端行为可随版本或配置变化。

## 协议栈

| 调用栈 | 主要业务 | 数据格式 |
| --- | --- | --- |
| ByteDance Retrofit / TTNet / Cronet | 阅读、书城、搜索、书架 | 查询参数、JSON、表单、multipart |
| KMP RPC | 阅读与业务服务 | 按方法声明选择 JSON、Protobuf 或表单 |
| Novel SDK / OkHttp | 目录、章节、阅读进度 | 查询参数、JSON、表单 |
| Web / Lynx | 活动页与动态界面 | HTTP、JSB 消息与动态资源 |
| 财经 SDK | 支付侧密码运算 | 独立的签名、摘要与加密接口 |

静态清单记录了 551 条有调用链证据的记录和 29 条推断记录。一个路径可对应多个调用方或序列化分支，因此这个计数按调用链统计。动态验证覆盖代表性请求。

## 代表性接口

路径中的 `{version}` 泛指客户端的版本段。参数栏仅列已定位的代表字段。

| 业务 | HTTP | 路径 | 参数示例 | 证据 |
| --- | --- | --- | --- | --- |
| 书城标签页 | GET | `/reading/bookapi/bookmall/tab/{version}/` | `tab_type`、`offset` | 静态调用与运行时样本 |
| 书籍详情 | GET | `/reading/bookapi/detail/{version}/` | 由 `BookDetailRequest` 构造 | 静态调用 |
| 搜索结果页 | GET | `/reading/bookapi/search/tab/{version}/` | `count`、`corrected_query` | 静态调用 |
| 章节目录 | GET | `/api/novel/book/directory/list/v1` | `book_id`、`parent_enterfrom`、`novel_sdk_ver` | 静态调用 |
| 章节元数据 | GET | `/api/novel/book/directory/detail/v1` | `item_ids`、`epub_parser_level` | 静态调用 |
| 书架状态 | GET | `/api/novel/book/bookshelf/check/v1/` | `book_id`、`book_type`、`album_id` | 静态调用 |
| 阅读进度 | POST | `/api/novel/book/user/read_history/report/v1/` | `book_id`、`item_id`、`read_progress`（表单） | 静态调用 |

## 算法与验证

| 项目 | 作用 | 已验证结果 | 待验证 |
| --- | --- | --- | --- |
| MetaSec | 业务请求完整性与风控字段 | 同一次调用中，原生结果与 Java 映射的六个字段逐项一致。两次独立的有效调用可产生不同值。 | 独立离线实现 |
| `ttEncrypt` | 请求参数加密 | 一组 7 字节测试输入产生 118 字节输出。Java 与原生返回逐字节一致。静态分析记录了核心函数的 432 个基本块和多级分发结构。 | 独立离线实现与跨样本行为 |
| 章节相关 DH / AES | 内容保护 | Java 调用链显示 DH 派生和 AES-CBC/PKCS7，数据封装含前置 IV 与 Base64。 | 实际章节内容获取与解密的端到端验证 |
| `dragon_crypt` | 版本化的内容加解密接口 | 定位了加解密和密钥版本查询入口。 | 各版本的完整算法映射 |
| 支付 SM2 | 财经 SDK 签名 | 原库生成临时密钥，对同一测试消息产出两个不同的签名。两者均通过原库验签，格式为 64 字节 `r || s`。 | 真实订单链路与独立验签交叉验证 |
| 支付 SM3 / SM4 | 摘要、消息认证与对称加密 | 静态调用点显示 SM3、HMAC-SM3 和 SM4-ECB/PKCS7。 | 各模式的业务样本 |

代表性书城请求的独立 HTTP 样本曾返回 `HTTP 200` 和业务 `code=0`。原应用的流式正文未完整采集，正文逐字节一致性没有验证。2026-11-26 的本地审计通过 13 项检查，覆盖原 APK 哈希、54 份索引证据的哈希、文件格式、脚本语法和已有验证结果的一致性。
