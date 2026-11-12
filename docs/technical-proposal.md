**文档修订记录：**

| **版本号** | **修订日期** | **修订说明** | **修订人** | **审核人** | **批准人** |
| ---------- | ------------ | ------------ | ---------- | ---------- | ---------- |
| 1.0        | 2026/11/05   | 初版：选型、架构、部署与验证方案 |            |            |            |
| 1.1        | 2026/11/05   | 同步三阶段实施顺序、情绪契约、Waveshare 固件基线；迁至 `docs/` |            |            |            |
| 1.2        | 2026/11/12   | WS 协议按本文落地（T2/T3 合并），实际实现见 `docs/ws-protocol.md` |            |            |            |

# ESP32 与 DSH 实时通信技术方案

状态：**传输层已实现**（2026-11-12，插件 + Android 0.11.0 走 `xiaozhi-esp32` 的 WS 协议，见 `docs/ws-protocol.md`）；ESP32 固件未开始。项目介绍见根 `README.md`，实施顺序与情绪契约以 `TODO.md` 为准。实现目录为 `plugin/`（插件）、`android/`（Android client）、`esp32/`（ESP32 client）。事实依据为 2026-11-05 已检出的 `dsh-Go/`、`xiaozhi-esp32/`、`dsh-mobile-apk/`、`ESP32-S3-Touch-AMOLED-1.75C/` 源码；这些上游检出已在 `.gitignore` 中忽略、不随本仓库分发，文中相关路径引用均指本地检出，推论与未验证项已显式标注。

## 一、总述

### 1.1 背景

目标是把 ESP32 的语音与设备交互能力接入 DSH Agent，使设备成为 DSH 的音频与 IoT 端点。先后评估三条路径：自研 Android 宿主、PC 常驻 DSH + HTTP 远程、复用 `dsh-Go` + 自写插件。前两条分别卡在插件生态与便携性，最终选定第三条。

### 1.2 现状和需求分析

**现状**

| 对象 | 已核实事实 |
| --- | --- |
| `dsh-Go/` | 源码完整；有官方 release APK（`README.md:47`）。Flutter + Kotlin 壳，PRoot 内 Ubuntu/Node 跑 `dsh web`；插件为 DSH npm/Cordis 包，本地目录可直接安装（`docs/plugins/hosts.md:13-29`） |
| `dsh-mobile-apk/` | 另一套 Termux 冻结快照宿主。第三方插件受构建期依赖闭包、peer 版本与平台链限制，运行时装插件可致 boot 硬崩（`scripts/check-mcp-client-deps.mjs:4-19`），不作为基础 |
| `xiaozhi-esp32/` | 设备端协议参考：OTA 配置下发、WebSocket 二进制 Opus、设备侧 MCP Server、`llm.emotion` 均有源码与协议文档 |
| `ESP32-S3-Touch-AMOLED-1.75C/` | 目标硬件。出厂 `firmware/*FactoryOnly*.bin` 为合并镜像、无源码；可修改的是 `examples/` 下的 ESP-IDF / Arduino 示例源码与板级 BSP |
| 缺口 | 全仓无 `dsh-voice-channel` 插件、无 ESP32↔DSH 桥接代码；`dsh-Go` 无设备配对 PIN、无 LAN 监听、无内置 TTS |

**需求**

| 编号 | 约束 |
| --- | --- |
| C1 | 保留 DSH 本体插件生态，不自造插件协议、不模拟 shell |
| C2 | 便携，不依赖笔记本常开、公网 IP 或内网穿透 |
| C3 | 长连接双向流式，非请求-应答轮询 |
| C4 | 能力迭代落在一个插件包内，不重编译 APK |
| C5 | 设备经 LAN + PIN 绑定后方可建立会话 |
| C6 | 插件 client 形态无关：PC `dsh web`、手机 APK、ESP32 接同一套协议，换 client 不改插件代码 |
| C7 | server 只返回语义情绪，client 只做「情绪 → 本地资源」映射；形象资源由外部提供 |

**代码基线**

