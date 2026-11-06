# TODO — 插件 → Android Client → ESP32

实施顺序以本文件为准；`docs/technical-proposal.md` 保留方案、协议与验收标准，项目介绍与参考仓库见 `README.md`，当前进度与踩坑见 `HANDOFF.md`。三阶段串行，上一阶段验收通过再进入下一阶段。

实现目录：`plugin/`（插件）、`android/`（Android client）、`esp32/`（ESP32 client）；上游检出目录仅本地参考，不随仓库提交。

## 约束

- 插件是唯一服务端，**client 形态无关**：PC `dsh web`、手机 APK、ESP32 接同一套协议，换 client 不改插件代码。
- 通路三件套：语音输入（麦克风 → ASR → DSH 会话）、语音输出（会话流式 → TTS → 扬声器）、动态形象（回复情绪 → client 本地 gif）。
- **插件只返回语义情绪，client 只做「情绪 → 本地资源」映射**；任一侧换形象/换资源包不影响另一侧。
- **形象资源由外部提供**，本方案只定义情绪枚举、映射契约与渲染契约，不产出美术资源。
- 每阶段产出可运行 demo 与实测记录，不用 mock 冒充。

## 情绪契约（解耦边界）

```json
{"session_id": "xxx", "type": "llm", "emotion": "happy"}
```

| 项 | 约定 |
| --- | --- |
| 字段 | 回复事件中的 `emotion`，字符串枚举；可选 `intensity`（0–1），首版不启用 |
| 枚举 | 首版 `neutral / happy / sad / angry / surprised / thinking / sleepy / error`，随协议版本扩展；未知值 client 回退 `neutral` |
| 禁止 | 插件不下发资源 ID、URL、文件名、帧数据，不感知 client 的形象、尺寸、语言、明暗 |
| 映射 | client 维护 `emotion → 本地动画 key` 表（资源由外部提供）；换皮肤只替换映射表与资源包 |
| 状态 | `idle / listening / thinking / speaking` 由 client 从 `listen` / `tts` 链路事件本地推导，与 `emotion` 正交；叠加优先级由 client 定 |
| 依据 | 与 `xiaozhi-esp32/docs/websocket_zh.md:242` 的 `type:"llm"` + `emotion` 一致；设备侧 `SetEmotion()` 实现见 `main/application.cc:695-701` |

## 1. 插件（DSH host 侧）

- [ ] 定义 client 无关协议：WS 长连接，消息分音频上行、音频下行、控制与情绪事件三类；带版本与能力协商（`hello`），情绪枚举随协议版本下发。
- [ ] 工程骨架：npm 包 + `cordis.patch.yml` + `apply(ctx)`；`session/prompt`、`ctx.on('session/event')`、`ctx.tools.register`。
- [ ] 语音输入：音频上行 → VAD 分段 → ASR（优先复用 `dsh-Go` 端侧 SenseVoice，`speech/transcribe`，整段 16 kHz WAV、非流式）→ DSH 会话。
- [ ] 语音输出：`session/event` 增量 → TTS（`dsh-Go` 无内置 provider，需自接）→ 音频下行；支持打断。
- [ ] 情绪产出：从模型结构化输出或 server 侧规则判定当前回复的 `emotion`，随回复事件下发；规则无法判定时回退 `neutral`；不产出资源、不绑定 client 形象。
- [ ] 服务与鉴权：自监听 `0.0.0.0:<port>`、token / 设备 ID、限次、重连（引擎本身只绑 `127.0.0.1`）。
- [ ] 验收 A：PC 上 `dsh web` 装插件，PC/网页客户端跑通三件套。
- [ ] 验收 B：手机安装现成 `dsh-Go` APK，不改插件代码，跑通同一通路。
- [ ] 门禁：A、B 通过才进入阶段 2。

> 官方 `dsh-Go` APK 的边界：`audio` / `media` 只回整段音频、不支持流式（`dsh-Go/docs/plugins/voice.md:70-71`），`dshGo` 通道 32 KiB / 4 Hz（`dsh-Go/docs/plugins/stability.md:37-45`）。若验收 B 因此不达标，该 APK 仅作控制面 client，完整语音与形象由阶段 2 自写应用承接——插件协议不变。

### 进度快照（2026-11-06，不改动上面的路线图复选框）

已完成（代码层，待整体验收）：

