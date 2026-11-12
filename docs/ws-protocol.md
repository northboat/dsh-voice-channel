# WS 协议（v1，已实现）

> 状态：**已落地**（插件 0.11.0 / Android 0.11.0）。协议不是自创的——**完全照抄 `xiaozhi-esp32`（小智）的 WebSocket 协议**，
> 所以未改固件的小智设备把 OTA 指到本插件即可直连；Android 只是同一协议的一个 client（用 PCM 免掉 Opus 依赖）。
> 设备侧依据：`xiaozhi-esp32/docs/websocket_zh.md`、`main/protocols/websocket_protocol.cc`、`main/application.cc`、`main/ota.cc`。
> 实现：`plugin/lib/ws.js`（RFC6455 服务端）、`plugin/lib/xiaozhi.js`（协议会话）、`plugin/lib/vad.js`（服务端断句）、
> `plugin/lib/opus.js` / `mp3.js`（编解码）、`android/.../net/WsSession.kt`（客户端）、`voice/StreamingPlayer.kt`（流式播放）。

## 1. 连接

| 项 | 值 |
| --- | --- |
| 地址 | `ws://<host>:8787/xiaozhi/v1/`（`/ws` 同样接受，便于调试） |
| 端口 | 与插件同端口（插件自己的 `http.createServer`，走 `server.on('upgrade')`） |
| 握手头 | `Authorization: Bearer <PIN>`（必需，本机 loopback 免）、`Protocol-Version: 1`、`Device-Id: <MAC/UUID>`、`Client-Id: <UUID>` |
| 鉴权失败 | 升级请求直接 `401`，连接不建立（不会出现「连上再踢」） |
| 子协议 / 压缩 | 不声明 `Sec-WebSocket-Protocol`，不协商 `permessage-deflate`（OkHttp 与 esp-websocket 默认都不发） |

实现约束：插件**不依赖 `ws` 包**，`lib/ws.js` 手写握手与帧（文本/二进制/分片/ping-pong/close），单条消息上限 4 MB。

## 2. 握手（hello）

设备连上后立刻发（10 s 内必须收到服务端 hello，见 `websocket_protocol.cc:194-201`）：

```json
{
  "type": "hello",
  "version": 1,
  "transport": "websocket",
  "features": { "mcp": true },
  "audio_params": { "format": "opus", "sample_rate": 16000, "channels": 1, "frame_duration": 60 }
}
```

服务端回（**必须**含 `transport: "websocket"`，否则设备判失败）：

```json
{
  "type": "hello",
  "transport": "websocket",
  "session_id": "xiaozhi-mwdof289-70cl",
  "audio_params": { "format": "opus", "sample_rate": 24000, "channels": 1, "frame_duration": 60 }
}
```

- `audio_params` 是**各自的输出参数**：client 的 hello 说的是上行（16 kHz Opus），服务端说的是下行（24 kHz Opus，与 edge-tts 的 24 kHz 对齐，不重采样）。
- `version` 是**二进制协议版本**（1/2/3，见 §4），与 `Protocol-Version` 头一致；服务端按它决定怎么拆帧。
- 5 s 内没有合法 hello → 服务端 `close 1008`。

## 3. 会话消息

| 方向 | 消息 | 说明 |
| --- | --- | --- |
| C→S | `{"type":"listen","state":"start","mode":"auto"}` | 开始上行。`auto`：服务端 VAD 断句（ESP32 走这条）；`manual`：等 `listen stop` 才断句（Android 走这条） |
| C→S | 二进制帧 | 音频，紧跟 `listen start` 之后 |
| C→S | `{"type":"listen","state":"stop"}` | 一句话说完：服务端立刻收尾并识别 |
| C→S | `{"type":"abort","reason":"wake_word_detected"}` | 打断：停声 + 丢弃排队 |
| C→S | `{"type":"mcp",...}` | 忽略（本项目不用 MCP） |
| S→C | `{"type":"stt","text":"..."}` | 用户字幕 |
| S→C | `{"type":"llm","emotion":"happy"}` | 情绪枚举，客户端自己映射表情 |
| S→C | `{"type":"tts","state":"start"}` | 开始朗读 |
| S→C | `{"type":"tts","state":"sentence_start","text":"..."}` | 本句字幕，**紧接该句的音频帧** |
| S→C | 二进制帧 | 该句的音频（60 ms 一帧） |
| S→C | `{"type":"tts","state":"stop"}` | 本轮朗读结束（音频可能还在客户端缓冲里播） |
| S→C | `{"type":"alert","status":"error","message":"...","emotion":"error"}` | 服务端错误（设备会弹提示） |

所有消息都带 `session_id` 回显；客户端不必校验。

## 4. 二进制帧

- **上行**：`version=1` 是裸 Opus 帧（16 kHz / 单声道 / 60 ms = 960 采样）；`version=2/3` 带 16 / 4 字节头，服务端按 `hello.version` 拆（`websocket_protocol.cc:29-53`）。
- **下行**：一律是单帧音频，无头。`format:"opus"` 时是 24 kHz / 60 ms（1440 采样）的 Opus；`format:"pcm16"` 时是 s16le 2880 字节（见 §5）。
- 同一时刻只有一路上行、一路下行，所以二进制帧不需要 `id` 多路分解。

## 5. 扩展：PCM16（仅 Android 用）

