# TODO — 插件 → Android Client → ESP32

实施顺序以本文件为准；`docs/technical-proposal.md` 保留方案、协议与验收标准，项目介绍与参考仓库见 `README.md`，当前进度与踩坑见 `HANDOFF.md`。三阶段串行，上一阶段验收通过再进入下一阶段。

实现目录：`plugin/`（插件）、`android/`（Android client）、`esp32/`（ESP32 client）；上游检出目录仅本地参考，不随仓库提交。

## 当前状态（2026-11-12，0.11.0）

**已经能用的**：

- **一条 WS 长连接**（`ws://<host>:8787/xiaozhi/v1/`，**完全照抄小智协议**）：上行 PCM/Opus → 服务端识别 → DSH 会话 → 逐句 TTS → 60 ms PCM/Opus 帧下行，`stt` / `llm.emotion` / `tts start|sentence_start|stop` 全在同一连接。HTTP 三条路由保留给演示页与脚本调试。
- **流式输出**：服务端按句合成边收边发，手机 `AudioTrack` 边收边播；字幕在 `sentence_start` 时切句，**字随播放头长出来**（`captionChars` ← `playbackHeadPosition` 落在哪句、句内比例），不再是「先打完字再等声音」。
- **朗读不打断**：说话时照常上行，服务端在连接内串行排队，当前音频播完再播下一句回复。
- 形象只有内置 **Deepy**（素材随 APK，首次启动复制到 `filesDir`）；插件管理页按设备绑角色、TTS 音色按角色下发。
- 插件侧新增 `POST /xiaozhi/ota/`：未改固件的小智设备把 OTA 指过来即可直连（缺硬件，未真机验证）。

**验证到哪一步**：插件侧协议用 `node plugin/test/ws_test.mjs`（桩 ASR/TTS）与 `node plugin/test/ws_full.mjs`（真 Vosk + 真 edge-tts + 真 Opus）全绿；
Android 0.11.0 已装机，真机实测已跑到「音频上行 → ASR → DSH 正常出回复」，只在 TTS 环节报 `合成失败：TTS 超时（30s）`——
已定位并修复（30 s 硬超时 + 迟到音频导致帧流失步，见 `HANDOFF.md` 末节）。
**闭环待复测**：需在 Desktop **重载插件**（WS 路由在 `lib/index.js`、`tts.js` 修复在模块级代码里，都不是请求级热加载）；
重载后直接打 `GET /api/tts?text=你好` 应 12~26 s 返回 mp3，而不是 30 s → 503。`health.tts.requests=0 / lastMs=null` 说明当前跑的还是旧版。

**已知未做**（按优先级）：