- 工程骨架：`plugin/` npm 包 + `cordis.patch.yml` + `apply(ctx)` + 会话收发；`/api/chat` NDJSON（`state` / `delta` / `emotion` / `done`），`/api/health`，同源管理页与演示页，侧边栏入口。
- 角色定义 / 长期记忆（`plugin/data/memory.log` 全量保留、按会话载入最近 20 条快照；T1 后走 `systemPrompt.section`，用户消息为原文）、可改 PIN + LAN 认证、已连接 client 可视化、工作区归属。
- 情绪产出：首版 **server 侧规则判定** `detectEmotion()`，未知回退 `neutral`；未用模型结构化输出。
- 服务与鉴权：**部分**——`0.0.0.0:8787` + 预共享 PIN + client 请求级记录；WS 长连接、token / 设备 ID 绑定、限次、重连未做。
- 语音输出：插件新增 `/api/tts`（`lib/tts.js` + `lib/tts_worker.py` + `lib/tts_http.js`，Edge 在线神经语音 edge-tts 7.3.1，零本地模型、需联网），`zh-CN` 8 个音色（含东北 / 陕西方言）+ `/api/tts/voices`；演示页改走该接口并保留 speechSynthesis 回退。**整段合成、无流式、无打断**，故「流式 TTS 下行 / 打断」仍未完成。

未完成（复选框保持不勾）：WS 协议与能力协商、流式 ASR / TTS 下行 / 打断、模型结构化情绪、手机 APK 验收 A/B、阶段 2 真机闭环 / 阶段 3。

### 阶段 2 进展（2026-11-06，复选框保持不勾）

- `android/` 0.3.0 已落地（Kotlin + Jetpack Compose，minSdk 28）：鲸鱼主屏（无输入框、纯语音、小字字幕、情绪动画）、角落设置弹窗（局域网扫描连接 + PIN）、音色切换与音色包下载管理。
- **语音输入改为瘦客户端（ASR 在插件端）**：0.2.0 曾在 APK 内置 Vosk（42 MB 模型 + 19 MB so → 116 MiB），且本 App 本来就必须连插件，离线识别价值残缺。参照 `xiaozhi-esp32`（设备只做 AFE/VAD + 编解码 + 播放）改为：手机 `AudioRecord` 16 kHz + 能量 VAD 700 ms 断句 → 整段 PCM `POST /api/asr` → PC 侧 Python `vosk` 0.3.45 + `vosk-model-small-cn-0.22`（Apache-2.0）解码。模型只在 PC（`plugin/models` + `plugin/.venv` 已 gitignore），客户端可随时换大模型而零改动；当时输出仍走系统 TTS（0.4.0 起改为插件端合成，见下）。
- 插件新增 `/api/asr`（`lib/asr.js` + `lib/asr_worker.py` + `lib/asr_http.js`）：模型加载一次（约 580 ms），recognizer 复用 + 启动预热，1 s 音频 67 ms、5 s 224 ms；另有独立服务 `asr-server.mjs`（8788）仅供本机调试。**注意**：`/api/asr` 在 `index.js` 里，需重载一次插件才生效。
- 插件零改动（chat 侧）：`/api/health` 发现 + `/api/chat` NDJSON + PIN 全部沿用；情绪 gif 只参考 `dsh-Go/docs/assets` 的 4 状态命名，素材按 `ASSETS.md:3,19` 不复制、不分发。
- **`:app:assembleDebug` 已成功**：`app-debug.apk` **26,535,693 B（25.31 MiB）**，161 条目，`com.dsh.channel.whale / 0.3.0 / minSdk 28 / targetSdk 34`，v2 验签通过；dex 24.6 MiB，无 native 库、无模型（去掉 `material-icons-extended` 34 MB，`Mic` 改 Canvas 自绘）。
- 验证：`adb install -r` 后 `versionCode=3`，冷启动无崩溃，设置页显示「识别状态：插件端识别 /api/asr（本机不加载模型）」；PC 侧用 SAPI 合成「你好，今天天气怎么样」→ 识别 `你好今天天气怎么样`。**真机语音闭环待测**：测试时手机 WiFi 关闭、USB 共享网段被防火墙挡、`adb reverse` 在该 ROM 不转发。构建、命令与实测细节见 `android/README.md`。

### 阶段 2 增量：语音输出也搬到插件端（2026-11-07，APK 0.4.0）