小智设备只会 Opus（解码器在固件里），手机端没有现成的 Opus 编码器（MediaCodec 的 Opus encoder 老系统没有），
所以协议留了一个口子：**client 在 hello 里把 `audio_params.format` 声明成 `"pcm16"`**，服务端就上下行都用裸 PCM：

```json
{"type":"hello","version":1,"transport":"websocket",
 "audio_params":{"format":"pcm16","sample_rate":16000,"channels":1,"frame_duration":60}}
```

- 上行：s16le / 16 kHz / 单声道二进制帧。
- 下行：服务端 hello 回 `{"format":"pcm16","sample_rate":24000,...}`，每帧 1440 采样（2880 字节），直接喂 `AudioTrack`。
- 对 Opus client 无影响：`format` 缺省 / `"opus"` 都走 Opus，所以要装 `opusscript` + `mpg123-decoder` 两个包（见 `package.json`）。

## 6. 服务端的处理链

```
上行音频 → (Opus 解码) → 断句 → ASR(vosk) → stt
        → DSH 会话(chat.js) → 逐句切分 → 每句 edge-tts → mp3 解码 → (Opus 编码)
        → tts sentence_start + 60 ms 节奏推帧 → tts stop
```

- **断句**：`mode=auto` 跑服务端 VAD（帧内峰值 + 噪声底，静音 700 ms 收句）；`mode=manual` 用 `listen stop` 收句。
- **逐句流水线**：一句话合成完就发，不等整段回复；合成下一句的同时上一句正在播（`Pipeline` 队列，上限 240 帧）。少于 16 字的短句并进下一段一起合成（`lib/text.js` 的 `MIN_SENTENCE`），少一次在线 TTS 请求。
- **合成超时**：edge-tts 在线单次 2~17 s、worker 对网络异常还会重试一次（最坏 ~2×），所以 `lib/tts.js` 的请求超时是 **90 s**；超时后 worker 迟到的音频字节会被整块跳过，不会污染后续帧（详见 `HANDOFF.md` 末节）。
- **节奏**：线性 60 ms 一帧发送（不是突发），客户端缓冲小也不会断音；opus 帧末补零对齐。
- **排队**：一个连接内的请求串行执行（同一个 DSH 会话，两个 turn 交叉会让回复串味）；朗读中新说的话排在后面播，**不打断**。
- **abort**：`abortToken` 自增 + 清队列 + 关掉当前 Pipeline，实测 60 ms 内回 `tts stop` 且不再下发音频。
- **情绪**：规则判定（`lib/text.js` 的 `detectEmotion`），第一句前发一次，结论变了再补一次。

## 7. 心跳与断线

- 服务端每 20 s 发 ping；90 s 没有任何帧（含 pong）就断开。
- 客户端断线后 0.5 → 1 → 2 → 4 → 8 → 10 s 退避重连，重连后重新 hello（PIN 不缓存）。
- 在途请求在断线时一律作废：没有 `tts stop` 的半截回复不当作完整回复。

## 8. OTA（小智设备入场）

```
POST /xiaozhi/ota/    （GET 也接受）
→ 200 {"server_time":{"timestamp":...,"timezone_offset":480},
       "websocket":{"url":"ws://<host>:8787/xiaozhi/v1/","token":"<PIN>","version":1}}
```

设备把 `websocket.url/token/version` 写进 NVS（`ota.cc:180-199`），下次开机直连本插件，**不用改固件**。
不下发 `firmware` / `activation`，所以不会触发升级或激活流程。
前提是设备自身的 `wifi/ota_url`（NVS 或 `CONFIG_OTA_URL`）已经指向本机——出厂固件默认连官方云。

## 9. 与 HTTP 的关系

HTTP 路由**全部保留**（演示页、管理页、脚本调试都在用），WS 是客户端的主通道：

```
lib/chat.js  ← /api/chat  与 ws 的 chat 用例共用
lib/asr.js   ← /api/asr   与 ws 的上行识别共用
lib/tts.js   ← /api/tts   与 ws 的逐句合成共用
```

`/api/health` 多了 `ws: {live,total,sessions}` 与 `codecs: {opus,mp3}`，可直接看出长连接与编解码依赖状态。

## 10. 验收清单（已跑过）

| 项 | 结果 |
| --- | --- |
| hello 往返、下行参数 24000/opus/60 | 通过（`plugin/test/ws_test.mjs`） |
| 上行 Opus → VAD 断句 → 真实 Vosk 识别 | 通过：`zh.wav` 识别出 `帮我把这个文件打开然后查一下镜像`（`plugin/test/ws_full.mjs`） |
| 逐句 `sentence_start` + 音频、帧长与时长对得上 | 通过（短句合并后 2 句 / 62 帧 / 3.72 s） |
| TTS 超时后迟到音频不破坏帧流 | 通过：故意 300 ms 超时，后续请求仍成功（11.7 s），`health.tts.lastMs` 有值 |
| 下行按 60 ms 节奏发送 | 通过，实测平均 63 ms |
| `abort` 后 300 ms 内 `tts stop` 且不再下发 | 通过，实测 61 ms，额外帧 0 |
| pcm16 client 上下行、每帧 2880 字节 | 通过 |
| 鉴权失败 401、路径不匹配 404 | 通过 |
| `/api/health` 的 ws/codecs、OTA 下发、连接计数归零 | 通过 |
| 真机（Android 0.11.0）通话闭环 | 待测（需在 Desktop 重载插件后跑） |
| 未改固件的小智设备 | 未测（缺硬件；协议按固件源码对齐） |
