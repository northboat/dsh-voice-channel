# 本地开发指南（Android / 插件 / ESP32）

面向「在这台机器上继续动手」的人。命令都在仓库根 `e:\File\Repo\dsh-android` 下执行。

文档分工：进度与踩坑看 [`HANDOFF.md`](../HANDOFF.md)，实施顺序看 [`TODO.md`](../TODO.md)，客户端细节看 [`android/README.md`](../android/README.md)，插件接口看 [`plugin-server.md`](plugin-server.md)，**本文只讲怎么跑起来、改哪里**。

---

## 0. 本机环境（实测可用）

| 项 | 值 |
| --- | --- |
| Node / npm | v22.23.2 / 10.8.2 |
| JDK | `D:\env\java\jdk17`（`JAVA_HOME` 已配） |
| Android SDK | `D:\Env\AndroidSDK`（`android/local.properties` 已指向）；adb 在 `platform-tools\adb.exe` |
| Gradle | **仓库没有 wrapper**，用本机缓存里解压好的 8.11.1（见 1.1） |
| 测试机 | OPPO PLE110，物理分辨率 1280×2800 |
| DSH Desktop | `D:\Software\DSH\DeepSeek Harness.exe`，profile `~/.dsh/profiles/desktop` |
| 图像处理 | **没有可用的 Python**（`python` 是 uv 的坏 shim，报 `uv trampoline failed`），用 Node + `sharp` |

---

## 1. Android

### 1.1 构建与安装

```powershell
# 找到本机缓存的 gradle 发行版（路径里的 hash 每台机器不同）
Get-ChildItem $env:USERPROFILE\.gradle\wrapper\dists\gradle-8.11.1-bin -Recurse -Filter gradle.bat

cd android
& "C:\Users\<你>\.gradle\wrapper\dists\gradle-8.11.1-bin\<hash>\gradle-8.11.1\bin\gradle.bat" :app:assembleDebug --offline -q
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell monkey -p com.dsh.channel.whale -c android.intent.category.LAUNCHER 1
```

- 有依赖缓存时 `--offline` 三到十秒出包；第一次或改了依赖就去掉 `--offline`。
- 版本号在 `android/app/build.gradle.kts`（`versionCode` / `versionName`），当前 `11 / 0.10.1`。
- 产物：`android/app/build/outputs/apk/debug/app-debug.apk`（约 25.45 MiB，debug 不混淆）。

### 1.2 真机调试

| 目的 | 命令 |
| --- | --- |
| 截图 | `adb shell screencap -p /sdcard/x.png` 然后 `adb pull /sdcard/x.png $env:TEMP\x.png` |
| 只看本应用日志 | `adb shell pidof com.dsh.channel.whale` → `adb logcat -d --pid=<pid>` |
| 点击 | `adb shell input tap <x> <y>`，坐标按 `adb shell wm size`（本机 1280×2800） |
| 授权录音 | `adb shell pm grant com.dsh.channel.whale android.permission.RECORD_AUDIO` |

- **截图预览是缩放过的**（484×1008 一类的缩略图），拿预览图里的坐标点会点空，先按 `wm size` 换算。
- 本机的 `RECORD_AUDIO` 曾被 `USER_FIXED` 拒过：麦克风没反应先查 `adb shell dumpsys package com.dsh.channel.whale | Select-String RECORD_AUDIO`。
- 卡顿看 `Choreographer: Skipped N frames`；`deliverInputEvent cost time > 1000` 说明主线程被绘制拖住了（见 1.6）。

### 1.3 代码地图