- **动机**：阶段 3 的 ESP32 跑不了中文神经 TTS，音频下行无论如何要插件端做；PC / 手机 / ESP32 共用 `/api/tts` 才不用维护两套逻辑（与 `xiaozhi-esp32` 的「设备只编解码 + 播放」一致）。
- **删除**：Android 端系统 `TextToSpeech`（`TtsOutput` 重写为拉 `/api/tts` + `MediaPlayer` 播放）、`state.systemVoices` / `systemVoiceName`、「系统 TTS 音色」列表、「打开系统 TTS 语音数据安装」入口、清单里的 `TTS_SERVICE` `<queries>`、`SettingsStore.systemVoiceName`。
- **音色包语义变更**：`locale + pitch 倍率 + rate 倍率 + voiceName` → `voice`（Edge `ShortName`）+ `rate`（百分比，如 `+10`）+ `pitch`（Hz，如 `-12`），原样作为 `/api/tts` 请求参数；内置 4 个（小鲸鱼 / 温柔 / 活泼 / 沉稳男声），下载与 Zip Slip 校验保留，新增 `voice` 白名单校验。
- **新增**：`net/TtsClient.kt`（POST `/api/tts` → cache 目录 mp3，带 PIN / device-id）、`TtsOutput` 用 `generation` 丢弃过期合成结果（打断）、合成线程 + 主线程播放。
- **构建**：`:app:assembleDebug` 成功，`26,434,679 B`（**25.21 MiB**）/ 161 条目 / `versionCode=4` / `0.4.0`；`adb install -r` 通过，冷启动无崩溃，设置页只剩 4 个服务端音色预设。
- **待测**：手机 → PC 的 8787 仍被 Windows 防火墙拦（`ping` 与 TCP 均不通；手机 `rndis0` 10.86.196.160/24 与 PC 同段），所以录音 → ASR → 合成 → 播放的闭环要放行入站或换同 WiFi 后再测；PC 侧已用 App 同参数（`voice` + 整数 `rate`/`pitch`）验证 `/api/tts` 返回 `audio/mpeg`。
- **0.5.0：client 不再发音色参数**。音频消费方是模型 / 下游分析而非挑音色的人耳，故 `{"text"}` 之外全砍：删 `VoicePacks.kt`、`assets/voices/voice-packs.json`、`SettingsStore.voicePackId`、设置页音色与音色包区块、唤醒音效；音色只在服务端由 `DSH_TTS_VOICE` 定（默认 `zh-CN-XiaoxiaoNeural`）。APK `26,385,163 B`（25.16 MiB）/ 159 条目 / `versionCode=5` / `0.5.0`，装包后设置页只剩「语音能力 + 局域网插件」。
- **音色管理补齐（服务端）**：`state.json` 新增 `tts {voice, rate, pitch}`（`lib/state.js` 校验归一化），`lib/tts.js` 的 `ttsSettings()` 按「环境变量 > state > 默认」取值，`/api/tts` 不带参数时用它；管理页 `/admin` 新增「语音音色」卡片（下拉 + 刷新在线音色 + 语速/音调 + 试听 + 保存），`/api/dsh-channel/state` GET/POST 带上 `tts`；演示页的音色下拉删除，只发文本。实测保存 Yunxi/+10%/−5Hz 后 `/api/tts` 返回 `x-tts-voice: zh-CN-YunxiNeural`，非法值自动归一化。
- **UI 产品化（0.6.0）**：App 设置页删掉全部技术说明与「语音能力」节，只剩地址 / 配对码 / 搜索 / 连接状态；`connected`（填过地址）改为 `reachable`（健康检查通过才亮）；错误提示全部人话。管理页改标题「小鲸鱼」、长句收进折叠「运行状态」、卡片与 hint 精简；演示页显示中文心情与简单状态。技术细节迁入新建的 `docs/app-client.md` 与 `docs/plugin-server.md`。APK `26,385,163 B`（25.16 MiB）/ `versionCode=6` / `0.6.0`，装包截图确认。
- **ASR 中英混输不可用（2026-11-07 实测，样本见 `plugin/testdata/`）**：纯中文句 `帮我把这个文件打开，然后查一下镜像` 全对（328 ms）；混说句 `帮我把 VS Code 打开，然后 search 一下 Docker 镜像` 识别成 `帮我把悲哀扣打开然后事儿一下着可是景象`（2001 ms）——`vosk-model-small-cn-0.22` 只会按中文谐音硬转英文词。可选：换 `vosk-model-cn-0.22`（1.3 GB）/ 换 SenseVoice-Small（需新依赖）/ 保留小模型 + 词典纠错；`/api/asr` 换 worker 即可，client 零改动。**真机语音闭环仍待测**（手机与 PC 未同网、8787 入站被拦）。
- **通话模式（0.7.0，常驻监听 + 插话打断）**：`voice/RemoteSpeechInput.kt` → `voice/CallSpeechInput.kt`，一个 `AudioRecord` 常驻到挂断，capture 线程按句切分、单线程池跑 `/api/asr`（识别不再挡住继续听）；新增 `SpeechStarted` 事件驱动打断——朗读中停 `MediaPlayer`、思考中撤 `chatJob`，随后这句话当新输入。防自打断三层：`VOICE_COMMUNICATION`+AEC（理想）/ 朗读期阈值抬到 `max(噪声峰值×3.5, 700)` 且连续 320 ms / 朗读期起步却没被判为人声的片段直接丢弃。**实测**：该 ROM 的 `VOICE_COMMUNICATION` 返回全 0 采样，只能用 `VOICE_RECOGNITION`，平台 AEC 拿不到；VAD 首发沿用「RMS > 350」导致完全没反应（说话峰值仅 220），改帧内峰值 + 噪声底最小值标定后阈值收敛到 ~216。APK `26,441,113 B`（25.22 MiB）/ `versionCode=7` / `0.7.0`，`compileSdk` 随本机 SDK 变更提到 35。**真机通话闭环与打断验收待测**。
- **仓库尚未首次提交**：`main` 只有 `64f8bc9 --story=初始化仓库`，`plugin/`、`docs/`、`android/app/` 等仍为未跟踪状态。