1. 真机通话闭环验收（先在 Desktop 重载插件）。
2. 小智 ESP32 设备真机（协议按固件源码对齐，`/xiaozhi/ota/` 已就绪）。
3. 模型侧 token 流式：DSH 当前只发整段 `assistant/message`，「边收边播」只能做到「按句合成流水线」；等引擎支持增量事件还能更早出声。
4. 多设备仍共用一个 DSH 会话（只做到按设备选角色）。
5. edge-tts 单次请求抖动大（实测 2.4~17 s，worker 重试最坏 ~2×），句间可能空一下；已放宽超时到 90 s、短句合并到 16 字减少请求，要再快可考虑合成并发 2（`tts.synthesize` 目前仍是 Node 串行队列）或本地 TTS 兜底。

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
- 插件零改动（chat 侧）：`/api/health` 发现 + `/api/chat` NDJSON + PIN 全部沿用；情绪 gif 只参考 `dsh-Go/docs/assets` 的 4 状态命名，素材当时按 `ASSETS.md:3,19` 不复制、不分发（0.9.0 起改为随私有仓库提交，仅本机 / 私有仓库使用）。
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
- **音色管理补齐（服务端）**：`state.json` 新增 `tts {voice, rate, pitch}`（`lib/state.js` 校验归一化），`lib/tts.js` 的 `ttsSettings()` 按「环境变量 > state > 默认」取值，`/api/tts` 不带参数时用它；管理页 `/admin` 新增「语音音色」卡片（下拉 + 刷新在线音色 + 语速/音调 + 试听 + 保存），`/api/dsh-voice-channel/state` GET/POST 带上 `tts`；演示页的音色下拉删除，只发文本。实测保存 Yunxi/+10%/−5Hz 后 `/api/tts` 返回 `x-tts-voice: zh-CN-YunxiNeural`，非法值自动归一化。
- **UI 产品化（0.6.0）**：App 设置页删掉全部技术说明与「语音能力」节，只剩地址 / 配对码 / 搜索 / 连接状态；`connected`（填过地址）改为 `reachable`（健康检查通过才亮）；错误提示全部人话。管理页改标题「小鲸鱼」、长句收进折叠「运行状态」、卡片与 hint 精简；演示页显示中文心情与简单状态。技术细节迁入新建的 `docs/app-client.md` 与 `docs/plugin-server.md`。APK `26,385,163 B`（25.16 MiB）/ `versionCode=6` / `0.6.0`，装包截图确认。
- **ASR 中英混输不可用（2026-11-07 实测，样本见 `plugin/testdata/`）**：纯中文句 `帮我把这个文件打开，然后查一下镜像` 全对（328 ms）；混说句 `帮我把 VS Code 打开，然后 search 一下 Docker 镜像` 识别成 `帮我把悲哀扣打开然后事儿一下着可是景象`（2001 ms）——`vosk-model-small-cn-0.22` 只会按中文谐音硬转英文词。可选：换 `vosk-model-cn-0.22`（1.3 GB）/ 换 SenseVoice-Small（需新依赖）/ 保留小模型 + 词典纠错；`/api/asr` 换 worker 即可，client 零改动。**真机语音闭环仍待测**（手机与 PC 未同网、8787 入站被拦）。
- **通话模式（0.7.0，常驻监听 + 插话打断）**：`voice/RemoteSpeechInput.kt` → `voice/CallSpeechInput.kt`，一个 `AudioRecord` 常驻到挂断，capture 线程按句切分、单线程池跑 `/api/asr`（识别不再挡住继续听）；新增 `SpeechStarted` 事件驱动打断——朗读中停 `MediaPlayer`、思考中撤 `chatJob`，随后这句话当新输入。防自打断三层：`VOICE_COMMUNICATION`+AEC（理想）/ 朗读期阈值抬到 `max(噪声峰值×3.5, 700)` 且连续 320 ms / 朗读期起步却没被判为人声的片段直接丢弃。**实测**：该 ROM 的 `VOICE_COMMUNICATION` 返回全 0 采样，只能用 `VOICE_RECOGNITION`，平台 AEC 拿不到；VAD 首发沿用「RMS > 350」导致完全没反应（说话峰值仅 220），改帧内峰值 + 噪声底最小值标定后阈值收敛到 ~216。APK `26,441,113 B`（25.22 MiB）/ `versionCode=7` / `0.7.0`，`compileSdk` 随本机 SDK 变更提到 35。**真机通话闭环与打断验收待测**。
- **形象包（0.8.0）**：主页形象改成 `assets/packs/<id>/pack.json` 资源包（`states` + `emotions` + `fallback`），按 emotion 播 gif，缺素材回退内置程序化鲸鱼；设置页「形象」可切换，选择存 `SettingsStore.packId`。首包 `deepy` 用两张参考图（`notification` / `sleeping`），按既有许可结论只在本机用、gif 已 gitignore，`pack.json` 入库。踩坑：`Box + clickable` 在 `horizontalScroll` 里点不动，换 `Surface(onClick)` + `FlowRow`。APK `26,427,140 B`（25.21 MiB）/ `versionCode=8`。
- **形象动起来（0.9.0）**：主页形象从「静图 + 呼吸缩放」改成真·多帧动画。三处根因：① 0.8.0 的两个 gif 只有 1 帧（`ImageAnimator` + 手写解析双验），换成 `dsh-Go/docs/assets` 的 deepy 四状态动图（32/52/64/48 帧，336×352）；② `AnimatedImageDrawable` 在解码阶段 `start()` 无效（还没挂到 ImageView、无 callback），改为 `setImageDrawable` 后再 `start()`；③ `packId` 默认空串导致新装用户一直看内置鲸鱼，新增 `packChosen` 默认选第一个包。渲染层去掉与帧动画打架的缩放/浮动，只留 180 ms 淡入。素材改为随仓库提交（`.gitignore` 放开 `assets/packs/**/*.gif`），许可仍是私有仓库内使用。真机连续截图确认动画在跑。APK `26,851,993 B`（25.61 MiB）/ `versionCode=9` / `0.9.0`。
- **形象库重做 + 设备绑定角色（0.10.1）**：内置形象从程序化鲸鱼换成线条 **Eva**（圆角头框 + 可弯可斜的眼睛 + 脖子线，按情绪改眼形，说话浮出声波，零素材依赖）；素材完全外置（APK 不带素材），只能导入 zip，落到 `filesDir` 后可改名 / 覆盖导入 / 删除（删除前有确认弹窗）；样本包 `assets/deepy.zip` 推到手机 `Download/` 手动导入；deepy 的卡片底板用 `android/tools/strip-avatar-bg.py` 在素材层面抠成透明，输出 4 个透明底动画 WebP（约 66 KB，原 GIF 374 KB）；页面背景取素材首帧边缘均值色做纯色，素材边界消失（**别在这条路径加渐变**）；播放从 `ImageView` 换成 Compose `Canvas` 直绘 + 自建 `Drawable.Callback` 推帧（View 层绘制在 Compose 之上，羽化/同色融合盖不住）。插件侧新增 `bindings`（clientId → roleId），`/api/chat` 按 `device-id` 选角色定义，管理页「在线设备」每行一个角色下拉、改动即存。APK `26,515,676 B`（25.29 MiB）/ `versionCode=11` / `0.10.1`。**限制**：所有设备仍共用一个 DSH 会话，只做到角色按设备区分。
- **形象收敛成「屏幕即脸」（2026-11-12 迭代，未升版本号）**：Eva 去掉头框、脖子线、腮红、嘴巴与背后的渐变光晕，**只留一双发光的眼睛**——`bar()` 用四层同形叠加冒充 blur（暗蓝辉光 → 边缘光 → 主亮蓝 → 淡白内芯），`arcEye()` 画笑眼，`cross()` 画 error；情绪只靠眼形（neutral 圆润光斑 / happy 上弯弧 / sad ±11° / angry ±16° / surprised 短而高 / thinking 一眼收窄一眼偏侧 / sleepy 压扁 / error 发光 X）。主页把形象从 `Modifier.size(260.dp)` 改成根 `Box` 的第一层 `fillMaxSize()`（字幕与通话按钮 `bottom 152dp` 浮在脸上），尺寸按屏宽比例：眼距 ±0.205w、眼长 0.25w、眼高 0.10w、眼睛在 0.40h。**教训**：比例看着「紧凑」时先查容器尺寸，别在绘制参数里找。素材目录同时从 `android/samples/` 迁到仓库根 `assets/`。管理页角色角标/按钮 `使用中` → `默认角色`，设备下拉不再有「跟随使用中」。debug APK `26,691,658 B`（25.45 MiB）/ `versionCode=11` / `0.10.1`。
- **素材去文字 + 阶段驱动播放（同日）**：`assets/deepy/` 的 4 个动画 WebP 逐帧裁成 224×224（`x56 y40`），角色保留、底部中文说明整条裁掉，无损重编码后 66 KB → 54 KB，`deepy.zip` 重打包 43 KB；素材包播哪一帧改由**通话阶段**决定（待机 sleeping / 通话中 happy / 思考 typing / 说完 notification 2 s 再回 happy），内置 Eva 仍按 `emotion`；修掉真机两个 bug——双线性过滤导致糊（`setFilterBitmap(false)`）、整屏 `saveLayer` 羽化拖死主线程导致麦克风点不动（删羽化层、素材缩到 86% 屏宽并上移 0.06h）。新增 `docs/dev-guide.md`（Android / 插件 / ESP32 留白 / 素材处理）。追加修掉「点麦克风要等约 1 s」：`CallSpeechInput.start()/stop()` 的音频模式切换、`AudioRecord` 与 `probeHasAudio()`（≤500 ms）全在主线程，改为 ViewModel 单线程 `speechWorker` 串行执行、UI 先切状态；首页同时删掉与底部按钮重复的那行状态文案。
- **耳机路由 / 两行字幕 / 角色音色 / 健康检查**：通话按 蓝牙→有线→USB→外放 选 `CommunicationDevice`（老系统退回 `isSpeakerphoneOn`）并监听插拔，修掉「戴耳机还是外放」；字幕只保留最新两行（标点处截断 + `maxLines=2`）；插件把音色/语速/音调下放到角色（`roles[].{voice,rate,pitch}`，空的跟随全局），`/api/tts` 按 `device-id` 解析、管理页删掉独立「声音」卡片；管理页接口统一走 `/dsh-voice-channel/{health,voices,tts}`，删掉独立的「运行状态」卡片，改在「在线设备」里放「检查运行状态」按钮——原来 `/api/health` 打到引擎路由回纯文本 `not found`，`res.json()` 抛 `Unexpected token 'o'`。
- **仓库已推送**：`main` 现包含 `plugin/`、`docs/`、`android/app/` 与 `assets/`（素材样本，不进 APK），最近一次推送是 android 0.9.0（此前只有 `64f8bc9 --story=初始化仓库` 与一次文档提交）；0.10.1 尚在本地。