| 文件 | 职责 |
| --- | --- |
| `MainActivity.kt` | 唯一 Activity，Compose 宿主，录音权限 |
| `WhaleViewModel.kt` | 状态机 `Phase.IDLE / LISTENING / THINKING / SPEAKING` + `callActive`，WS 事件 → UI/播放器 |
| `net/WsSession.kt` | 小智协议长连接（hello / listen / stt / llm / tts），退避重连 |
| `voice/CallSpeechInput.kt` | 常驻 `AudioRecord` + 峰值 VAD 切句 + 整段上行，含朗读期防自打断门限 |
| `voice/StreamingPlayer.kt` | `AudioTrack` 边收边播 + 字幕按播放头推进 |
| `net/PluginClient.kt` | 只剩 `GET /api/health` 探活（对话/语音都在 WS 上） |
| `net/SettingsStore.kt` | SharedPreferences `dsh-voice-channel`：endpoint / pin / packId / deviceId |
| `ui/WhaleScreen.kt` | 主屏布局、形象取哪一帧、字幕与通话按钮 |
| `ui/WhaleAvatar.kt` | 素材包渲染（AnimatedImageDrawable + Canvas），没有代码绘制的兜底形象 |
| `ui/AvatarPack.kt` | 素材包扫描与解析（`filesDir/packs/<id>/pack.json`） |
| `ui/SettingsSheet.kt` | 设置弹层（地址、配对码、形象列表、导入/改名/删除） |

### 1.4 改形象

形象只有素材包，没有代码绘制的兜底：旧的内置 Eva 绘制（`drawEvaFace()` 及 `EvaEye*` 配色）已整体删除，`ui/WhaleAvatar.kt` 只渲染 `pack`，取不到就画空白。

**素材包**：`ui/AvatarPack.kt` + `pack.json`（格式见 [`android/README.md` 3.10](../android/README.md)）。

- 播哪一帧由 **通话阶段** 决定，在 `ui/WhaleScreen.kt` 的 `packEmotion`：
  没通话 → `SLEEPY`（sleeping）；通话中 → `HAPPY`；思考 → `THINKING`（typing）；说完 → 先 `NEUTRAL`（idle/notification）2 秒，再回 `HAPPY`。
- 形象只有内置 Deepy 和导入的素材包，都按这套阶段映射播片；插件下发的 `emotion` 不再影响形象。
- 想加新的阶段映射，只改 `packEmotion` 那个 `when`。

**布局**：形象是根 `Box` 的第一层 `Modifier.fillMaxSize()`，字幕与通话按钮 `align(BottomCenter) + bottom 152dp` 浮在上面。**不要再退回 `Modifier.size(260.dp)`**——固定方框会让所有「按屏幕比例」的画法失真。

### 1.5 手动把素材塞进手机（免走 SAF 导入）

```powershell
adb push android/app/src/main/assets/packs/deepy/*.webp android/app/src/main/assets/packs/deepy/pack.json /data/local/tmp/
adb shell run-as com.dsh.channel.whale mkdir -p files/packs/deepy
adb shell run-as com.dsh.channel.whale cp /data/local/tmp/happy.webp /data/local/tmp/notification.webp /data/local/tmp/sleeping.webp /data/local/tmp/typing.webp /data/local/tmp/pack.json files/packs/deepy/
```

> Deepy 现在是内置形象，正常不需要这一步：`AvatarPacks.ensureBuiltIn()` 首次启动会把 `assets/packs/deepy/` 复制到 `filesDir/packs/deepy/`。这段只在改素材、又不想重新打包时用。

再把 `shared_prefs/dsh-voice-channel.xml` 里的 `packId` 设成 `deepy`：

```powershell
adb shell am force-stop com.dsh.channel.whale
adb shell run-as com.dsh.channel.whale cat shared_prefs/dsh-voice-channel.xml   # 拉出来改 <string name="packId">deepy</string>
adb push $env:TEMP\prefs.xml /data/local/tmp/prefs.xml
adb shell run-as com.dsh.channel.whale cp /data/local/tmp/prefs.xml shared_prefs/dsh-voice-channel.xml
```

### 1.6 已踩过的坑（改之前先看）

