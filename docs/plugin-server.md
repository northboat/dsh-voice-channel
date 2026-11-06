# 插件服务端（`plugin/`）

面向开发与排障。产品界面上的文案保持口语化，技术细节集中在这里。

相关文档：架构与选型见 [技术方案](technical-proposal.md)，进度与踩坑见 [HANDOFF](../HANDOFF.md)，客户端见 [app-client.md](app-client.md)。

## 1. 职责边界

| 侧 | 负责 | 不负责 |
| --- | --- | --- |
| 插件（PC） | 对话转发、角色与记忆、语音识别（ASR）、语音合成（TTS）、音色配置、PIN 鉴权 | 形象渲染、音频播放、界面文案 |
| 客户端（App / 网页 / ESP32） | 采集音频、播放音频、字幕与情绪动画、连接配置 | 识别、合成、音色选择 |

一句话：**客户端只传字节和文本，不知道也不选择后端引擎与音色。**

## 2. 路由

端口默认 `8787`（`PORT` 可改），监听 `0.0.0.0`。回环免鉴权；LAN 访问需 `Authorization: Bearer <PIN>` 或 `X-PIN: <PIN>`。

| 路由 | 方法 | 说明 |
| --- | --- | --- |
| `/api/health` | GET | `{ ok, pluginLoaded, backend, asr, tts, port, probe }`，发现与连通性探测，**不需要 PIN** |
| `/api/chat` | POST | `{ text, sessionId }` → NDJSON：`state` / `delta` / `emotion` / `done` / `error` |
| `/api/asr` | POST | body 为 s16le 16 kHz mono PCM 或同格式 WAV → `{ ok, text, ms }` |
| `/api/tts` | GET/POST | `{ text }`（可选 `voice/rate/pitch/volume`）→ `audio/mpeg`，头 `x-tts-voice` / `x-tts-ms` / `x-tts-cache` |
| `/api/tts/voices` | GET | 音色清单，`?locale=zh` 过滤，`&live=1` 拉在线全量 |
| `/api/dsh-channel/state` | GET/POST | 角色、记忆条数、`tts` 配置；GET 本机额外返回 PIN |
| `/dsh-channel/memory.log` | GET | 记忆原文（仅本机） |
| `/dsh-channel/clients` | GET | 在线设备（仅本机，进程内统计） |
| `/admin` | GET | 桌面端设置页 |

`/api/tts` 的错误码：`400` 文本为空、`401` 配对码不对、`413` 超过 2000 字、`503` 合成服务不可用（在线服务异常或未联网）。

## 3. 状态文件

| 文件 | 内容 |
| --- | --- |
| `plugin/data/state.json` | `persona`（角色定义）、`sessionId`、`pin`、`tts { voice, rate, pitch }` |
| `plugin/data/memory.log` | JSONL，每行 `{ time, role, text }`，全量保留 |

- `state.json` 由 `lib/state.js` 读写，`normalizeTts()` 做校验：`voice` 白名单 `[A-Za-z0-9._-]{3,64}`、`rate` −50..100 %、`pitch` −50..50 Hz，非法值回默认。
- 默认音色 `zh-CN-XiaoxiaoNeural`，`rate 0` / `pitch 0`。
- 记忆只注入最近 20 条（`INJECT_MEMORY`），走 `systemPrompt.section`，不拼进用户消息。

## 4. 音色管理

音色是**服务端单一出口**，客户端只发文本：

```
client ──{"text"}──▶ /api/tts ──▶ ttsSettings() ──▶ edge-tts ──▶ mp3
                                      ▲
                    DSH_TTS_VOICE（临时）│ state.tts（管理页保存）
```

- 优先级：环境变量 `DSH_TTS_VOICE` > `state.json` 的 `tts` > 内置默认。
- 管理页 `/admin` →「声音」：下拉（内置 8 条中文，可「刷新在线音色」拉全量 322 条）、语速、音调、试听、保存。
- 改完即时生效（下一句），不需要重载插件。
- 可用中文音色：`Xiaoxiao` / `Xiaoyi`（女）、`Yunjian` / `Yunxi` / `Yunxia` / `Yunyang`（男）、`liaoning-Xiaobei`（东北）、`shaanxi-Xiaoni`（陕西），完整名加 `zh-CN-` 前缀与 `Neural` 后缀。
- 在线清单本身要 7–9 s，所以接口默认先回内置 8 条（`source: builtin`），后台再拉全量。

## 5. 语音引擎（常驻 Python worker）

`plugin/.venv` 里的 Python 同时跑两个 worker，协议均为「长度前缀 JSON + JSON 行响应」，崩溃由 Node 侧重启：

| worker | 输入 | 输出 |
| --- | --- | --- |
| `asr_worker.py`（vosk 0.3.45 + `vosk-model-small-cn-0.22`） | `<uint32 id><uint32 nbytes><pcm>` | `{"id","ok","text","ms"}` |
| `tts_worker.py`（edge-tts 7.3.1，在线） | `<uint32 nbytes><json>` | JSON 行头 + 原始 mp3 字节 |

- ASR 模型只加载一次，recognizer 复用并预热；1 s 音频约 67 ms。
- TTS 首次合成约 2.1 s（首音频块 1.67 s），同文本缓存命中约 30 ms（进程内 64 条）。
- TTS 输出格式固定 `audio-24khz-48kbitrate-mono-mp3`（edge-tts 7.x 无 `output_format`）；ESP32 阶段要么解 mp3，要么在 PC 侧转 PCM。
- TTS 不做流式、不分句：整段文本一次合成后返回，因此首句延迟与文本长度相关。

## 6. 部署与生效

```powershell
# 一次性依赖（与 ASR 同一个 venv）
& plugin/.venv/Scripts/python.exe -m pip install edge-tts

# 只调试语音链路：独立服务（8788），不依赖 Desktop
node plugin/asr-server.mjs
```

- `lib/index.js`、`lib/state.js`、`lib/tts*.js` 的改动静止于**重载插件**后生效；`lib/chat.js` 是按请求热加载。
- 管理页与演示页在 `plugin/public/`，刷新浏览器即可看到改动。
- `plugin/models`、`plugin/.venv` 不入库。

## 7. 排障

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 客户端「连接失败」 | 不在同一网段 / Windows 防火墙拦入站 | 放行 TCP 8787，或 `netsh advfirewall firewall add rule name="dsh-channel" dir=in action=allow protocol=TCP localport=8787`（管理员） |
| `401` | 配对码不匹配 | 管理页查看或重置配对码 |
| `/api/tts` 返回 `503` | PC 未联网、Edge 服务异常、音色名非法 | 先试听；看 `plugin` 控制台 `[dsh-channel][tts]` 输出 |
| 有声但没声音 | 手机媒体音量 / 播放被打断 | 检查 `TtsOutput` 的 `generation` 打断逻辑，重发一轮 |
| 语音识别慢/不准 | Vosk 小模型上限 | 换 `vosk-model-cn-0.22`（1.3 GB）或 SenseVoice，客户端零改动 |
| 长回复合成慢 | 整段合成、无流式 | 待做：按句切分 + 边合成边下发 |
