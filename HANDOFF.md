# HANDOFF — dsh-channel 插件接入 DSH Desktop 会话

> 2026-11-07 收束版。新会话先读本文件末尾「2026-11-07 收尾」与「下一步」；「关键实证」里的 API 结论别重新调研。
> 命名沿革：`dsh-esp32` → `dsh-bridge` → **`dsh-channel`**；目录 `server/` → `plugin/`。历史条目里的旧名按当前名理解。`plugin/data/state.json` 当前 `sessionId=dsh-channel-1793974315196`（已随改名更新，会话历史会重来）。
> 用户偏好：中文、简洁专业、不科普；最简方案；无安装脚本（Desktop「添加插件」选本地目录）；不用 CLI 管 `desktop` profile；讨厌反复重启 Desktop；**只改字面名字时不要顺手处理存量数据**。

## 目标与环境

`plugin/` 装入 DSH Desktop（Electron），插件自管一个专用 DSH session：client 输入进会话、模型回复从事件收回；插件只返回 `emotion` 枚举，client 自行映射 gif。要真实模型回复，不是 demo 回显。

- 工作区 `e:/File/Repo/dsh-android`；插件目录 `e:\File\Repo\dsh-android\plugin`；服务 `0.0.0.0:8787`；profile `~/.dsh/profiles/desktop`（Electron 独占，`dsh plugin --profile desktop add` 会报 managed exclusively，不可用）。
- Node 22，插件零运行时依赖、ESM；Desktop 主程序 `D:\Software\DSH\DeepSeek Harness.exe`。
- **生效方式**：`lib/chat.js` 每次请求动态 import、`public/*.html` 每次请求读文件 → 热生效；`lib/index.js`（`apply()` 注册同源路由）、`lib/state.js`、`lib/client.js`（Web 启动时经 `window.__ModuleLoader__` 加载）→ 改动需重载 Desktop / 刷新 Web 页面。
- `webPort` 每次启动变化，从 `/api/health` 的 `probe.webPort` 取，别写死。

## 仓库现状

| 路径 | 内容 |
| --- | --- |
| `README.md` | 项目简介、参考仓库、开源合规 |
| `docs/technical-proposal.md` | 技术方案（选型/架构/部署/验证/许可） |
| `TODO.md` | 三阶段实施 + 情绪契约 + **预留任务 T1（prompt 注入优化）** |
| `HANDOFF.md` | 本文件 |
| `plugin/` | 插件实现 |
| `android/`、`esp32/`、`archive/` | 后续 client / 归档旧方向 |

四个外部检出（`dsh-Go/`、`dsh-mobile-apk/`、`xiaozhi-esp32/`、`ESP32-S3-Touch-AMOLED-1.75C/`）仅本地参考，不提交。

## 插件结构

| 文件 | 作用 |
| --- | --- |
| `plugin/package.json` | `engines.dsh`、`dsh.bundle.patch`、`dsh.client.platform=web`、无依赖 |
| `plugin/cordis.patch.yml` | `- insert: [{id: dsh-channel, name: dsh-channel}]` |
| `plugin/public/index.html` | 演示页：麦克风、NDJSON 流式、`emotion→形象`、speechSynthesis、状态胶囊、持久化 `device-id` + PIN 输入 |
| `plugin/public/admin.html` | 管理页：角色定义、`memory.log` 路径/内联查看、可改 PIN、已连接 client（5 s 刷新）；运行状态并入顶部描述行 |
| `plugin/data/state.json` | `{persona, sessionId, pin}` |
| `plugin/data/memory.log` | 长期记忆唯一存储源：JSONL `{time,role,text}`，**全量保留**，按会话载入最近 20 条快照（`INJECT_MEMORY=20`） |
| `plugin/lib/state.js` | 状态读写、`validPin`/`randomPin`、`contextOf`（生成角色/记忆文本，供 system prompt 使用）、`promptOf`（仅降级时拼用户消息）、`appendTurn` |
| `plugin/lib/index.js` | 8787 服务；`/api/health`、`/api/chat`（LAN 需 PIN、记录 client）、`detectEmotion`；`apply(ctx)` 注入服务 + 事件总线 + 同源路由；页面每次请求读文件 |
| `plugin/lib/chat.js` | 专用会话：建/复用会话、`controller.prompt` 只发用户原文、事件/snapshot 取回复；`ensurePromptSection` 注册一次 system prompt section、`loadMemory` 维护会话级记忆快照；`ensureModel` 切账号路由、`ensureWorkspace` 挂 `dsh-channel` 工作区；每轮写回记忆 |
| `plugin/lib/client.js` | Web 前端插件：侧边栏 `dsh-channel-charactor` + 双向箭头图标 + `main` 面板，iframe 指向同源 `/dsh-channel/admin` |

`health` 输出 `backend`、`lastError`、`sessionId`、`lastVia`、`probe`（服务方法名、`webPort`、`modelSelected`、`workspaceTitle`、`workspaceAttached`）。

## 关键实证（别再猜 API）

**会话链路**