## 预留任务 T1：prompt / 角色定义注入优化（省 token）

> 用户 2026-11-06 提出，**已落地**（代码层 + 离线断言 + 线上注册验收）；端到端模型回复验收待 `turn/end.reason.kind=blocked` 解除。优先级：排在阶段 1 语音链路之前——每轮都在重复烧 token，越早改越省。

**原问题（已解决）**：`plugin/lib/state.js` 的 `promptOf()` 把 `[角色定义] + [长期记忆] 最近 20 条 + [本轮消息]` 拼成一条用户消息交给 `controller.prompt()`。后果：每轮重复发送角色与 20 条记忆；这些前缀写进会话历史、越滚越长；用户消息被 `[角色定义]` 污染；按 token 计费等于每轮多付一次前缀钱（即便命中 prompt cache 也只是打折）。

**目标**：至少一个会话只注入一次；`controller.prompt()` 只发用户原文；改角色、加记忆后仍能在后续轮次生效；不回归会话复用与「青柠」式跨会话记忆。

**落地方案**（只改 `plugin/lib/chat.js` + `public/admin.html`，热生效、无需重载 Desktop；细节见 `HANDOFF.md`「关键实证」）：

1. `ensurePromptSection()` 用 `ctx.get('systemPrompt').section(...)` **注册一次** `dsh-channel:context`：角色定义每次组装动态读 `state.json`（改角色下一轮生效）；`text` 按 `assembly.agent.id` 只对专用会话返回内容，其他会话为空；`interpolate:false` 防记忆里的 `{{…}}` 被当变量解析。拿不到服务时用 `ctx.inject(['systemPrompt'], …)` 兜底，仍失败才退回 `promptOf()` 旧行为。
2. `loadMemory()` 把最近 20 条记忆做成**会话级快照**：本进程每轮 `appendTurn` 追加的两条已在会话历史里、不算变化；只有外部编辑 `memory.log` / 新会话 / 重启才重新载入，保证 system prompt 稳定、前缀缓存可复用。
3. `controller.prompt()` 只发用户原文；`SECTION_VERSION` 递增才 dispose + 重注册 section（热加载替换闭包）。`/api/health` 的 `probe.promptSection` / `memoryLoads` 可直接观测。

**禁止 / 坑**：官方明确「Do not listen to `system-prompt/assemble` to add or remove tools or text」；`agent/request` 监听器不能改 request messages；`ctx.systemPrompt` 属性访问会被 cordis 拒绝（必须 `ctx.get` / `ctx.inject`）；`agent.inject()` 不唤醒 agent（本次未用，故不依赖它）。

**验收状态**：

- 已验证（离线断言 + 线上）：同一会话连续 N 轮只发用户原文、前缀不随轮次增长；会话历史不再新增 `[角色定义]/[长期记忆]` 前缀（线上最后一次 `agent/inbox/spliced` 已不含前缀）；改角色下一轮生效；外部编辑 / 新会话 / 重启重载快照，本轮追加不重载；重启复用 `sessionId` 不重复注入；`backend=dsh` 不回归（`probe.promptSection=system`、`promptSectionError` 空）。
- 待验证（模型链路恢复后）：让模型回复一句话，确认角色定义生效、能回忆「青柠」式跨会话记忆，并对比改造前后请求 prompt token。

## 2. Android 应用（插件 client 端）

