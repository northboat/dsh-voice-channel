# Deepy

让 ESP32 设备经局域网接入 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（DSH）Agent 的技术方案与实施清单。一个 client 无关的 DSH 插件提供语音输入、语音输出与回复情绪；PC、Android、ESP32 三种客户端按需接入，形象资源由 client 本地映射。Android 端形象只有一种：内置的 Deepy。

本仓库包含方案文档与三端实现目录；上游仓库检出仅作本地参考，已 ignore、不随本仓库提交。

## 状态

阶段 1 插件已可用（`plugin/`：**0.11.0 起主通道是一条 WS 长连接**（`ws://<host>:8787/xiaozhi/v1/`，完全照抄小智协议），`/xiaozhi/ota/` 可让未改固件的 ESP32 直连；原 `/api/chat`、`/api/asr`、`/api/tts` 保留给演示页与脚本；支持多角色与**按设备绑定角色**），阶段 2 Android client 已落地（`android/`：**通话模式常驻监听**、**朗读不被打断**、**音频边收边播**、字幕按播放头逐字出、**唯一内置形象 Deepy**（素材随 APK，删了自动补回）＋ 可选外置素材包），阶段 3 ESP32 固件未开始（协议已就绪，可只写板级与播放）。**当前唯一阻塞：在 Desktop 重载插件后复测真机通话闭环**（WS 路由与 TTS 修复都在模块级代码里，不是请求级热加载）。进度、踩坑与已知取舍见 `HANDOFF.md`。

- [技术方案](docs/technical-proposal.md)：选型对比、架构、部署、验证、许可边界
- [插件服务端](docs/plugin-server.md)：接口、状态文件、音色管理、worker 协议、排障
- [WS 协议（已实现）](docs/ws-protocol.md)：v1 连接 / 鉴权 / hello / 会话消息 / 二进制帧 / OTA / 验收清单
- [客户端](docs/app-client.md)：产品原则、页面结构、协议消费、构建与排障
- [实施清单](TODO.md)：插件 → Android client → ESP32 固件（三阶段），含情绪契约、T2/T3 与待决项
- [Android 开发记录](android/README.md)：ASR 选型对比、构建 / adb 命令、局域网与协议实现细节
- [本地开发指南](docs/dev-guide.md)：怎么构建装包、改形象、调插件、处理素材（ESP32 一节留白）
- `archive/`：被推翻的旧方案与历史决策记录

## 目录

| 路径 | 内容 |
| --- | --- |
| `docs/technical-proposal.md` | 技术方案（原 README 迁入） |
| `docs/dev-guide.md` | 本地开发指南：Android / 插件 / ESP32（留白）/ 素材处理 |
| `TODO.md` | 实施顺序与验收项 |
| `plugin/` | 插件实现：client 无关服务端，DSH npm/Cordis 包 |
| `android/` | Android client 实现（独立仓库）；内置形象素材在 `android/app/src/main/assets/packs/deepy/` |
| `esp32/` | ESP32 client 实现（Waveshare ESP32-S3-Touch-AMOLED-1.75C） |
| `archive/` | 历史归档 |
| `dsh-Go/`、`dsh-mobile-apk/`、`xiaozhi-esp32/`、`ESP32-S3-Touch-AMOLED-1.75C/` | 上游检出，仅本地参考；已 ignore 且剥离各自 `.git`，不随本仓库提交 |

## 参考仓库

| 仓库 | 用途 | 许可 |
| --- | --- | --- |
| [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) | DSH 引擎本体 | 见上游 LICENSE |
| [ccch1mneyyy/dsh-go](https://github.com/ccch1mneyyy/dsh-go) | Android 宿主、插件机制、端侧 ASR | AGPL-3.0（`sdk/`、`examples/` MIT） |
| [kelai141/dsh-mobile-apk](https://github.com/kelai141/dsh-mobile-apk) | 冻结快照宿主，方案 A 对照 | MIT |
| [kelai141/dsh-mobile](https://github.com/kelai141/dsh-mobile) | 上述仓库的构建链 | 见上游 |
| [78/xiaozhi-esp32](https://github.com/78/xiaozhi-esp32) | WebSocket / Opus / MCP / `llm.emotion` 协议参考 | MIT |
| [waveshareteam/ESP32-S3-Touch-AMOLED-1.75C](https://github.com/waveshareteam/ESP32-S3-Touch-AMOLED-1.75C) | 目标板 BSP、LVGL 与音频示例 | Apache-2.0 |
| [xinnan-tech/xiaozhi-esp32-server](https://github.com/xinnan-tech/xiaozhi-esp32-server) | 方案 B 的服务端参考 | 见上游 |
| [liguobao/ds-harness-remote](https://github.com/liguobao/ds-harness-remote) | DSH Remote 远程主机 | MIT |
| [TOM88812/xiaozhi-android-client](https://github.com/TOM88812/xiaozhi-android-client) | 小智协议 Android 客户端参考 | 见上游 |
| [ccch1mneyyy/dsh-TUI](https://github.com/ccch1mneyyy/dsh-TUI) | 形象与品牌素材来源（本仓库未使用其素材） | 见上游 |
| [rullerzhou-afk/clawd-on-desk](https://github.com/rullerzhou-afk/clawd-on-desk) | 桌宠包格式来源（间接参考） | 见上游 |

小智生态的其它服务端/客户端（Python / Java / Go / Linux / SF32 / QuecPython）见 [78/xiaozhi-esp32](https://github.com/78/xiaozhi-esp32) 的「相关开源项目」章节。

## 开源规范

- 本地检出的上游仓库保持原样，保留其 LICENSE、版权与第三方声明；本仓库不提交、不修改上游代码，未复制品牌素材用于产品。
- AGPL-3.0：修改或分发 `dsh-Go` 衍生作品须同许可开源、提供对应源码并标注主仓库与原作者；仅安装官方 APK 自用不触发再发布义务。
- MIT / Apache-2.0：保留版权、许可与 NOTICE 声明。
- 语音识别在 PC 侧运行：Python `vosk`（Apache-2.0）与 `vosk-model-small-cn-0.22`（Apache-2.0）由使用者本地拉取（`plugin/.venv`、`plugin/models` 已 gitignore），**不随 APK 分发**；APK 不含任何语音模型，也不包含、不引导安装 `com.google.android.tts`。
- 品牌与美术素材（DSH Go 名称与标识、Deepy、鲸娘、DeepSeek 图标）不在 AGPL-3.0 授权范围内，仅限构建、测试与本仓库展示，不得用于自行发布的产品；`android/app/src/main/assets/packs/deepy/` 的 4 个动画 WebP 即此类参考素材，随**私有**仓库提交便于直接装包，不对外分发；不冒充官方版本，相关名称与标识归各自权利人。
- 文中以 `dsh-Go/`、`xiaozhi-esp32/` 等开头的路径引用均指本地上游检出（已 ignore，不随本仓库分发），对应内容见上表仓库链接。
- 本仓库新增文档与实现代码尚未选定许可证，分发前需明确；`android/` 为独立仓库，许可随其自身。