## 预留任务 T1：prompt / 角色定义注入优化（省 token）

> 用户 2026-11-06 提出，**已落地**（代码层 + 离线断言 + 线上注册验收）；端到端模型回复验收待 `turn/end.reason.kind=blocked` 解除。优先级：排在阶段 1 语音链路之前——每轮都在重复烧 token，越早改越省。

**原问题（已解决）**：`plugin/lib/state.js` 的 `promptOf()` 把 `[角色定义] + [长期记忆] 最近 20 条 + [本轮消息]` 拼成一条用户消息交给 `controller.prompt()`。后果：每轮重复发送角色与 20 条记忆；这些前缀写进会话历史、越滚越长；用户消息被 `[角色定义]` 污染；按 token 计费等于每轮多付一次前缀钱（即便命中 prompt cache 也只是打折）。

**目标**：至少一个会话只注入一次；`controller.prompt()` 只发用户原文；改角色、加记忆后仍能在后续轮次生效；不回归会话复用与「青柠」式跨会话记忆。

**落地方案**（只改 `plugin/lib/chat.js` + `public/admin.html`，热生效、无需重载 Desktop；细节见 `HANDOFF.md`「关键实证」）：

1. `ensurePromptSection()` 用 `ctx.get('systemPrompt').section(...)` **注册一次** `dsh-voice-channel:context`：角色定义每次组装动态读 `state.json`（改角色下一轮生效）；`text` 按 `assembly.agent.id` 只对专用会话返回内容，其他会话为空；`interpolate:false` 防记忆里的 `{{…}}` 被当变量解析。拿不到服务时用 `ctx.inject(['systemPrompt'], …)` 兜底，仍失败才退回 `promptOf()` 旧行为。
2. `loadMemory()` 把最近 20 条记忆做成**会话级快照**：本进程每轮 `appendTurn` 追加的两条已在会话历史里、不算变化；只有外部编辑 `memory.log` / 新会话 / 重启才重新载入，保证 system prompt 稳定、前缀缓存可复用。
3. `controller.prompt()` 只发用户原文；`SECTION_VERSION` 递增才 dispose + 重注册 section（热加载替换闭包）。`/api/health` 的 `probe.promptSection` / `memoryLoads` 可直接观测。