| 方案 | GitHub 仓库 | 本地路径 | 许可 |
| --- | --- | --- | --- |
| 共用 | [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)（DSH 引擎，`dsh web`） | 由宿主安装 | 见上游 |
| A | 自研壳：本仓库历史，无独立上游；参考宿主 [kelai141/dsh-mobile-apk](https://github.com/kelai141/dsh-mobile-apk) | `archive/android/`、`dsh-mobile-apk/` | MIT |
| B | PC 部署 `dsh web`；小智协议服务端参考 [xinnan-tech/xiaozhi-esp32-server](https://github.com/xinnan-tech/xiaozhi-esp32-server) | 外部 | 见各仓库 |
| C | [ccch1mneyyy/dsh-go](https://github.com/ccch1mneyyy/dsh-go) + [78/xiaozhi-esp32](https://github.com/78/xiaozhi-esp32)（协议参考） | `dsh-Go/`、`xiaozhi-esp32/` | AGPL-3.0（`sdk/`、`examples/` MIT） / MIT |
| C 硬件 | [waveshareteam/ESP32-S3-Touch-AMOLED-1.75C](https://github.com/waveshareteam/ESP32-S3-Touch-AMOLED-1.75C) | `ESP32-S3-Touch-AMOLED-1.75C/` | Apache-2.0 |

`dsh-Go` 的远程主机依赖 [liguobao/ds-harness-remote](https://github.com/liguobao/ds-harness-remote)；小智协议的其它服务端/客户端实现见 `xiaozhi-esp32/README_zh.md:139-152`。完整仓库清单与许可说明见根 `README.md`。

**选型对比**

| 维度 | A `dsh-mobile-apk` / 自研壳 | B `deepseek-harness` on PC | **C `dsh-go` + Waveshare 板** |
| --- | --- | --- | --- |
| 插件生态 | 断：自造 `ctx.shell`；参考实现冻结快照，第三方插件大面积不可用 | 完整 | 完整：rootfs 内可自由 `npm install` |
| 魔改成本 | 极高：重建快照/APK、门禁与三层验收 | 低 | 低：插件包级迭代，无需重出 APK |
| 便携性 | 好 | 差：笔记本常开、供电、随身 | 好：手机可作热点 |
| 网络 | LAN | CGNAT → DDNS / 穿透 / TLS / 暴露面 | 纯 LAN，零公网依赖 |
| 实时性 | 中 | 中-差：公网 RTT 抖动影响 60ms 音频帧 | 好：LAN 内短链路 |
| 语音算力 | 手机偏弱 | 强 | 手机偏弱；端侧 ASR 可用、TTS 需自接 |
| 交付物 | 壳 + 引擎 + 插件 + 固件 | 1 插件 + 1 固件应用 | 1 插件 + 2 类 client（Android、ESP32） |
| 主要问题 | 生态不可用且改不动 | 违背 C2/C3 | 插件自开 LAN 端口无真机实证；固件从零实现 |

**加权矩阵**（1–5 分，越高越好；权重与分数为工程判断）

| 判据 | 权重 | A | B | C |
| --- | --- | --- | --- | --- |
| 插件生态 | 25% | 1 | 5 | 5 |
| 便携性 | 20% | 5 | 1 | 5 |
| 魔改成本 | 20% | 1 | 5 | 4 |
| 网络简单度 | 15% | 4 | 1 | 4 |
| 实时性 | 10% | 3 | 2 | 4 |
| 实现成本 | 10% | 2 | 5 | 3 |
| **加权总分** | 100% | **2.55** | **3.30** | **4.35** |

结论：选 C。宿主用 `dsh-go` 官方 APK，插件为唯一服务端，client 依次落地 PC / Android APK / ESP32。B 保留为联调环境与降级路径。A 的失败依据见 `archive/README.md:9` 与 `dsh-mobile-apk/scripts/check-mcp-client-deps.mjs:4-19`。

### 1.3 总体目标

复用 `dsh-Go` 官方 APK 作为宿主，不修改其 Android 代码；新增一个 client 无关的 DSH 插件，提供语音输入、语音输出与回复情绪。按 `TODO.md` 三阶段实施：插件 → Android client → ESP32 固件（Waveshare 板从零实现）。

验收指标：PC 与手机 APK 两类 client 在不改插件的前提下跑通全链路；设备工具调用可用；端到端首字 P95 < 2.5 s（含 ASR/TTS，按 provider 调整）；锁屏/后台连续 30 min 不掉线；未绑定设备无法建立 WS 会话；替换形象资源包与映射表即可换形象，server 零改动。

### 1.4 术语/缩略语

| 术语 | 含义 |
| --- | --- |
| DSH / `dsh web` | DeepSeek Harness 引擎及其 Web/HTTP 服务 |
| Cordis | DSH 插件装配内核；插件以 npm 包 + `cordis.patch.yml` 注册 |
| PRoot | `dsh-Go` 在 Android 上运行 Ubuntu rootfs 的用户态方案 |
| OTA / NVS | `xiaozhi-esp32` 的配置下发端点 / 设备端持久化配置（协议参考） |
| MCP | JSON-RPC 2.0 工具协议；可选扩展，不进入三阶段首版验收 |
| ASR / TTS | 语音转文字 / 文字转语音 |
| emotion | server 在回复事件下发的语义情绪枚举，client 映射为本地 gif |
| launch token | `dsh web` 每次启动生成的回环访问凭据 |

### 1.5 参考资料

| 来源 | 用途 |
| --- | --- |
| `TODO.md` | 三阶段实施顺序、情绪契约、资源与待决项（实施口径以此为准） |
| `README.md` | 项目介绍、全部参考仓库与开源合规 |
| `dsh-Go/README.md:47,174,337-348` | 现成 APK、模型 API 由外部提供、AGPL 与署名边界 |
| `dsh-Go/ASSETS.md:3,19` | 品牌与美术素材不在 AGPL-3.0 范围内 |
| `dsh-Go/docs/plugins/hosts.md:13-39` | 本地插件安装路径与生效方式 |
| `dsh-Go/docs/architecture.md:82-86`、`app/.../runtime/DirectLaunch.kt:200-202` | 引擎回环绑定与 token 链路 |
| `dsh-Go/docs/design/voice-input.md:7,14-23,34`、`docs/plugins/voice.md:9,70-71` | 端侧 ASR、无内置 TTS |
| `dsh-Go/runtime/direct/modules.mjs:110-125` | 用户安装原生 `.node` 的限制 |
| `xiaozhi-esp32/docs/websocket_zh.md:242`、`main/application.cc:695-701` | `llm.emotion` 与 `SetEmotion()`，情绪契约的依据 |
| `xiaozhi-esp32/docs/websocket_zh.md`、`docs/mcp-protocol_zh.md`、`main/ota.cc:137-157` | 传输、工具协议与 activation 码 |
| `ESP32-S3-Touch-AMOLED-1.75C/README_ZH.md:22-37,51-83`、`firmware/README_ZH.md` | 硬件参数、示例清单、出厂固件无源码 |
| `dsh-Go/app/assets/pets/*/pack.json` | 「动画 key → 帧序列」映射表结构参考 |
| `archive/ISSUES.proot.md`、`archive/README.md` | 历史决策与已推翻方案 |

## 二、架构设计

### 2.1 总体思路

复用 `dsh-Go` 官方 APK 与本机引擎，全部新增逻辑收敛到一个 host 侧 DSH 插件。插件 client 形态无关：PC `dsh web`、自写 Android APK、ESP32 接同一套协议。ESP32 侧在 Waveshare 板上从零实现**应用与编排**（出厂固件无源码），驱动与 BSP 复用该仓库 `examples/` 及托管组件；`xiaozhi-esp32` 仅作协议参考。

三条硬边界（源码实证）：

1. `dsh web` 硬绑 `127.0.0.1` + 动态端口，且路由有 Host/Origin 与 cookie/凭据守卫；**设备无法直连引擎**，改 `--host 0.0.0.0` 会被判非法参数。
2. App 不执行插件代码、不连外部 WS；插件与引擎同进程、无沙箱（`dsh-Go/docs/plugins/files.md:72`），须自己 `listen('0.0.0.0', port)` 提供 OTA/WS。`dshGo` 原生面板仅用于 UI，32 KiB / 4 Hz / 3 s 回调限制，不承载音频。
3. 前台服务 + `WAKE_LOCK` 已有（`DshService.kt:22,87-95`），但官方 FAQ 承认厂商策略仍可能结束进程；插件自开端口与长连接需自管重连。

### 2.2 整体架构设计

```text
Client（三选一，协议相同）
  ├─ PC dsh web / 网页客户端（阶段 1 联调）
  ├─ Android client APK（阶段 2）
  └─ ESP32-S3-Touch-AMOLED-1.75C（阶段 3，从零实现应用）
  │ ① WS 长连接：JSON 控制 + 二进制 Opus（上行 16k / 下行 24k，60ms）
  │ ② 情绪事件：{"type":"llm","emotion":"happy"}
  │ ③ PIN/token 绑定；MCP tools/call 为可选扩展
  ▼  手机热点或同一 WiFi，纯 LAN
Android（dsh-Go：PRoot + Ubuntu + Node + `dsh web`，回环 + token）
  ├─ DSH 本体：会话、工作区、模型、插件生态
  └─ dsh-voice-channel（Cordis/npm 插件，apply(ctx)，引擎进程内，唯一服务端）
       ├─ client 无关 WS 服务：自监听 0.0.0.0:<port>，Bearer + Device-Id 白名单
       ├─ 语音桥：Opus ⇄ PCM ⇄ ASR/TTS
       ├─ 会话桥：session/prompt ⇄ session/event
       ├─ 情绪产出：回复事件下发 emotion 枚举（不下发任何资源）
       └─ 可选：MCP Client ⇄ ctx.tools.register
  ▼
模型 API（自行配置）、可选云端 TTS
```

局域网可达性说明：App 具备 `INTERNET` 权限且 PRoot 共享网络栈，Node 原生 socket 不受仅针对 loopback 的 `network_security_config.xml` 约束（Android HTTP/WebView 栈策略）。该结论为**推断**：仓库内无 `listen()` 示例、无端口配置 UI、无真机记录，列为首个验证项（G1）。

### 2.3 业务架构设计

**会话与绑定时序**

1. 设备/客户端发起连接，未在白名单时进入 PIN 绑定：server 返回 `activation.code` + `challenge`，client 显示 6 位码并轮询确认；限次与过期由插件实现（`dsh-Go` 无此能力）。协议字段沿用 `xiaozhi-esp32/main/ota.cc:137-157` 的语义。
2. 用户确认后绑定 `Device-Id`、签发 token；下发 WS 端点与 `audio_params`，未确认不下发，避免绕过 PIN。
3. WS 握手校验 `Authorization: Bearer`、`Protocol-Version`、`Device-Id`，交换 `hello`（`transport:"websocket"`，16 kHz / Opus / 60 ms）。
4. 音频上行 → VAD 分段 + Opus→16 kHz WAV → `speech/transcribe` → `session/prompt`；`session/event` 增量 → TTS → 24 kHz Opus 下行；`abort` 映射 `session/cancel`。
5. server 在同一回复流中下发 `emotion`；client 查本地映射表切换 gif。
6. 可选扩展：`tools/list` / `tools/call`（`type:"mcp"`）→ `ctx.tools.register`，不进入三阶段首版验收。

**情绪契约（解耦边界）**

```json
{"session_id": "xxx", "type": "llm", "emotion": "happy"}
```

| 项 | 约定 |
| --- | --- |
| 字段 | 回复事件中的 `emotion`，字符串枚举；可选 `intensity`（0–1），首版不启用 |
| 枚举 | 首版 `neutral / happy / sad / angry / surprised / thinking / sleepy / error`，随协议版本扩展；未知值 client 回退 `neutral` |
| 禁止 | server 不下发资源 ID、URL、文件名、帧数据，不感知 client 的形象、尺寸、语言、明暗 |
| 映射 | client 维护 `emotion → 本地动画 key` 表（资源由外部提供）；换皮肤只替换映射表与资源包 |
| 状态 | `idle / listening / thinking / speaking` 由 client 从链路事件本地推导，与 `emotion` 正交；叠加优先级由 client 定 |
| 依据 | `xiaozhi-esp32/docs/websocket_zh.md:242`、`main/application.cc:695-701`（`SetEmotion()`） |

**协议映射**

| client 侧 | 插件侧 | DSH 侧 |
| --- | --- | --- |
| 连接/绑定请求 | PIN 校验、签发 token、下发 WS 端点 | — |
| `hello` / `listen` / `abort` | 握手与状态机、10 s hello 超时 | `session/cancel` |
| 二进制 Opus 帧（首版 v1 裸帧） | 解码、VAD、ASR | `session/prompt` |
| `stt` / `tts` | `session/event` 增量映射、TTS 编码 | `ctx.on('session/event')` |
| `llm.emotion` | 模型结构化输出或 server 规则产出枚举 | `session/event` |
| `mcp`（可选） | `initialize` / `tools/list` / `tools/call` | `ctx.tools.register(...)` |

**插件模块**

| 文件 | 职责 |
| --- | --- |
| `package.json` / `cordis.patch.yml` | npm 包与装配注册，`engines.dsh` 锁版本 |
| `src/index.ts` | `apply(ctx)`，随引擎生命周期启停 |
| `src/ws.ts` / `src/pin.ts` | client 无关 WS 服务；码生成、限次、过期、Device-Id 绑定与撤销 |
| `src/voice.ts` | Opus ⇄ PCM、ASR/TTS 适配 |
| `src/session.ts` / `src/emotion.ts` | 会话桥、情绪产出与枚举版本化 |
| `src/mcp-client.ts`（可选） | 工具转发 |

**关键决策**

| # | 决策 | 依据 |
| --- | --- | --- |
| D1 | 插件自监听 LAN 端口，不改宿主绑定与路由守卫 | 引擎回环绑定与守卫为硬编码，改宿主即需重编译 APK，违反 C4 |
| D2 | 会话桥走 Agent 会话，不走 `ctx.llm.stream()` | 仅前者复用工作区、工具与插件生态 |
| D3 | 情绪由 server 以枚举返回，client 自行映射本地资源 | 换形象/换资源包不改 server（C7）；对齐 `llm.emotion` |
| D4 | 首版仅 WebSocket，不做 MQTT+UDP | 后者需 broker 与 AES-CTR key/nonce 下发 |
| D5 | Opus 编解码用纯 JS/WASM | Direct 模式拒绝用户安装原生 `.node`（默认关闭） |
| D6 | ASR 复用内置端侧模型，TTS 自接 | SenseVoiceSmall INT8 约 241 MB、离线、整段 16 kHz WAV 非流式；仓库无 TTS provider |
| D7 | 固件在 Waveshare 板上从零实现应用，复用示例驱动 | 出厂 bin 无源码；`xiaozhi-esp32` 工具链不同，只作协议参考 |

### 2.4 部署实施架构

#### 2.4.1 硬件要求

| 项 | 要求 |
| --- | --- |
| 手机 | Android 8+，arm64 / x86_64，约 1 GB rootfs 空间；开启电池优化白名单、保留「引擎运行中」通知 |
| ESP32 | Waveshare ESP32-S3-Touch-AMOLED-1.75C：ESP32-S3、32 MB Flash / 8 MB PSRAM、466×466 QSPI AMOLED（CO5300）、CST9217 触摸、ES7210 双麦 + ES8311 编解码、AXP2101、QMI8658 |
| 网络 | 手机热点（网关固定）或同一 WiFi |
| 外部依赖 | DSH 账号登录（走账号余额，无需 API Key；或自备模型 API Key）；ASR 模型约 241 MB（首次下载）；TTS provider；形象资源（外部提供） |
| 工具链 | 固件：ESP-IDF v6.0.2 或 v5.5.5（Waveshare 示例支持版本）；`xiaozhi-esp32` 协议参考为 release/v6.1。仅改 App 时才需 Linux x86_64 + JDK 17 + Flutter 3.47.7 |

#### 2.4.2 部署架构

```sh
# 1. 手机：安装官方 release APK，登录 DSH 账号（走账号余额；或自备模型 API Key）
# 2. 手机：在 dsh-Go「终端」页放入插件并安装（装上即生效；改 lib/ 后在「引擎」页重启内核）
mkdir -p /root/.dsh/dsh-go/plugins
cp -r /sdcard/Download/dsh-voice-channel /root/.dsh/dsh-go/plugins/
dsh plugin --profile web add /root/.dsh/dsh-go/plugins/dsh-voice-channel

# 3. 阶段 1/2：PC dsh web 与 Android client 连同一插件实例，验证 client 无关协议
# 4. 阶段 3：Waveshare 板从零实现固件（示例基线见 TODO.md），用 ESP-IDF 构建烧录
#    出厂 bin 仅用于恢复/对比：
esptool.py --chip esp32s3 --port <PORT> write_flash 0x0 ESP32-S3-Touch-AMOLED-1.75C_FactoryOnly_20260114.bin
```

无 APK 重编译、无签名、无插件审核门禁（本地目录插件，`dsh-Go/docs/design/native-plugins.md:309`）。三阶段顺序与验收以 `TODO.md` 为准。

## 三、方案验证

### 3.1 部署和功能验证

#### 3.1.1 部署环境

##### 3.1.1.1 环境要求

| 项 | 取值 |
| --- | --- |
| 宿主 | `dsh-Go` 官方 arm64 release APK，本机引擎（`web` profile） |
| 插件 | `dsh-voice-channel`，`engines.dsh` 与运行时版本一致 |
| client | 阶段 1：PC `dsh web` + 手机 APK；阶段 2：自写 Android APK；阶段 3：Waveshare 板（ESP-IDF v6.0.2 / v5.5.5） |
| 网络 | 手机热点，设备与手机同网段，端口未被系统策略拦截 |
| 凭据 | DSH 账号登录（或模型 API Key）；插件签发的设备 token |

##### 3.1.1.2 部署操作

按 2.4.2 执行。验证前确认：引擎进程运行中、插件已加载（日志有就绪标记）、`0.0.0.0:<port>` 处于监听。

#### 3.1.2 链路与会话功能验证

##### 3.1.2.1 功能验证标准

| # | 门槛 | 通过标准 |
| --- | --- | --- |
| G1 | 插件在引擎内 `listen('0.0.0.0')` | 手机热点下 client 可建立 TCP；`ws://` 握手成功 |
| G2 | PIN 绑定 | 未绑定 client 拿不到 WS 端点；确认后签发 token；错误码限次生效 |
| G3 | 会话闭环 | 语音或文本 → `session/prompt` → 流式回复 → 播报/显示；P95 首字 < 2.5 s |
| G4 | 情绪解耦 | server 只返回 `emotion` 枚举；替换 client 资源包与映射表即可换形象，server 零改动 |
| G5 | 稳定性 | 双向 Opus 16k/24k 60 ms，锁屏/后台/Doze 连续 30 min 不掉线，无累积抖动与引擎回收 |
| G6（可选） | 协议闭环 | MCP `initialize` / `tools/list` / `tools/call`，至少 `self.get_device_status` 可调 |

##### 3.1.2.2 功能验证环境

同 3.1.1.1；另需一台同网段终端抓包核对握手头与 Opus 帧，以及真机电池/功耗观测。

##### 3.1.2.3 功能验证过程

实施顺序以 `TODO.md` 三阶段为准，取代此前的 P0–P4 分期：

| 阶段 | 内容 | 对应门槛 |
| --- | --- | --- |
| 1 插件 | client 无关协议、语音双向、`emotion` 产出；PC `dsh web` 与手机 APK 双 client 验收 | G1、G3、G4 |
| 2 Android client | 自写 APK、`emotion → gif` 映射表、换资源包不改 server；抽出可移植 client 层 | G3、G4 |
| 3 ESP32 | Waveshare 板从零实现：WS client、音频编解码、状态机、形象渲染、PIN/token、触摸交互 | G1、G2、G5 |

##### 3.1.2.4 功能验证报告

| 项 | 实测值 | 结论 | 日期 |
| --- | --- | --- | --- |
| G1 LAN 可达 / 后台存活 |  |  |  |
| G2 PIN 绑定与越权拒绝 |  |  |  |
| G3 会话闭环 / 首字 P95 |  |  |  |
| G4 情绪解耦 / 换资源包 |  |  |  |
| G5 30 min 稳定性 / 功耗 |  |  |  |
| G6 工具调用（可选） |  |  |  |

##### 3.1.2.5 功能验证结论

待验证。判定规则：任一必需门槛不通过即暂停；G1/G5 失败时把同一插件装到 PC `dsh web`，切回方案 B（接受便携/公网降级）；G2–G4 失败只修插件协议层，不动宿主与固件板级代码。

#### 3.1.3 配套功能方案验证

模板中的数据库、负载均衡、监控、扩容、备份恢复在本方案均不适用：单手机单 client、无状态长连接、无持久化业务库；状态在设备与插件配置中，重置后可重建。多设备并发时再补连接数上限、设备表与鉴权轮换。

### 3.2 基准测试

不单列。首版性能指标为首字时延与 30 min 链路稳定性，已在 3.1.2.1/G3/G5 定义；无集群或吞吐量基准可测。

### 3.3 场景验证

| 场景 | 操作 | 通过标准 |
| --- | --- | --- |
| client 无关 | 同一插件实例依次接 PC 网页、Android APK、ESP32 | 三者语音与情绪表现一致，插件代码零改动 |
| 便携语音会话 | 断开外网仅保留手机热点与模型 API，唤醒设备提问 | 端侧 ASR 完成转写，DSH 回复并播报，G3 达标 |
| 换形象 | 仅替换 client 映射表与 gif 资源包 | server 零改动，G4 达标 |
| 设备控制（可选） | Agent 调用设备侧工具 | `tools/call` 生效并回传结果 |
| 后台与重连 | 锁屏、切应用、开关 WiFi | 自动重连，未绑定 client 仍被拒绝 |

## 四、总结

### 4.1 方案适用场景分析和总结

适用：单人随身使用、可信 LAN/热点、需要 DSH Agent 能力与插件生态、可配置模型 API 与 TTS、形象资源外部提供。

不适用：完全离线语音（模型 API 与 TTS 仍需外部）、无屏无输入设备且不接受预共享 token 的绑定场景、多设备并发或公网暴露、对后台保活有 SLA 要求的生产环境。

### 4.2 方案硬件配置推荐和总结

| 项 | 最低 | 推荐 |
| --- | --- | --- |
| 手机 | Android 8+ / arm64 / 4 GB RAM | 近三代旗舰、6 GB+ RAM、电池优化白名单可配 |
| ESP32 | Waveshare ESP32-S3-Touch-AMOLED-1.75C | 同型号 + 外部形象资源（一套状态约 0.4–0.8 MB，32 MB Flash 可容纳） |
| 网络 | 同一 WiFi | 手机热点，网关固定 |
| 存储 | 约 1 GB rootfs | 额外预留约 241 MB ASR 模型与 TTS 缓存 |

### 4.3 方案总结

结论：**有条件可行**。不确定项已收敛为一处——插件能否在 `dsh-Go` 的 PRoot/Node 进程内稳定监听 LAN 端口并保持长连接（G1）；协议侧有完整源码参考，宿主侧有现成 APK 与本地插件安装机制，固件侧驱动与 BSP 有可复用示例。

主要风险：G1 无真机实证；厂商后台策略杀进程；端侧 ASR 整段非流式且占约 400 MB 内存（x86 实测 10 s 音频 0.26–0.49 s，手机未实测）；TTS 需自接；`ws` / Opus 依赖在 rootfs 内的可安装性；DSH rc 版本 `ctx` API 漂移；LAN 明文需 token 轮换与 `Device-Id` 白名单；固件从零实现的工作量。

待决项：情绪判定来源（模型结构化输出 vs server 规则）；情绪枚举与资源方交付契约；PIN 采用「设备显示码 + 手机确认」还是预共享 token；TTS provider；是否下发 241 MB 端侧 ASR 模型；阶段 3 的 ESP-IDF / Arduino 示例基线；是否分发衍生 APK（涉及许可义务）。详见 `TODO.md`。

## 附录

### A 关键事实索引

| 路径 | 结论 |
| --- | --- |
| `dsh-Go/README.md:47-50` | 官方 release APK 可直接安装 |
| `dsh-Go/docs/plugins/hosts.md:13-29` | `dsh plugin --profile web add /绝对路径`，装上即生效，无签名门禁 |
| `dsh-Go/app/.../runtime/DirectLaunch.kt:200-202` | `--host` 必须为 `127.0.0.1`，`0.0.0.0` 非法 |
| `dsh-Go/docs/design/plugin-seams.md:303,310` | 回环绑定 + Host/Origin + cookie 守卫 |
| `dsh-Go/docs/plugins/files.md:72` | 插件与引擎同进程、无沙箱 |
| `dsh-Go/docs/plugins/stability.md:37-45` | `dshGo` 通道 32 KiB / 4 Hz / 3 s，不可传音频 |
| `dsh-Go/docs/design/voice-input.md:7,34`、`docs/plugins/voice.md:9,70-71` | 端侧 ASR 整段非流式；无内置 TTS |
| `dsh-Go/docs/guide/zh/11-faq.md:20-25` | 前台服务仍可能被系统结束 |
| `dsh-Go/ASSETS.md:3,19` | 品牌与美术素材不在 AGPL-3.0 范围内，不得用于自行发布的产品 |
| `dsh-Go/app/assets/pets/*/pack.json` | 「动画 key → 帧序列」映射表结构参考 |
| `xiaozhi-esp32/docs/websocket_zh.md:242`、`main/application.cc:695-701` | `llm.emotion` 与 `SetEmotion()` |
| `xiaozhi-esp32/main/ota.cc:137-157`、`main/application.cc:527-547` | `activation.code` 显示与轮询，可作 PIN 载体 |
| `ESP32-S3-Touch-AMOLED-1.75C/firmware/README_ZH.md:5-9` | 出厂固件为合并镜像，无源码 |
| `ESP32-S3-Touch-AMOLED-1.75C/README_ZH.md:22-37,51-83` | 硬件参数、示例清单与工具链版本 |
| `dsh-mobile-apk/scripts/check-mcp-client-deps.mjs:4-19` | 方案 A 依赖闭包导致 boot 硬崩 |

### B 决策记录

| 项 | 结论 | 依据 |
| --- | --- | --- |
| A 自研/参考安卓宿主 | 否决 | 生态断裂、魔改需重建快照与 APK（`archive/README.md:9`） |
| B PC DSH + HTTP 远程 | 保留为联调/降级 | 工程量最小，但公网与便携代价违背 C2/C3 |
| C `dsh-Go` + `dsh-voice-channel` | 采用 | 现成 APK + 本地插件 + 纯 LAN，满足 C1–C7 |
| 实施顺序 | 插件 → Android client → ESP32 固件 | `TODO.md` |
| 情绪解耦 | server 返回枚举，client 映射本地 gif | `TODO.md`「情绪契约」、`xiaozhi-esp32/docs/websocket_zh.md:242` |
| 固件基线 | Waveshare 板从零实现应用，复用示例驱动 | 出厂 bin 无源码（`firmware/README_ZH.md`） |

历史实现（自造 H5/JS Bridge、Java `ctx.shell`、Termux bootstrap 重定位、Windows mock 预览）已归档至 `archive/`，不作基础。

### C 许可边界

`dsh-Go` 为 AGPL-3.0（`sdk/` 与 `examples/` MIT），`dsh-mobile-apk`、`xiaozhi-esp32` 为 MIT，Waveshare 硬件仓库为 Apache-2.0，`deepseek-ai/deepseek-harness` 见上游 LICENSE。仅安装官方 APK 并自写自用插件、不改造不分发，不触发 AGPL 再发布义务；`dsh-Go/README.md:344` 明确「改造前联系作者 / fork 署名」是协作请求而非许可证附加限制。若改 App 源码或发布衍生包，需履行同许可开源与源码提供义务、标注主仓库与原作者，并替换名称、标识及 Deepy/鲸娘素材（`dsh-Go/README.md:337-344`、`ASSETS.md:3,19`）。完整仓库清单与合规说明见根 `README.md`。