- `controller.prompt(request, signal)`（第二参数是 `AbortSignal`）；`controller.create({sessionId,cwd})` 只建存储记录，需 `sessions/agents.enter` 挂 live agent；`controller.selectModel({sessionId,provider,model})` 切路由；`sessions.get(sid).snapshotEvents()` 取事件。实测链路全靠注入的服务对象，`rpcViaHttp` 是死代码（本机 `fetch failed`）。
- 回复字段：`assistant/message.data.message.content[].text`（完整）与 `assistant/attempt.data.stream[]` 的 `text-chunks` / `{type:'chunk',chunk:{type:'text-delta',text}}`（可能是增量批次或累积快照，`mergeDelta` 去重）；错误在 `chunk.chunk.type='finish'` 的 `reason.failure` 或 `turn/end.data.reason.error`。
- 默认模型是 `deepseek-official`（无 key → `MISSING_CREDENTIAL`）；`controller.modelCatalog().routableProviders` 才有凭据，`ensureModel` 切到 `deepseek-account/deepseek-flash` 即通，无需 API Key。
- `ctx.get('workspaceRegistry')` 可直接取（不必改 `index.js`）：`list/create`，workspace 有 `title/attachSession/sessionIds`；只给 `cwd` 建的会话落「未分组」，需 `attachSession`。
- **官方提示词注入方式（T1 已据此落地）**：`ctx.get('systemPrompt').section({name,order,text,interpolate})` 注册一次（返回 disposer）；`text(assembly)` 的 `assembly.agent` / `assembly.scope` 由 `assembleContextFor(agent)` 提供，可按 `agent.id` 只对专用会话返回内容；`interpolate:false` 必加，否则记忆里的 `{{…}}` 会被当变量解析。system prompt 文本稳定时前缀缓存可复用，变化才追加 in-history system 节点 → 记忆快照必须稳定，角色定义才每轮动态读。`agent.inject()` 记为 `agent/inbox/spliced`、进入下一个 step，**不会唤醒 agent**（本次未用）；禁止监听 `system-prompt/assemble` 增删文本；`agent/request` 监听器不能改 request messages。`SystemMessage` 是 role=system / source=system-prompt，`SystemPromptUpdate='in-history'`。
- **cordis 取服务**：`ctx.systemPrompt` 属性访问会抛 `cannot get property "systemPrompt" without inject`；要么 `ctx.get('systemPrompt')` 直取（本次用），要么 `ctx.inject(['systemPrompt'], scoped => scoped.systemPrompt.section(...))`。`ctx.get('workspaceRegistry')` 同理。

**Web 路由与前端**

- `ctx.webServer.register({kind:'exact'|'prefix', path, handler})`，handler `(req,res)` 支持 async；`WebServer.match()` 先查 exact，再按 `pathname !== prefix && !pathname.startsWith(prefix + '/')` 最长前缀匹配 → **prefix 不能带尾斜杠**（`'/dsh-channel/'` 会要求 `'/dsh-channel//…'`），未命中走 SPA fallback（401/404）。route guard 由引擎统一处理。
- 同源 iframe 不受 CSP 限制（WebUI 主页面无 `frame-src 'none'`）；`target="_blank"` 新窗口被 Electron 拦，页面内要用 fetch + 内联展示。
- Web 前端最小范例：`package.json` 声明 `exports["./client"]` + `dsh.client.platform="web"`；`client.js` 用 `window.__ModuleLoader__.load({id,factory:(require)=>{...}})`、`module.exports.inject=['slots']`、`ctx.slots.inject('main',…)` + `register(...)`；组件用 `require('react')`。**SidebarIcon 只返回图标**，label 由宿主渲染（重复渲染会出现两遍文字）。
- 已装 `dsh-mnemon` 0.5.38（分层记忆 + Memory System），本次未复用；两套记忆互不冲突。

## 已踩过的坑（简述）

1. 不能直接读 `ctx.sessions`，用 `ctx.inject([...], cb)`；`controller.prompt` 漏 AbortSignal 会 `signal.throwIfAborted is not a function`。
2. 「no reply」多为 `MISSING_CREDENTIAL`（模型路由没切），不是字段未知；`/api/health` 的 `lastError` 是历史值，成功请求不清除。
3. prefix 尾斜杠导致 DSH 同源 `/dsh-channel/admin` 404 → iframe 白屏；已改 `path:'/dsh-channel'`，handler 对未识别路径回 404、iframe 加 `minHeight` 兜底。
4. 「打开日志」`<a target="_blank">` 点击无反应 → 按钮 `fetch` + `<pre>` 内联展开。
5. 所有按钮提示串到「角色定义」右下角（只有一个 `#status`）→ 三个 `.status` + `setStatus(text,id)`；两个保存按钮改成只提交各自字段（曾因一起提交把 `persona` 清空，已恢复默认角色）。
6. 工作区标题常量随改名变成 `dsh-channel`；**存量工作区 / 会话 ID 里的旧名不管**（实测 `~/.dsh/storages/workspace.json` 里标题已是 `dsh-channel`）。
7. UI 迭代已落地：侧边栏双向箭头图标、client 表底部留白、演示页状态胶囊（中文标签 + 淡色竖线）、管理页运行状态并入描述行。
8. T1 首版直接取 `ctx.systemPrompt` → 线上 `promptSection=fallback`（cordis 拒绝属性取服务）；改 `ctx.get('systemPrompt')` 后 `probe.promptSection=system`、错误清空。
9. `chat.js` 虽每次请求动态 import，但已注册的 section 闭包不会自动替换；`SECTION_VERSION` 递增才会 dispose + 重注册，改 section 逻辑记得递增版本（当前线上进程由旧版注册、没存 disposer，递增前需重载一次插件，否则同名注册会冲突并降级为 `fallback`）。