**禁止 / 坑**：官方明确「Do not listen to `system-prompt/assemble` to add or remove tools or text」；`agent/request` 监听器不能改 request messages；`ctx.systemPrompt` 属性访问会被 cordis 拒绝（必须 `ctx.get` / `ctx.inject`）；`agent.inject()` 不唤醒 agent（本次未用，故不依赖它）。

**验收状态**：

- 已验证（离线断言 + 线上）：同一会话连续 N 轮只发用户原文、前缀不随轮次增长；会话历史不再新增 `[角色定义]/[长期记忆]` 前缀（线上最后一次 `agent/inbox/spliced` 已不含前缀）；改角色下一轮生效；外部编辑 / 新会话 / 重启重载快照，本轮追加不重载；重启复用 `sessionId` 不重复注入；`backend=dsh` 不回归（`probe.promptSection=system`、`promptSectionError` 空）。
- 待验证（模型链路恢复后）：让模型回复一句话，确认角色定义生效、能回忆「青柠」式跨会话记忆，并对比改造前后请求 prompt token。

## T2 已完成：通信从 HTTP 换成 WebSocket（0.11.0）

> 结论先行：**没有自创协议，直接用 `xiaozhi-esp32` 的 WS 协议**（`hello` / `listen` / `stt` / `llm` / `tts` + 裸二进制音频）。
> 定稿文档：[`docs/ws-protocol.md`](docs/ws-protocol.md)（已重写为"已实现"版本）。

