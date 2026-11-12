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
| `WhaleViewModel.kt` | 状态机 `Phase.IDLE / LISTENING / THINKING / SPEAKING` + `callActive`，打断决策 |
| `voice/CallSpeechInput.kt` | 常驻 `AudioRecord` + 峰值 VAD 切句 + 异步上传 |
| `net/PluginClient.kt`、`net/AsrClient.kt`、`net/TtsClient.kt` | 插件三件套 |
| `net/SettingsStore.kt` | SharedPreferences `dsh-voice-channel`：endpoint / pin / packId / deviceId |
| `ui/WhaleScreen.kt` | 主屏布局、形象取哪一帧、字幕与通话按钮 |
| `ui/WhaleAvatar.kt` | 素材包渲染（AnimatedImageDrawable + Canvas）与内置 Eva 绘制 |
| `ui/AvatarPack.kt` | 素材包扫描与解析（`filesDir/packs/<id>/pack.json`） |
| `ui/SettingsSheet.kt` | 设置弹层（地址、配对码、形象列表、导入/改名/删除） |

### 1.4 改形象

**内置 Eva**：`ui/WhaleAvatar.kt` 的 `drawEvaFace()`。

- 不画边框、不画嘴：屏幕就是脸，只有一双眼睛。
- `bar()` 是灯带眼（四层同形叠加冒充 blur：`EvaEyeHalo` α0.09 → `EvaEyeRim` α0.22 → `EvaEyeMid` → `EvaEyeCore` α0.38）；`arcEye()` 是笑眼；`cross()` 是 error。
- 尺寸全按屏宽比例：`eyeY = h*0.40`、`gap = w*0.205`、`eyeLen = w*0.25`、`eyeH = w*0.10`。改比例只动这几个数。
- 情绪映射：`when (emotion)` 一个块解决；眨眼 5.2 s 一次把光斑压扁。

**素材包**：`ui/AvatarPack.kt` + `pack.json`（格式见 [`android/README.md` 3.10](../android/README.md)）。

- 播哪一帧由 **通话阶段** 决定，在 `ui/WhaleScreen.kt` 的 `packEmotion`：
  没通话 → `SLEEPY`（sleeping）；通话中 → `HAPPY`；思考 → `THINKING`（typing）；说完 → 先 `NEUTRAL`（idle/notification）2 秒，再回 `HAPPY`。
- 内置 Eva 不吃这套，仍按插件下发的 `emotion` 改眼形（`WhaleAvatar(emotion=..., packEmotion=...)` 两个参数分开）。
- 想加新的阶段映射，只改 `packEmotion` 那个 `when`。

**布局**：形象是根 `Box` 的第一层 `Modifier.fillMaxSize()`，字幕与通话按钮 `align(BottomCenter) + bottom 152dp` 浮在上面。**不要再退回 `Modifier.size(260.dp)`**——固定方框会让所有「按屏幕比例」的画法失真。

### 1.5 手动把素材塞进手机（免走 SAF 导入）

```powershell
adb push assets/deepy/happy.webp assets/deepy/notification.webp assets/deepy/sleeping.webp assets/deepy/typing.webp assets/deepy/pack.json /data/local/tmp/
adb shell run-as com.dsh.channel.whale mkdir -p files/packs/deepy
adb shell run-as com.dsh.channel.whale cp /data/local/tmp/happy.webp /data/local/tmp/notification.webp /data/local/tmp/sleeping.webp /data/local/tmp/typing.webp /data/local/tmp/pack.json files/packs/deepy/
```

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

- 管理页改的是 `state.json`：角色列表（当前角色带「默认角色」角标）、声音、记忆路径、配对码、在线设备（每台一个角色下拉，改动即存）。
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

## 4. 素材处理（`assets/` → ZIP）

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
- 打包：`Compress-Archive -Path assets/deepy/* -DestinationPath assets/deepy.zip -Force`，zip 根目录直接是 `pack.json` + 4 个 webp（导入逻辑只认这个结构）。
- 当前结果：336×352 → 224×224，帧数 48 / 32 / 64 / 48 不变，四文件 66 KB → 54 KB，zip 43 KB。

---

## 5. 提交前检查

1. `:app:assembleDebug` 通过 + 真机装一次看一眼（截图确认，别只看编译通过）。
2. `node --check` 三个 JS + 管理页内联脚本。
3. 文档同步：`TODO.md` 加一条进度、`HANDOFF.md` 加一节取舍、`android/README.md` 3.10 更新形象/素材描述。