## 会话 ea246af8 归档

导出 `修复 chat.js 回复提取_20261106013148.json`（12 轮）：修回复提取 → 账号路由免 API Key → 改名 + 专属工作区 → 角色/记忆自管 + 侧边栏管理页 → 诊断白屏/图标/文案。会话结束时 UI 三项未落地，后续会话已全部补齐。完整脉络已并入上文各节，不再单列。

## 当前状态

**功能已实现**：真实 DSH 会话回复（含重启复用）、角色定义与全量 `memory.log`、可改 PIN + LAN 认证、已连接 client 可视化、同源管理页/演示页、侧边栏入口、工作区归属、NDJSON 流式 + `emotion` 枚举；**T1 已落地**：角色/记忆走 `systemPrompt.section`，`controller.prompt()` 每轮只发用户原文，记忆按会话载入一次快照。

**已验证**：4 个 JS `node --check` + lint 全过；独立进程断言覆盖新路由 200 / 旧路由 404、LAN 无 PIN 401 / 带 PIN 200、PIN 与记忆不泄露到 LAN、client 列表、全量日志写入（60 行）与注入 20 条、状态部分提交；T1 断言覆盖「section 只注册一次 / 每轮纯文本 / 其他会话不受影响 / 改角色下一轮生效 / 外部编辑才重载 / 热加载不重复注册 / 重启复用 sessionId / 新会话 / inject 路径 / 降级」。线上实测 `/dsh-channel/admin`、`/api/dsh-channel/state`、`/dsh-channel/clients`、`/dsh-channel/memory.log` 均 200，`workspaceTitle=dsh-channel`、`workspaceAttached=true`、`workspaceError` 空；T1 线上 `probe.promptSection=system`、`promptSectionError` 空、`memoryLoads=1`，会话事件最后一次 `agent/inbox/spliced` 不含 `[角色定义]` 前缀（此前三次均带）。

**注意**：`backend=dsh` 只在发消息后成立；`health.lastError` 不清除。T1 复测的三次请求均拿到 `{"type":"done","text":""}` 且 `turn/end.reason.kind=blocked`（模型侧未给回复，改造前同样 blocked，与本次改动无关）——「system prompt 实际进入模型请求、回复遵循角色/记得青柠」尚未端到端验收，模型链路恢复后补；别把历史 `lastError` 当当前故障。

**数据**：`state.json` 现值见「2026-11-07 收尾」（`persona` 默认角色、`pin` 由用户设置、`sessionId=dsh-channel-1793974315196`）；`memory.log` 全量保留（约 18 条，blocked 轮次不写记忆）。

## 下一步

1. **真机通话闭环**（唯一阻塞整体验收的环节）：手机与 PC 同 WiFi（本轮已实测 8787 可通）→ 进通话说一句 → `/api/asr` → `/api/chat` → `/api/tts` → 播放；重点验两件事：① 连续说几句不用碰屏幕；② 鲸鱼朗读时插话能立刻停声、且**不会自己打断自己**（本机无平台 AEC，见「通话模式」一节）。
2. **中英混输**：按「ASR 混输实测」选换模型（`vosk-model-cn-0.22` / SenseVoice）还是做纠错；client 零改动。
3. **首次完整提交**：把 `plugin/`、`docs/`、`android/app/`、`android/gradle*`、`archive/` 等未跟踪文件归位入库；`.gitignore` 已挡 `plugin/models`、`plugin/.venv`、`android/*/build`。
4. **`ArchivedSessionGate` 守卫**：`chat.js` 复用 `sessionId` 前排除归档会话，避免 `turn/end blocked` 静默空回复。
5. **T1 剩余验收**（模型不再 blocked 后）：发一条正常消息，确认角色定义生效、能回忆 `memory.log` 里的「青柠」，且会话历史不新增 `[角色定义]/[长期记忆]` 前缀；管理页改角色后下一轮生效。
6. 其余按 `TODO.md`：阶段 1 的 WS / token 鉴权 / 流式 / 打断未做；**阶段 2 进行中**——Android 0.7.0（APK 25.22 MiB、ASR 与 TTS 均在插件端、**通话模式常驻监听 + 插话打断**），界面已产品化；阶段 3 未开始。旧 `dsh-bridge-*` / `esp32-bridge-*` 归档会话可在 Desktop 删。

## 已知取舍