**实际落地**

| 项 | 结果 |
| --- | --- |
| 插件端通道 | `lib/ws.js` 手写 RFC6455（握手/文本/二进制/分片/ping-pong/close），`server.on('upgrade')` 挂 `/xiaozhi/v1`（`/ws` 同名）；`lib/xiaozhi.js` 实现协议会话 |
| 鉴权 | 握手头 `Authorization: Bearer <PIN>`，LAN 不通过直接 401（不会"连上再踢"）；本机 loopback 免 |
| 编解码 | 上行 Opus 16k（`opusscript`）、mp3→PCM（`mpg123-decoder`）；**没有守住零依赖**，改为懒加载 + `/api/health` 暴露 `codecs` |
| 断句 | ESP32 走 `mode=auto`（服务端 VAD，`lib/vad.js`）；Android 走 `mode=manual`（本地 VAD，避开无 AEC 的回声） |
| 流式下行 | 按句切分 → 每句合成完立刻 `sentence_start` + 60 ms 节奏推帧（合成与播放解耦，句间不留空） |
| 客户端 | `net/WsSession.kt` 单连接（`PluginClient` 只留探活，`AsrClient` / `TtsClient` / `TtsOutput` 删除） |
| 播放 | `voice/StreamingPlayer.kt`：`AudioTrack` MODE_STREAM 边收边播 + 字幕按 `playbackHeadPosition` 推进 |
| OTA | `POST /xiaozhi/ota/` 下发 `websocket.url/token/version`，小智设备免改固件 |
| 验收 | `plugin/test/ws_test.mjs`（桩）与 `plugin/test/ws_full.mjs`（真 Vosk + 真 edge-tts + 真 Opus）全绿；见 `docs/ws-protocol.md` §10 |

**没做的（当时设想过的）**

- 灰度开关 `SettingsStore.wsEnabled` 与 HTTP 回退：直接切了 WS，HTTP 路由只留给演示页/脚本，客户端不再双栈。
- ASR 增量上行：仍是「一句整段」，`listen stop` 才识别（Vosk 需要改增量解码）。
- 请求 `id` 多路分解：不需要了，同一连接内服务端串行。