- [ ] 独立 Android client APK：连插件 WS，麦克风采集、音频播放，按 `emotion` 查本地映射表展示 gif。
- [ ] 复用阶段 1 协议与同一个插件实例，验证「换 client 不改插件」。
- [ ] 效果实测：首字延迟、音频连续性、形象帧率、后台/锁屏保活、功耗。
- [ ] 抽出可移植 client 层：音频编解码、状态机、`emotion → 动画 key` 映射表与协议编解码，供阶段 3 复用。
- [ ] 验收：与阶段 1 两端表现一致；替换资源包与映射表即可换形象，**server 零改动**；记录实测数据。

## 3. ESP32（从零实现固件，Waveshare ESP32-S3-Touch-AMOLED-1.75C）

- [ ] 基线确认：出厂 `firmware/ESP32-S3-Touch-AMOLED-1.75C_FactoryOnly_20260114.bin` 为合并镜像、**无源码**，不能在其上修改。从零写的是应用与编排，不是驱动；可复用源码：板级组件 `waveshare/esp32_s3_touch_amoled_1_75c`、`examples/esp-idf/02_lvgl_demo_v9`（LVGL 9）、`03_esp-brookesia`（UI 框架与 gif/png 资源）、`05_Spec_Analyzer`（ES7210）、Arduino `06_ES7210` / `07_ES8311`（音频）；协议实现可参考 `xiaozhi-esp32/`（MIT，工具链不同，不直接混用）。出厂 bin 仅用于恢复/对比。
- [ ] 工具链：ESP-IDF v6.0.2 或 v5.5.5（该仓库示例支持版本，非 `xiaozhi-esp32` 的 release/v6.1）。
- [ ] 按阶段 2 的 client 层契约用 C++ 重写：WS client、音频编解码、状态机、`emotion` 映射、重连与 PIN/token。
- [ ] 音频：ES7210 双麦输入 16 kHz、ES8311 输出 24 kHz；唤醒、AEC、打断。
- [ ] 形象：466×466 QSPI AMOLED（CO5300）+ LVGL 9，按 `emotion → 动画 key` 映射表加载外部资源转板载帧/gif，绑定 `llm.emotion` 事件（语义对齐 `SetEmotion()`）。
- [ ] 交互：CST9217 触摸（按键说话 / 确认 PIN）、QMI8658 IMU、AXP2101 电量与电源。
- [ ] 验收：同一插件实例、同一会话下语音双向 + 情绪驱动形象 + 30 min 稳定；换资源包不改 server；记录帧率与功耗。

> 资源参考：`dsh-Go/docs/assets/` 有 32 个桌宠 gif（4 状态 × 2 形象 × 中英 × 明暗），336×352、合计约 2.2 MB、单文件最大 114 KB；只带一套状态约 0.4–0.8 MB，32 MB Flash 可容纳，需转 LVGL 帧/解码并控制 PSRAM 帧缓冲。`dsh-Go/app/assets/pets/*/pack.json` 的 `animations` 是现成的「动画 key → 帧序列」映射表格式，可借鉴其结构而非素材。素材本身**不可随产品分发**：`dsh-Go/ASSETS.md:3,19` 明确品牌与美术素材不在 AGPL-3.0 范围内，仅限构建/测试与本仓库展示。

## 待决

- 情绪判定来源：模型结构化输出 vs server 规则映射（首版规则兜底 `neutral`）。
- 情绪枚举集合与资源方对齐；未知值回退、`state` 与 `emotion` 叠加优先级。
- 形象资源交付契约：尺寸、帧率、编码、动画 key 命名（由资源方提供）。
- TTS provider（**已定：插件端 `/api/tts` = edge-tts 在线神经语音，三端共用**；Android 0.4.0 删系统 TTS、0.5.0 连 client 侧音色参数也删，只剩服务端 `DSH_TTS_VOICE`；代价是 PC 必须联网、首句约 2 s、无流式）；ASR 位置（已定：**PC 侧**，APK 不带模型，代价是需要 PC 在线、无流式）。
- 阶段 3 选 ESP-IDF 还是 Arduino 示例作为驱动复用基线。
- T1 已决：角色/记忆统一走 `systemPrompt.section`（未用 `agent.inject`）；记忆是会话级快照，只在外部编辑 / 新会话 / 重启时重载，本轮对话靠会话历史。
- Android 语音链路已定：**手机采集 + VAD 断句 + 插件端 ASR + 插件端 TTS（本机只播 mp3）**（APK 从 116.06 MiB 降到 25.21 MiB）。发现方式仍是 /24 端口扫描（跨网段 / AP 隔离会失败，保留手动填地址），mDNS 未做。
- ASR 中英混输：`vosk-model-small-cn-0.22` 只认中文（实测见上），待定换大模型 / SenseVoice / 词典纠错；纯中文可用，故不阻塞真机闭环。