- **PIN 口径**：方案 `docs/technical-proposal.md` C5 / `2.3 会话与绑定时序` / `src/pin.ts` 记的是「设备显示 `activation.code` + 用户确认 → 绑定 `Device-Id` + 签发 token → WS 校验 Bearer」；实际按用户选择落地**预共享 PIN**（`state.json` 里 6 位码，LAN client 用 `Authorization: Bearer <PIN>` 或 `X-PIN`，本机免 PIN）。`activation.code`、token 签发、Device-Id 白名单、限次/过期、WS 长连接**未实现**，TODO 阶段 1「服务与鉴权」不能勾。client 列表是请求级统计（进程内 Map，5 分钟算在线），不是 TCP 连接表。
- 记忆是原文流水账（可直接编辑 `memory.log`），不是提炼式摘要；要提炼可启用 `dsh-mnemon` auto-capture。快照只在外部编辑 / 新会话 / 重启时重载，本轮对话靠会话历史，不再每轮刷新 system prompt。
- 角色/记忆已改为 `systemPrompt.section`（不再拼进用户消息）；旧会话历史里已有的 `[角色定义]/[长期记忆]` 前缀无法清除（保留 `sessionId` 的代价），需要完全干净的历史只能新建会话。仅当拿不到 `systemPrompt` 服务时才降级回旧的每轮拼接。
- `chat.js` 的 HTTP RPC 兜底是死代码，可删；`lastError` 成功后不清除（要修得动 `index.js` + 重载）。
- **Android ASR 口径**：已改为**插件端识别**（手机录音 + VAD 断句 → `POST /api/asr` → PC 侧 Python Vosk 解码），APK 25.31 MiB、无模型无 native 库；代价是必须 PC 在线（本来对话也依赖它）、首请求约 3.2 s、无流式。PC 侧可随时换大模型（`vosk-model-cn-0.22` 1.3 GB / SenseVoice），客户端零改动。（语音输出 0.4.0 起也搬到插件端，见下节。）
- **`turn/end blocked` 根因（已定位，未加守卫）**：引擎的 `ArchivedSessionGate` 在 `agent/pre-step` 直接返回 `{kind:'reject'}`（`underArchivedSession(ctx, agent)` 命中即拒），表现是 `turn/start` 后立刻 `turn/end{reason.kind:'blocked'}`、刚 splice 进去的用户消息被移除，模型根本没被调用。证据：`~/.dsh/storages/workspace.json` 的 `archivedSessionIds` 里含旧 `dsh-bridge-1793898963054`。插件目前只按 `state.sessionId` 复用，没有排除归档会话；再遇到时删 `plugin/data/state.json` 的 `sessionId` 或换新会话即可。给 `chat.js` 加「归档则新建」的守卫是待做项。

## 阶段 2 起点：Android client（2026-11-06）

用户给出的产品形态：点进去只有一只鲸鱼、无输入框、纯语音；右上角设置弹窗搜索/连接局域网插件 + 声音音色资源的切换与下载管理；插件响应回来后输出语音、下方小字字幕、鲸鱼按情绪展示 gif。

- 实现目录 `android/`（Kotlin + Jetpack Compose，minSdk 28 / targetSdk 34 / compileSdk 34），构建、协议、设计与实测记录见 `android/README.md`；已出 debug APK：`android/app/build/outputs/apk/debug/app-debug.apk`（0.2.0 / 116.06 MiB）。
- **插件零改动**：直接消费现有 `/api/health`（发现/连通）+ `/api/chat`（PIN + NDJSON `state/delta/emotion/done`）；语音放端侧系统 ASR/TTS，故阶段 1 的 WS / ASR / TTS 不是本版前置，复选框仍不勾。
- 发现方式：扫本机 /24 的 8787 + `/api/health`，不是 mDNS；跨网段手动填地址。
- 情绪资源：`app/src/main/assets/whale/emotion-map.json` 是 `emotion → 动画 key` 映射，命名参考 `dsh-Go/docs/assets` 的 4 状态 gif；**素材不复制**（`dsh-Go/ASSETS.md:3,19` 禁止用于自己发布的产品），资源缺失时回退内置程序化鲸鱼动画。
- 音色包：TTS 参数组合 + 可选唤醒音效，内置 3 个；设置里下载 `.json` / `.zip`（含 `pack.json`）安装、切换、删除。（0.4.0 起参数改成服务端 `voice/rate/pitch`，系统 TTS voice 与语音数据入口已删，见下节。）
- **语音输入改为瘦客户端（0.3.0）**：0.2.0 曾内置 Vosk（42 MB 模型 + 19 MB so，APK 116 MiB），端侧识别可用但包体过大；且本 App 本来就必须连插件才能对话，离线识别的价值是残缺的。参照 `xiaozhi-esp32` 的做法（ESP32 只做 AFE/VAD + Opus 编解码 + 播放，ASR/LLM/TTS 全在服务端），改为**手机只录音 + 能量 VAD 断句，整段 PCM 上传插件端 `/api/asr`**。插件侧用 Python `vosk` 0.3.45 + `vosk-model-small-cn-0.22`（Apache-2.0，模型解压 65.1 MB），常驻 worker 复用 recognizer 并启动预热：1 s 音频 67 ms、5 s 224 ms；模型只在 PC 上，`plugin/models`、`plugin/.venv` 已 gitignore。手机端 `RemoteSpeechInput` + `AsrClient`，事件契约不变（多一个 `Recognizing`），`PluginClient → 字幕 → TTS` 未改（当时输出仍走系统 TTS）。同时删掉 `material-icons-extended`（34 MB，只用两个图标）并自绘 `MicGlyph`。
- **验证状态**：`:app:assembleDebug` 成功，APK `26,535,693 B`（25.31 MiB，161 条目，v2 验签，dex 24.6 MiB，无 native 库），`adb install -r` 后 `versionCode=3 / 0.3.0`；冷启动无崩溃，设置页显示「识别状态：插件端识别 /api/asr（本机不加载模型）」。PC 侧用 SAPI `Microsoft Huihui` 合成「你好，今天天气怎么样」→ `/api/asr` 返回 `你好今天天气怎么样`，端到端成立。**真机语音闭环待测**：本轮手机 WiFi 关闭（只有蜂窝 `rmnet_data5` 与 USB 共享 `rndis0`），到 PC 的 8787/8788 全部超时；`adb reverse` 在该 ROM 上能建连但不转发；且 `/api/asr` 加在 `index.js` 里，需**重载一次插件**才生效（`chat.js` 才是按请求热加载）。TTS 引擎 `com.oplus.ttsaccessibilityengine` 正常、5 个音色。历史：`archive/out/dsh-android.apk` 是旧 Termux 方案产物（`com.dsh.android` 0.2.0、58 KB）；`archive/build-cache/sdk` 是当时手装的 SDK（复用时修了误写的 API 14 元数据、补 licenses、复制 `build-tools/35.0.0`）。