<details>
<summary>当时的拆解与论证（保留备查）</summary>

## 预留任务 T2：通信从 HTTP 换成 WebSocket（**必做**）

> 2026-11-12 提出。现在三条链路各发一次 HTTP：`POST /api/asr`（整段 PCM）、`POST /api/chat`（NDJSON 流）、`POST /api/tts`（整段 mp3）。每次都要重新握手 + 带 PIN，打断只能靠取消请求，也无法做真正的流式；阶段 3 的 ESP32 还要再实现一遍同样的三条 HTTP。**越早换越省**，因为协议一旦定稿，PC / 手机 / ESP32 三端可以共用同一套。

**拆解与工作量**

| 项 | 内容 | 估时 |
| --- | --- | --- |
| 插件端通道 | `webServer.upgrades` 注册 WS（**保持零依赖**，手写 RFC6455 握手 + 帧解析约 150~200 行，或引入 `ws` 并接受多一个依赖）；首帧 `hello` 带 PIN + 协议版本，之后按 `id` 关联请求 | 0.5 天 |
| 客户端迁移 | OkHttp `WebSocket` 把 `PluginClient` / `AsrClient` / `TtsClient` 合并成一条连接；连接复用、断线重连、请求 id 与 generation 对齐 | 1 天 |
| 流式（收益主体） | ASR 边录边发（要改 `lib/asr_worker.py` 的 Vosk 增量解码）；TTS 边收边播（二进制音频帧 + `AudioTrack`，替换现在的「落 mp3 再 `MediaPlayer`」） | 1.5~2 天 |

合计：**只换通道约 1.5 天；含 ASR/TTS 流式约 3~4 天**（不含真机联调）。

**协议**：[`docs/ws-protocol.md`](docs/ws-protocol.md)（v1 草案）——连接与鉴权、`asr`/`chat`/`tts` 三类消息、二进制帧约定、心跳与重连、与 HTTP 的双栈关系、验收清单。开工前先把这份定稿，三端照它实现，别再各自发明。

**实施顺序（建议按此推进，每步都能单独回滚）**

1. **插件侧只加不改**：新增 `lib/ws.js`（手写 RFC6455 握手 + 帧解析 + 连接表），`index.js` 里 `server.on('upgrade')` 挂 `/ws`；业务流程仍调用现有 `chat.js` / `asr.js` / `tts.js`。**这一步需要重载插件**。
2. **双栈验收**：用 Node 脚本（临时目录，零依赖 `WebSocket` 客户端可手写或 `npm i ws`）跑通 hello → asr → chat → tts，与 HTTP 版结果逐字对比。
3. **客户端接入**：新增 `net/WsClient.kt`（OkHttp `WebSocket`，单连接 + 请求 `id` + 超时 + 退避重连），`SettingsStore.wsEnabled` 默认关；`PluginClient` / `AsrClient` / `TtsClient` 内部改为「开关打开走 WS、否则走 HTTP」，调用方不动。
4. **灰度**：设置页加一个隐藏开关（或长按版本号），真机跑通后再默认开；出问题一键退回 HTTP。
5. **再做流式**：ASR 增量上报（改 `asr_worker.py` 的 Vosk 分段解码）、TTS 边收边播（`AudioStreamBuffer` / `AudioTrack`），这一步才拿延迟收益。
6. **收尾**：HTTP 路由保留（管理页与演示页还在用），但 README / `docs/plugin-server.md` 的路由表标注「WS 优先、HTTP 兼容」。

**开工前先确认**

- 插件零依赖要不要守：手写 RFC6455 约 150~200 行（只处理文本/二进制、不分片续帧），换来 `package.json` 不变；引入 `ws` 省事但破了「零运行时依赖」的口径。
- 二进制帧不做多路分解的前提是「一连接同时只一路 ASR / 一路 TTS」，这点要在客户端强制（`busy` 报错）。
- WS 服务端连接是长驻的，改它必须重载插件；只有 `chat.js` 那类请求级模块能热加载。