| 现象 | 原因 / 解法 |
| --- | --- |
| 素材糊成一团 | 放大时默认双线性过滤。`loaded.setFilterBitmap(false)`，像素画必须最近邻 |
| 麦克风「点不动」、点完没反应 | 主线程被每帧的整屏 `saveLayer`（羽化）拖死，`Skipped 360 frames`；素材已是透明底，羽化整层删掉，`saveLayer` 别覆盖整屏 |
| 点了之后要等近 1 秒才开始通话 | `CallSpeechInput.start()` 在主线程切音频模式 / 开 `AudioRecord` / `probeHasAudio()` 采 5 帧（最坏 500 ms）→ 全部挪到单线程 `speechWorker`（`WhaleViewModel`），UI 先切状态；`stop()` 同理（它要 join 工作线程） |
| 主页文案重复 | 状态文案只留底部按钮那一行；中间一列只放字幕与错误提示 |
| 插着耳机还是外放 / 用机身麦克风 | `enterCallAudioMode()` 以前无条件 `isSpeakerphoneOn = true`。现在走 `routeAudio()`：API 31+ 按 `availableCommunicationDevices` 选 `BLUETOOTH_SCO → BLE_HEADSET → WIRED_HEADSET → USB_HEADSET → BUILTIN_SPEAKER` 并 `setCommunicationDevice()`，老系统退回 `isWiredHeadsetOn/isBluetoothScoOn + isSpeakerphoneOn`；`AudioDeviceCallback` 负责通话中插拔 |
| 字幕把屏幕下半截铺满 | 只占一行：`captionTail()` 按整句截尾 + `maxLines = 1`；逐字节奏由朗读进度 `captionChars` 驱动，别在 UI 里自己计时 |
| 形象太靠下 | 素材画布底部原本有文字带，裁切后重心仍偏低；绘制时 `top - 0.06h` 抵消，别再去改素材 |
| 设置图标被盖住 | Compose 按声明顺序叠放：形象层放最前，`IconButton` 必须声明在它之后 |
| 素材尺寸乱跳 | 不同包画布尺寸不同，渲染按 `min(宽比, 高比)` 缩放，改了素材尺寸要重新看一遍 |
| 通话秒退 | 先查录音权限（`USER_FIXED` 拒绝过一次），再看 `AudioRecord` 日志 |

---

## 2. 插件（Node / DSH Desktop）

### 2.1 结构与热生效

| 文件 | 作用 |
| --- | --- |
| `plugin/lib/index.js` | `/api/health`、`/api/chat`、`/api/asr`、`/api/tts`、同源路由、`apply(ctx)` |
| `plugin/lib/chat.js` | 会话转发（**每次请求动态 import，改完即生效**） |
| `plugin/lib/state.js` | `state.json` 读写、角色解析、记忆 |
| `plugin/public/admin.html`、`index.html` | 管理页 / 演示页（**每次请求读文件，刷新浏览器即生效**） |
| `plugin/data/state.json` | `roles` / `activeRoleId`（页面上的「默认角色」）/ `bindings` / `persona` / `sessionId` / `pin` / `tts` |
| `plugin/data/memory.log` | 长期记忆，JSONL 全量保留，注入最近 20 条 |

生效边界：

- 改 `lib/chat.js`、`public/*.html` → 刷新页面即可。
- 改 `lib/index.js`（路由/`apply`）、`lib/state.js` → **必须重载 Desktop**。
- 服务监听 `0.0.0.0:8787`；本机请求免 PIN，局域网要 `Authorization: Bearer <PIN>` 或 `X-PIN`。

### 2.2 自测

```powershell
cd plugin
node --check lib/index.js; node --check lib/chat.js; node --check lib/state.js
curl.exe -s http://127.0.0.1:8787/api/health      # 看 backend / asr / tts / probe
```

- 管理页改的是 `state.json`：角色列表（当前角色带「默认角色」角标，弹窗里同时配系统提示词、音色、语速、音调，并带「试听」）、记忆路径、配对码、在线设备（每台一个角色下拉，改动即存）。**没有独立的「声音」卡片**——音色/语速/音调全部随角色走。
- **管理页只能用 `/dsh-voice-channel/*` 这组同源接口**（`state` / `clients` / `health` / `voices` / `tts` / `memory.log`）。页面是嵌在 DSH 里的同源 iframe，写 `/api/health` 会被引擎接走并回纯文本 `not found`，前端 `res.json()` 就报 `Unexpected token 'o', "not found" is not valid JSON`。
- 设备角色解析顺序：`bindings[device-id]` → `activeRoleId`；角色被删后失效绑定由 `normalizeBindings()` 清掉，上限 100 条。
- ASR/TTS 走 `plugin/.venv`（Python；模型在 `plugin/models`，不入库）。本机 `python` 命令不可用不代表 venv 不可用。
- 管理页内联脚本改完也要过 `node --check`：把 `<script>` 块抠到临时 `.js` 再检查。