## 插件端 TTS：Edge 神经语音（2026-11-07）

起因：演示页原来用浏览器 `speechSynthesis`，Windows 经典 SAPI 只有 `Microsoft Huihui` 一个中文音色；OneCore 有 `Kangkang` / `Yaoyao` 但要走 WinRT，从 Node / Python 直接调不方便；用户不想为 TTS 再下本地大模型。结论：用 **edge-tts 7.3.1**（调 Edge 在线语音服务，包体几 MB、无本地模型、无显存）。

- 依赖：`& .venv/Scripts/python.exe -m pip install edge-tts`（本机装的是 7.3.1；`.venv` 已 gitignore，换机需重装，与 `vosk` 同一个 venv）。
- 新增 `plugin/lib/tts_worker.py`（常驻 Python worker，stdin 读 `<uint32 nbytes>+JSON`，stdout 写 JSON 行头 + 原始 mp3 字节，asyncio 并发、单条失败不拖垮进程）、`plugin/lib/tts.js`（Node 侧 spawn + 粘包拼接 + 串行队列 + 64 条文本缓存 + 音色清单 10 分钟缓存）、`plugin/lib/tts_http.js`（PIN 鉴权同 `/api/asr`，loopback 放行）。
- 路由：`GET|POST /api/tts`（`text` / `voice` / `rate` / `pitch` / `volume`，返回 `audio/mpeg`，头 `x-tts-voice` / `x-tts-ms` / `x-tts-cache`）、`GET /api/tts/voices?locale=zh`。挂在 `index.js`（8787，需**重载一次插件**）与 `asr-server.mjs`（8788，调试用）；`/api/health` 多返回 `tts` 状态。
- 音色清单：默认 **89 ms 返回内置 8 条 zh-CN 表**（`source: builtin`）并后台拉在线全量，`&live=1` 才等全量（`zh` 前缀 14 条、全量 322 条，7–9 s，`source: live`），结果缓存 10 分钟。**别在页面加载路径上同步等全量**。
- 演示页：`done` 后改调 `/api/tts` 播放 blob（失败自动回退 `speechSynthesis`）。音色下拉后来删了，改由管理页统一配置（见下条）。
- **音色管理（服务端唯一出口）**：`state.json` 新增 `tts: {voice, rate, pitch}`（`DEFAULT_TTS` = Xiaoxiao / 0 / 0）；`lib/state.js` 的 `normalizeTts()` 做白名单与范围校验（voice `[A-Za-z0-9._-]{3,64}`、rate −50..100 %、pitch −50..50 Hz，非法回默认）；`lib/tts.js` 的 `ttsSettings()` 优先级 = `DSH_TTS_VOICE` 环境变量 > `state.tts` > 内置默认，`/api/tts` 请求不带参数时就落在这里，`/api/health` 的 `tts` 会回显当前值。
- 管理页 `public/admin.html` 新增「语音音色」卡片：音色下拉（内置 8 条 / 「刷新在线音色」拉全量）、语速、音调、试听（POST `/api/tts` 播 blob）、保存（POST `/api/dsh-channel/state` 的 `tts` 字段）；`/api/dsh-channel/state` 的 GET 也返回 `tts`。管理页走同源本机，免 PIN。
- 实测：默认 Xiaoxiao/0/0 → 保存 Yunxi/+10%/−5Hz → 只发 `{"text"}` 的 `/api/tts` 返回 `x-tts-voice: zh-CN-YunxiNeural`；非法值 `{voice:'!!!bad',rate:999,pitch:-999}` 归一化为默认音色 / 100 / −50；`/api/health.tts` 同步反映。
- `zh-CN` 音色 8 个：Xiaoxiao / Xiaoyi（女）、Yunjian / Yunxi / Yunxia / Yunyang（男）、liaoning-Xiaobei（东北）、shaanxi-Xiaoni（陕西）；性别与风格见 `GET /api/tts/voices`。
- 实测（本机，PC 联网）：`import edge_tts` 0.43 s，首音频块 1.67 s，整句（约 5 s 音频）2.1 s；同文本第二次 33 ms（缓存命中，`x-tts-cache: hit`）；非法音色 `503 NoAudioReceived`（5 ms 快速失败），空文本 400，出错后 worker 仍可继续合成；LAN 无 PIN 401 / 带 `x-pin` 200。在线清单偶发一次失败，worker 对网络类异常自动重试一次（`NoAudioReceived` 不重试）。
- 取舍：**必须联网**（离线即不可用；网页端回退浏览器 `speechSynthesis`，Android 0.4.0 起没有本机兜底，PC 不可达就没有语音）；edge-tts 输出格式固定 `audio-24khz-48kbitrate-mono-mp3`（7.x 无 `output_format` 参数），ESP32 阶段要么解 mp3 要么在 PC 侧转 PCM；单次上限 2000 字、无流式、无打断（打断仍待做）；在线服务有变更 / 限流风险，要纯离线得换 piper / sherpa-onnx，worker 协议不变、只换 `tts_worker.py`。
## 阶段 2 语音输出上移：Android 0.4.0（2026-11-07）