**注意**

- WS 只解决连接复用与延迟，**不自动修好「音频乱」**；音频问题要先按播放属性 / 音轨路由单独排查（已给 `MediaPlayer` 指定 `USAGE_MEDIA` + `CONTENT_TYPE_SPEECH`）。
- 新增路由与协议字段属于 `lib/index.js` 级别改动，**需重载插件**才生效；`chat.js` 才是请求级热加载。
- PIN 从每请求头改为连接首帧鉴权，LAN 无 PIN 需 401 并断开，别退化成「连上就能发」。
- 阶段 3 的 ESP32 按同一协议实现，改动时同步更新 `docs/technical-proposal.md` 的协议章节与 `docs/plugin-server.md` 的路由表。

**验收**：一条连接内完成「说话 → 识别 → 回复 → 朗读」；朗读中插话能立刻停声且不重连；拔网/断线后自动重连并继续可用；PC 演示页与手机走同一协议，插件侧不再有每次请求的握手开销。

</details>

## T3 一并做完：兼容 `xiaozhi-esp32` 客户端（0.11.0）

T2 直接采用了小智协议，所以 T3 的主体（Opus 编解码、服务端 VAD、OTA 下发、二进制 v1/v2/v3 拆帧、
`listen auto/stop`、`abort`、`tts` 三态）已在同一次改动里落地，见 `docs/ws-protocol.md`。
剩余：**没有硬件做真机验证**；设备侧 OTA 地址（`CONFIG_OTA_URL` / NVS `wifi/ota_url`）要指向本机才能自动接入。

<details>
<summary>当时的 T3 评估（保留备查）</summary>

## 预留任务 T3：兼容 `xiaozhi-esp32` 客户端（**建议做，排在 T2 之后**）

> 2026-11-12 评估。参考代码在本仓库 `xiaozhi-esp32/`（MIT，仅作协议与客户端行为依据，工具链不混用）。结论：**可以做到「不改设备固件、语音对话完整可用」**，代价是实现一层适配；难点不在 WS，而在 **Opus 编解码 + 服务端断句 + 会话时序**。做完阶段 3 的 ESP32 可以完全省掉协议实现。

**设备真正依赖的（代码实证，别再猜）**

| 环节 | 要求 | 出处 |
| --- | --- | --- |
| 握手头 | `Authorization: Bearer <token>`（可空）、`Protocol-Version`、`Device-Id`（MAC）、`Client-Id`（UUID） | `xiaozhi-esp32/main/protocols/websocket_protocol.cc:97-106` |
| 握手时序 | 设备连上即发 `hello`，**10 s 内**收不到服务端 `hello` 判失败并断开 | 同上 `:187-201` |
| 服务端 hello | 必须含 `"transport":"websocket"`；`audio_params.sample_rate` / `frame_duration` 决定下行播放参数（可 24000 / 60） | 同上 `:236-263` |
| 上行音频 | Opus 16 kHz / 单声道 / 60 ms 一帧；协议 v1 裸 Opus，v2/v3 带 `BinaryProtocol2/3` 头 | 同上 `:24-60`，`docs/websocket_zh.md:103-131` |
| 下行音频 | 二进制帧一律按 **Opus** 解码，采样率取服务端 hello，不一致由设备重采样 | `docs/websocket_zh.md:297-312` |
| 上行 JSON | `listen`(start/stop/detect)、`abort`(reason) | 同上 `:162-202` |
| 下行 JSON | `stt`（用户字幕）、`llm.emotion`（表情）、`tts`(start / sentence_start+text / stop) | `application.cc:644-701` |
| 自动模式断句 | `listen start` 后设备持续推流，**没有「说完」标记**，断句必须服务端做 | `application.cc:1164-1180` |
| 启动 OTA | `POST /xiaozhi/ota/`（默认 `https://api.tenclass.net/xiaozhi/ota/`，可被 NVS `wifi/ota_url` 覆盖）；失败会重试 10 次（10→512 s 退避），必须返回可解析 JSON | `ota.cc:56-63`、`application.cc:467-554` |
| OTA 可下发 WS 配置 | 响应里的 `"websocket":{url,token,version}` 会写进设备 NVS —— **不改固件即可把设备指向我们的服务** | `ota.cc:180-199` |
| 可忽略 | `mcp` / `glyph_push` / `system.reboot` / `custom` / `alert`，不回不影响对话 | `application.cc:702-731` |