### 2.3 改接口的注意

- 新增字段先给默认值（老 `state.json` 没有该键），再改页面。
- 页面文案改动纯前端，别在接口里做兼容。
- 演示页 `index.html` 用 `window.__ModuleLoader__` 加载 `lib/client.js`，改它要刷新页面（不是重载 Desktop）。

---

## 3. ESP32（留白，待补）

- 目标板：Waveshare ESP32-S3-Touch-AMOLED-1.75C（BSP / LVGL / 音频示例见上游仓库）。
- 目录 `esp32/` 目前为空，计划：WS 长连接 + AFE/Opus + 情绪 → 本地动画映射，服务端仍是同一个插件。
- 待填：工具链与分区表、引脚与屏参、音频通路（I2S 麦克风 / 喇叭）、LVGL 渲染方案、配网与 PIN 输入。
- **本节先留白**，动手时按 `TODO.md` 阶段 3 补全验收标准与实测记录。

---

## 4. 素材处理（原图 → `android/app/src/main/assets/packs/deepy/`）

环境：Node + `sharp`（临时目录装依赖，别装进仓库）。

```powershell
$dir = Join-Path $env:TEMP 'deepy-tools'; mkdir $dir -Force; cd $dir
npm init -y | Out-Null; npm i sharp --no-audit --no-fund
```

要点：

- 动画 WebP 在 sharp 里是「竖排长图」：`metadata().height = 单帧高 × 帧数`，单帧高看 `pageHeight`。
- 这批素材：角色画在 y 66–237，底部说明文字在 y 298–323 → **按 224×224 裁 `(x=56, y=40)`** 就把文字整条裁掉，角色不动。
- `extract()` 对动画是**逐帧生效**的，裁完仍是动画（帧数与每帧延迟都保留）。
- 编码用 `webp({ loop: 0, lossless: true, effort: 6 })`：像素画无损反而比有损更小，且边缘不糊。
- 内置素材直接放 `android/app/src/main/assets/packs/deepy/`（`pack.json` + 4 个 webp，会被打进 APK）。
- 要给别的设备手动导入时再打包：`Compress-Archive -Path android/app/src/main/assets/packs/deepy/* -DestinationPath deepy.zip -Force`，zip 根目录直接是 `pack.json` + 4 个 webp（导入逻辑只认这个结构）。
- 当前结果：336×352 → 224×224，帧数 48 / 32 / 64 / 48 不变，四文件 66 KB → 54 KB。

### 4.1 App 图标

图标是 `sleeping.webp` 第 32 帧（闭眼 + 白眼罩）生成的，脚本在 `android/tools/make-icon.cjs`：

```powershell
cd $env:TEMP\deepy-tools          # 刚才装 sharp 的目录
node <repo>/android/tools/make-icon.cjs
```

它做四件事：抽帧 → 抠掉右上角 Zzz 与左侧气泡圈 → 裁掉透明边 → 按 `mipmap-mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi` 出前景位图（108 / 162 / 216 / 324 / 432 px，最近邻缩放，本体占画布 `--ratio`，默认 0.56）。

- `--ratio` 别再往上加：0.68 那版装机后尾巴会被桌面圆角遮罩切掉，0.56 才安全。
- 背景色不在这里生成，改 `res/mipmap-anydpi-v26/ic_launcher.xml` 引用的 `@color/deep_bg`。
- 想保留 Zzz / 气泡用 `--keep-zzz` / `--keep-bubble`，仅建议 144 px 以上的场景。

---

## 5. 提交前检查

1. `:app:assembleDebug` 通过 + 真机装一次看一眼（截图确认，别只看编译通过）。
2. `node --check` 三个 JS + 管理页内联脚本。
3. 文档同步：`TODO.md` 加一条进度、`HANDOFF.md` 加一节取舍、`android/README.md` 3.10 更新形象/素材描述。