决定「全面切插件端合成」，理由是要适配 ESP32：设备侧（ESP32 / 手机）都只做采集、编解码与播放，ASR / LLM / TTS 全在 PC（与 `xiaozhi-esp32` 一致）。于是 Android 的系统 `TextToSpeech` 整块移除。

- `voice/TtsOutput.kt` 重写：文本 → `TtsClient` POST `/api/tts` → 落 `cacheDir/tts/speech-<gen>.mp3` → `MediaPlayer.prepareAsync()` 播放；`generation` 计数丢弃过期结果（打断 / 快速连说）、`stop()` 立即中止并删临时文件；唤醒音效仍用本地 `MediaPlayer`。
- 新增 `net/TtsClient.kt`：带 `Authorization: Bearer <PIN>` / `X-PIN` / `device-id`，`413`（文本过长）、`401`、`503`（PC 侧 TTS 未就绪）分别给中文提示。
- 删掉的死配置：`UiState.systemVoices` / `systemVoiceName`、`SettingsSheet` 的系统音色列表与「打开系统 TTS 语音数据安装」、`SettingsStore.systemVoiceName`、`AndroidManifest` 的 `TTS_SERVICE` `<queries>`、`VoicePack.locale/pitch(Float)/rate(Float)/voiceName`。
- 音色包改成服务端预设：`voice`（Edge `ShortName`）+ `rate`（百分比整数，`+10` = 快 10%）+ `pitch`（Hz 整数，`-12` = 低 12 Hz），JSON `schema: 2`；内置 4 个（小鲸鱼 Xiaoxiao / 温柔 Xiaoxiao -8% -12Hz / 活泼 Xiaoyi +10% +12Hz / 沉稳男声 Yunyang -4% -6Hz），下载仍支持 `.json`/`.zip`，新增 `voice` 白名单校验。
- 构建与装包：`:app:assembleDebug` 成功，`26,434,679 B`（25.21 MiB）/ 161 条目 / `versionCode=4` / `0.4.0`，`adb install -r` 通过，冷启动无崩溃，截图确认设置页只剩 4 个服务端音色预设。
- **未验证的一环**：手机到 PC 的 8787 仍被 Windows 防火墙拦（手机 `rndis0` = 10.86.196.160/24，与 PC 同段，但 `ping` 与 TCP 均不通；`adb reverse` 在该 ROM 不转发），所以「手机说话 → 字幕 → 插件端合成 → 播放」要在放行入站或换同 WiFi 后测。PC 侧 `/api/tts` 已用 App 同参数（`voice` + 整数 `rate`/`pitch`）验证返回 `audio/mpeg`（约 9.6–18 KB/句）。
- 顺带：手机里存的旧 endpoint 是 `http://10.181.97.185:8787`（上一次网络），换网后需在设置里重新扫描或手填。

### 0.5.0：client 连音色参数也不发了（2026-11-07）

用户判断：这段音频的消费方是模型 / 下游分析，不是挑音色的人耳，client 传 `voice/rate/pitch` 没意义。于是把上一版刚做的服务端音色预设也砍掉：

- `TtsClient.synthesize()` 只发 `{"text"}`；`TtsOutput.speak(text)` 不再接音色包。
- 删除 `voice/VoicePacks.kt`（`VoicePack` + 下载 / 解压 / Zip Slip 校验）、`assets/voices/voice-packs.json`、`SettingsStore.voicePackId`、设置页「声音音色」整节与「音色包 URL / 下载」区块、`UiState.voices/selectedVoiceId/packUrl/downloadPercent`、唤醒音效（原属音色包，内置包本来都没配）。
- 音色只在服务端：管理页 `/admin`「语音音色」写 `state.json` 的 `tts`（`DSH_TTS_VOICE` 可临时覆盖），client 零改动。
- 构建：`:app:assembleDebug` 成功，`26,385,163 B`（25.16 MiB）/ 159 条目 / `versionCode=5` / `0.5.0`；`adb install -r` 通过，冷启动无崩溃，截图确认设置页只剩「语音能力 + 局域网插件」。
- 若以后真有人耳挑音色：插件端 `/api/tts` 的 `voice/rate/pitch` 参数还在（网页演示页在用），client 加一个下拉把参数带上即可。

## UI 产品化：界面不解释技术（2026-11-07）

用户反馈：App 设置页与管理页文案在讲实现，产品界面应当简洁大方，技术解释挪进文档。