**我们的差距**

1. ASR 入口是「整段 PCM `POST /api/asr`」，设备是 Opus 流 → 需 Opus 解码 + 服务端 VAD 断句（手机端 `CallSpeechInput` 的能量 VAD 移植成 JS 即可）。
2. TTS 出口是 edge-tts 的 **mp3**，设备只认 Opus → 需 mp3→PCM→Opus(24 k / 60 ms) 重编码。
3. **必须破「零依赖」**：Opus 编解码选 `opusscript`（纯 JS，MIT）或 `@discordjs/opus`（native 预编译）；mp3 解码选 `mpg123-decoder`（wasm）或直接 shell 出 ffmpeg。选定后要改 `package.json` 与文档里「零运行时依赖」的口径。
4. WS 监听不用另起服务：插件自己的 8787 就是 `http.createServer`（`plugin/lib/index.js:createServer`），加 `server.on('upgrade')` 即可，不依赖 DSH 的 `webServer`。

**工作量**

| 项 | 估时 |
| --- | --- |
| WS 服务器 + xiaozhi 二进制协议 v1/v2/v3 + hello/时序/心跳 | 1 天 |
| Opus 上下行打通（16 k 解码 / 24 k 编码 / 60 ms 分帧 / 重采样） | 1 天 |
| 服务端 VAD 断句（JS 版） | 0.5 天 |
| TTS 链路 mp3 → PCM → Opus | 0.5~1 天 |
| OTA 空实现（返回 `{}` 或带 `websocket` 配置）+ 真机联调 | 1 天 |

合计约 **4 天**；若 T2 的 WS 基础设施已就绪，兼容层增量约 **2.5~3 天**。

**注意**

- 设备出厂默认连官方云（OTA 与 WS 都指向 tenclass），自建必须先把 `wifi/ota_url`（NVS 或编译期 `CONFIG_OTA_URL`）指到我们这里，否则设备会被官方响应重新指走。
- 固件版本差异：`version` 只存在设备侧 NVS（服务端 hello 不下发），要按 v1/v2/v3 都能解析；`glyph_push`/`text_font` 直接忽略。
- 多设备仍共用一个 DSH 会话，按 `Device-Id`（MAC）走现有 `bindings` 选角色。
- 与 T2 共用同一套 WS / 帧 / 重采样基础设施，别写成两套。

**验收**：一台未改固件的 xiaozhi 设备（固件把 OTA 指到本机）能 说一句 → 设备显示 `stt` 字幕 → 听到朗读并看到 `llm.emotion` 表情 → 自动回到聆听；`abort`（唤醒/打断）能立刻停声；长挂 30 min 不断连。

</details>

## 2. Android 应用（插件 client 端）

- [x] 独立 Android client APK：连插件 WS，麦克风采集、音频播放，按 `emotion` 查本地映射表展示 gif。
- [x] 复用阶段 1 协议与同一个插件实例，验证「换 client 不改插件」（WS 一套协议同时喂手机与 ESP32）。
- [ ] 效果实测：首字延迟、音频连续性、形象帧率、后台/锁屏保活、功耗。（0.11.0 真机闭环待测）
- [x] 抽出可移植 client 层：`net/WsSession.kt`（协议编解码 + 重连）、`voice/StreamingPlayer.kt`（音频输出 + 字幕时序），阶段 3 可直接照着写 C++。
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