- 新增 `docs/app-client.md`（客户端职责 / 页面结构 / 协议消费 / 构建与排障）与 `docs/plugin-server.md`（路由 / 状态文件 / 音色管理 / worker 协议 / 排障），根 `README.md` 加索引；`android/README.md` 保留实现细节。
- App 设置页：删掉「语音能力」整节（Vosk、`/api/asr`、"APK 不含模型"、识别状态）与全部音色/接口文案，只剩标题 +「连接桌面端的小鲸鱼，就能开口聊天。」+ 桌面端地址 + 配对码 + 搜索/连接 + 状态点 + 设备行；`UiState.asrAvailable/asrDetail` 一并删除，「PIN」改称「配对码」。
- **修正状态语义**：原 `connected` 表示「填过地址」，现在换成 `reachable`，只有 `/api/health` 校验通过才亮绿点「已连接」，否则灰点「未连接」——之前地址过期也会显示已连接。
- 错误文案全部改人话：`配对码不正确` / `网络异常，请确认和电脑在同一网络` / `麦克风不可用，请检查权限` / `电脑端语音服务不可用`；状态码与异常堆栈不再上界面，改由 docs 的排障表承接。
- 管理页：标题「小鲸鱼」，顶部长句（backend / sessionId / 模型 / 工作区）改为折叠「运行状态」（展开时才查一次 `/api/health`）；卡片改为「角色定义 / 声音 / 记忆 / 配对码 / 在线设备」，hint 各压到一句；「已连接的 client」→「在线设备」。
- 演示页：标题「小鲸鱼」、心情值显示中文（平常 / 开心 / …）、状态胶囊只显示「已连接 / 演示模式 / 未连接」，删掉情绪映射与接口路径说明；空回复显示「说点什么试试」。
- 构建：`0.6.0` / `versionCode 6`，APK `26,385,163 B`（25.16 MiB）/ 159 条目；`adb install -r` 通过，截图确认设置页与管理页新文案。
- 提醒：若拿 TTS 音频回环做 ASR 自测，别调快语速（`+10%` 以上识别率下降），默认 `+0%` 最稳。

## ASR 混输实测：小模型只认中文（2026-11-07）

测试素材固化在 `plugin/testdata/`（Windows SAPI `Microsoft Huihui` 合成，16 kHz / 16-bit / mono / 默认语速；复测直接把这个文件二进制 `POST /api/asr` 即可，本机 loopback 免 PIN）：

| 文件 | 说的内容 | 识别结果 | 耗时 |
| --- | --- | --- | --- |
| `zh.wav` | 帮我把这个文件打开，然后查一下镜像 | `帮我把这个文件打开然后查一下镜像`（全对） | 328 ms |
| `mix.wav` | 帮我把 VS Code 打开，然后 search 一下 Docker 镜像 | `帮我把悲哀扣打开然后事儿一下着可是景象` | 2001 ms |

结论：**`vosk-model-small-cn-0.22` 只认中文，英文词一律按中文谐音硬转**（VS Code → 悲哀扣、search → 事儿一下、Docker → 着可是）；纯中文链路成立（这也是 0.3.0 的验收口径）。要支持中英混说有三条路：① 换 `vosk-model-cn-0.22`（1.3 GB，中文更准但英文仍弱）；② 换 SenseVoice-Small（需引入 sherpa-onnx / onnxruntime 侧依赖，混输明显更好）；③ 保留小模型 + 词典/提示词纠错（最省，自由口语不牢）。`/api/asr` 后面只是 worker，换模型客户端零改动。

## 通话模式：常驻监听 + 打断（2026-11-07，Android 0.7.0）

用户要求：不要按着按钮说话，进去就直接常驻监听，并且要考虑被打断。对讲机形态（点一下说一句）作废。

- 新增 `voice/CallSpeechInput.kt` 取代 `voice/RemoteSpeechInput.kt`（已删）：一个 `AudioRecord` 常驻到挂断，capture 线程按句切分，切出的 PCM 投给单线程池做 `/api/asr`——**识别耗时不再挡住继续听**（0.6.0 是停下来上传）。
- 打断链路：VAD 判定开口 → `SpeechInput.Event.SpeechStarted` → `WhaleViewModel` 若在 `SPEAKING` 就 `tts.stop()`、在 `THINKING` 就 `chatJob.cancel()`，随后这句话照常识别当新输入。开口前保留 300 ms 预录，头几个字不丢。
- 防自打断三层：① `VOICE_COMMUNICATION` + `AcousticEchoCanceler`/`NoiseSuppressor`（理想）；② 朗读期间阈值抬到 `max(噪声峰值×3.5, 700)` 且需连续 320 ms；③ 朗读期间起步又始终没被判为人声的片段直接丢弃，不送识别。
- **实测结论（关键，别再踩）**：这台 OPPO PLE110 / Android 16 上 `AudioSource.VOICE_COMMUNICATION` **返回全 0 采样**（RMS 恒 0），`VOICE_RECOGNITION` 正常（静音 RMS≈10 / peak≈35，说话 peak 200~1200）。当前固定用 `VOICE_RECOGNITION`，**平台 AEC 拿不到**，自打断的实测还欠着。
- **VAD 判据从 RMS 改峰值**：首版沿用 0.6.0 的「RMS > 350」，实测用户说话峰值只有 220，一句都没触发上传、界面完全没反应。改帧内峰值 + 噪声底「标定取最小值 + 快降慢升」+ 门限下限 120 后，阈值收敛到 ~216，判定正常。调 VAD 时临时打 `Log.d("dsh-vad", "rms/peak/noise/thr/voiced")` 看数值，比猜快得多（已删）。
- 状态机：`UiState.callActive` + 主屏按钮改成通话开关（蓝色麦克风 ↔ 红色挂断），通话中失败只提示不退出，只有麦克风起不来才 `Finished` 收尾。
- 构建：`0.7.0 / versionCode 7`，APK `26,441,113 B`（25.22 MiB），`adb install -r` 通过，冷启动与进入通话截图确认。
- **环境变更**：`archive/build-cache/sdk` 已不存在，本机 SDK 改到 `D:\Env\AndroidSDK`（只有 `platforms/android-35`），`compileSdk` 从 34 提到 **35**（`targetSdk` 仍 34）；`android/local.properties` 已写 `sdk.dir`（gitignore 内）。
- **未闭环**：端到端「说话 → 字幕 → 朗读」与「朗读中插话 → 停声」都还没在真机验证（测试时用户无法出声）。同 WiFi 下手机 → PC 8787 本轮已通，不再是网络问题。

## 2026-11-07 收尾：新会话从这里开始

**仓库**：`main` 只有一次提交 `64f8bc9 --story=初始化仓库`；当前 `M README.md`、`M android/README.md`，而 `plugin/`、`docs/`、`android/app/`、`android/gradle*`、`android/settings.gradle.kts`、`archive/`、`.gitignore`、`HANDOFF.md`、`TODO.md` 全部未跟踪 —— 即**阶段 1/2 的全部代码尚未进版本库**，首次完整提交是待办（提交前确认 `plugin/models`、`plugin/.venv`、`android/app/build` 被 `.gitignore` 挡住；`plugin/testdata/*.wav` 共 330 KB，作为 ASR 回归样本建议一并入库）。

**运行态**（`127.0.0.1:8787`，Desktop 插件已加载含 `/api/asr` `/api/tts` 的新版 `index.js`）：`health.backend=dsh`；`asr.ready=true`（vosk / `vosk-model-small-cn-0.22` / loadMs 380）；`tts.ready=true`（edge-tts / `zh-CN-XiaoxiaoNeural` / rate 0 / pitch 0）。

**`plugin/data/state.json` 现值**（文件本身 UTF-8 正常）：

```json
{"persona":"你叫马飞飞，是一个可爱的肥鲸鱼","sessionId":"dsh-channel-1793974315196","pin":"324000","tts":{"voice":"zh-CN-XiaoxiaoNeural","rate":0,"pitch":0}}
```

> 读取陷阱：PowerShell 5.1 的 `Get-Content` 默认按 ANSI 解，中文会显示成 `浣犲彨椹…` 这类乱码，看着像文件损坏。用 `[IO.File]::ReadAllText($p,[Text.Encoding]::UTF8)` 或 `Get-Content -Encoding UTF8` 才准；`plugin/lib/state.js` 内部是 `fs.readFile(...,'utf8')`，不存在编码 bug。

**三件未闭环的事**（优先级序）：

1. **真机语音闭环**：手机与 PC 同 WiFi（或放行 8787 入站）→ 录音 → `/api/asr` → `/api/chat` → `/api/tts` → 播放。此前卡在网络：手机只有蜂窝 / `rndis0` 同段但 ping 与 TCP 均不通，`adb reverse` 在该 OPlus ROM 能连不转发；App 里存的旧端点 `http://10.181.97.185:8787` 换网后需重扫或手填。
2. **中英混输**（见上节）：定换模型还是做纠错。
3. **首次提交 + `ArchivedSessionGate` 守卫**：`turn/end.kind=blocked` 的根因已定位（`workspace.json` 的 `archivedSessionIds` 命中即拒），`chat.js` 复用 `state.sessionId` 前应先排除归档会话，命中就新建。

## 复测命令

```powershell
$body = [Text.Encoding]::UTF8.GetBytes('{"text":"只回复四个字：链路已通","sessionId":"smoke"}')
$r = Invoke-WebRequest -Uri 'http://127.0.0.1:8787/api/chat' -Method POST -Body $body -ContentType 'application/json; charset=utf-8' -UseBasicParsing -TimeoutSec 90
$t = if ($r.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($r.Content) } else { [string]$r.Content }
@($t -split "`r?`n" | Where-Object { $_ }) | Select-Object -Last 2
```

T1 状态（`promptSection=system` 表示走 system prompt，`fallback` 表示退化为每轮拼接）：

```powershell
$h = (Invoke-WebRequest -Uri 'http://127.0.0.1:8787/api/health' -UseBasicParsing).Content | ConvertFrom-Json
$h.probe | Select-Object promptSection, promptSectionError, memoryLoads
```

插件端 TTS（8787 需重载插件后生效；独立调试服务为 8788）：

```powershell
$uri = 'http://127.0.0.1:8787/api/tts?text=' + [uri]::EscapeDataString('你好，我走插件端合成。') + '&voice=zh-CN-XiaoxiaoNeural'
$r = Invoke-WebRequest -Uri $uri -UseBasicParsing
[IO.File]::WriteAllBytes("$PWD/tts.mp3", $r.Content)
"$($r.StatusCode) bytes=$($r.RawContentLength) ms=$($r.Headers['x-tts-ms']) cache=$($r.Headers['x-tts-cache'])"
(Invoke-RestMethod 'http://127.0.0.1:8787/api/tts/voices?locale=zh-CN').voices.name
```

> 中文参数在 PowerShell 5.1 直接塞进 `-Body` / `-d` 会按 ANSI 编码，可能被服务端判成 `invalid JSON`；用 `[uri]::EscapeDataString()` 走 GET，或用 Node `fetch`。

情绪契约：`{"type":"llm","emotion":"happy"}` 对齐 `xiaozhi-esp32/docs/websocket_zh.md:242` + `main/application.cc:695-701`；形象资源外部提供，`dsh-Go/docs/assets` 素材按 `ASSETS.md:3,19` 不可随产品分发。
