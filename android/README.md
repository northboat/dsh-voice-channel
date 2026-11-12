# dsh-voice-channel（Android client）开发记录

阶段 2 的 Android client：点进去只有一只鲸鱼、没有输入框，纯语音与局域网插件对话。

当前版本 **0.10.1**（`versionCode 11`）。架构：**手机只做「常驻录音 + 断句 + 播放」**，ASR 与 TTS 都在插件端；client 只发文本，音色 / 语速由服务端固定，本机既不初始化系统 TTS，也不带任何音色配置。debug APK **25.45 MiB**。

0.6.0 之前是「点一下说一句」的对讲机形态；0.7.0 改为**通话模式**：点一下进入通话后麦克风常驻打开，说完自动回到听，鲸鱼朗读时**直接开口就能打断**，不需要任何按钮。0.8.0 主页形象改成**可换的资源包**；0.9.0 修好「素材只有 1 帧 + `start()` 时机不对」导致形象不动的问题；0.10.0 内置形象换成线条 Eva，素材改成可拆装（导入 / 改名 / 删除 / 恢复内置），deepy 的卡片底板在素材层面抠掉、页面背景跟随素材底色。0.10.1 之后又收敛了一轮：**Eva 不再画头框、脖子、嘴巴和光晕，只留一双发光的眼睛，主页形象铺满整块屏幕**（见 3.10）。界面文案在 0.6.0 收敛过一次（设置页只留「地址 + 配对码 + 连接状态」），技术细节在 [`docs/app-client.md`](../docs/app-client.md)。

本文记录设计取舍、可复现命令和实现细节。整体路线见根目录 `TODO.md` / `HANDOFF.md`，插件侧见 `plugin/`。

---

## 1. 关键设计决策

### 1.1 问题：真机上没有系统语音识别服务

0.1.0 用框架 `SpeechRecognizer`。`adb install -r` 到 OPPO PLE110（Android 16）后，局域网搜索 / 连接插件已实测通过，但点「说话」直接失败：

```
adb shell settings get global voice_recognition_service              → null
adb shell pm query-services -a android.speech.RecognitionService     → 无结果
adb shell pm query-activities -a android.speech.action.RECOGNIZE_SPEECH → 无结果
```

该机既没有 `RecognitionService`，也没有 `RECOGNIZE_SPEECH` Activity（未装 `com.google.android.tts`）。当时补了 Android 11+ 的 `<queries>`、主屏错误卡片、设置里的诊断与安装入口——但这些只是「告诉用户去装引擎」，不解决「开箱即用」。

输出侧反而是好的：`com.oplus.ttsaccessibilityengine` 存在，`TextToSpeech.getVoices()` 返回 5 个音色。**阻塞只在输入侧**。

### 1.2 为什么不能把 Google 语音服务塞进 APK

| 维度 | 结论 |
| --- | --- |
| 合规 | `com.google.android.tts` 是 Google 专有组件，受 GMS / Play 服务条款约束，第三方不得随 APK 再分发。本仓库本身就在意许可边界（根 `README.md` 开源规范、`dsh-Go/ASSETS.md:3,19`）。 |
| 技术 | `SpeechRecognizer` 是 `PackageManager` 查**已安装**的 `android.speech.RecognitionService`。assets 里的 APK 没安装、没注册服务，框架完全看不见。 |
| 运行 | 它依赖 GMS 与联网下载模型，国行 ROM 未必能跑；即便装上，也丢了「大陆可用、纯离线」的目标。 |

所以必须换引擎，且不能自带 Google 组件。

### 1.3 三次演进与最终架构

| | 0.1.0 系统 ASR | 0.2.0 端侧 Vosk | **0.3.0+ 瘦客户端（当前 0.7.0）** |
| --- | --- | --- | --- |
| 识别在哪 | 系统 `RecognitionService` | 手机 native（Vosk） | **插件端（PC）** |
| APK | — | 116.06 MiB | **25.22 MiB** |
| 依赖 | 系统引擎（该机没有） | 无 | PC 在线（本来就必须） |
| 识别质量 | 不可用 | 中文小模型 CER 23.5~38.3% | 可换 PC 大模型（当前同模型，可升级） |
| 首启开销 | 无 | 解包 42 MB + 加载约 300 MB 内存 | 无 |
| 交互 | 点按说话 | 点按说话 | **0.7.0 起常驻监听 + 插话打断** |
| 结论 | 否决 | 可用但 APK 118 MB | **采用** |

0.2.0 的端侧方案确实能做到「不依赖系统服务、不联网」，但代价是 116 MiB 的包体、+68 MB 存储、模型常驻约 300 MB 内存、CPU 推理耗电。用户提出体积问题后重新审视，发现一个关键事实：

> **这个 App 本来就必须连上 PC 插件才能对话。** 没有插件时，「离线识别」只能听、不能答，离线价值是残缺的。

而 ESP32 客户端（`xiaozhi-esp32`）的做法正好相反——设备只做**信号处理 + 编解码 + 播放**，ASR / LLM / TTS 全在服务端：

```3:4:xiaozhi-esp32/main/audio/README.md
`AudioService` owns one input engine and keeps codec I/O, Opus encoding/decoding,
and network queues independent from the chip-specific speech pipeline.
```

```296:299:xiaozhi-esp32/docs/websocket_zh.md
8. **音频数据：二进制帧**
   - 当服务器发送音频二进制帧（Opus 编码）时，设备端解码并播放。
   - 若设备端正在处于 "listening" （录音）状态，收到的音频帧会被忽略或清空以防冲突。
```

注意区分两层：**音频编解码（Opus）在设备端**（`audio_service.cc` 里 `esp_opus_dec_decode`），**语音理解（ASR）与合成（TTS）在服务端**。设备上真正跑模型的只有唤醒词（WakeNet/MultiNet，几百 KB）和 VAD。

### 1.4 为什么最终选「ASR 在 server 侧」

| 方案 | APK 增量 | 识别质量 | 延迟 | 依赖 |
| --- | --- | --- | --- | --- |
| 端侧 Vosk（0.2.0） | +60 MiB（模型 42 + so 19） | 中 | 本地，低 | 无 |
| 端侧 sherpa-onnx / SenseVoice | +50~200 MiB | 较高 | 本地，低 | 无 |
| **插件端 ASR（0.3.0）** | **≈ 0** | **可随 PC 换大模型** | 多一次上传（局域网 ~10 ms + 解码 ~0.1 s） | PC 在线（必需） |
| 云端 ASR | ≈ 0 | 高 | 受网络影响 | 联网、隐私、费用 |

选插件端的理由：

1. **APK 从 116.06 MiB 降到 25.31 MiB**，且不需要在手机上做任何模型管理。
2. **模型升级不再受手机约束**：PC 上可以随时换 `vosk-model-cn-0.22`（1.3 GB，CER 13.98/27.30/7.43）或 SenseVoice，客户端零改动。
3. **识别不在热点路径**：原方案要占用手机 CPU 做解码，现在只上传一次 PCM。
4. **架构一致**：既然对话必须经过插件，把「听懂」也放过去，反而少一层不一致。
5. 许可不受影响：Vosk 与中文模型仍是 Apache-2.0，只是不再随 APK 分发（`plugin/models` 本地拉取，不入库）。

### 1.5 输出也搬到插件端（0.4.0）

0.3.0 保留系统 TTS 的理由是「音色一致性收益低于再修一条音频下行通道的成本」。0.4.0 推翻这一条，触发条件是要适配 ESP32：

- ESP32 跑不了中文神经 TTS，阶段 3 无论如何都需要插件端合成 + 音频下行；PC / 手机 / ESP32 共用一条 `/api/tts` 才不用维护两套逻辑。
- 一旦音频由插件端给，手机端的 `locale` / 引擎 voice / 「语音数据安装」入口全成死配置，所以 0.4.0 直接删掉系统 `TextToSpeech` 与「系统 TTS 音色」列表，而不是留着当第二套并列选项。
- 代价：手机必须连得上 PC 且 **PC 能联网**（Edge 在线语音）；首句约 2 s 出声；PC 不可达时没有语音，失败面与 ASR 一致，不做额外降级。

音色包因此从「本机 TTS 参数（locale / 引擎 voice / 音调倍率 / 语速倍率）」变成「发给 `/api/tts` 的预设（voice 名 + 语速百分比 + 音调 Hz）」。

**0.5.0 再砍一层**：连这套预设也删了。理由与 ASR 一致——client 既不知道 PC 用的哪个识别模型，也没必要知道哪个音色；这音频在当前链路里的消费方是模型 / 下游分析，不是挑音色的人耳。所以 client 只发 `{"text"}`，音色与语速由服务端（`DSH_TTS_VOICE`，默认 `zh-CN-XiaoxiaoNeural`、`+0%`、`+0Hz`）固定。删掉的连带项：`VoicePack` / `VoicePackRepository` / 音色包下载与 Zip Slip 校验 / `assets/voices/voice-packs.json` / `SettingsStore.voicePackId` / 唤醒音效（原属音色包，内置包本来都没配）。设置页只剩「语音能力 + 局域网插件」两节。

如果哪天要给人耳挑音色，服务端 `/api/tts` 的 `voice/rate/pitch` 参数仍在（网页演示页就在用），恢复只需在 client 加一个下拉并把参数带上。

### 1.5.1 通话模式：常驻监听 + 打断（0.7.0）

对讲机模型（点一下、说一句、等回复）在真实对话里很割裂：每轮都要摸手机，插话只能等鲸鱼说完。0.7.0 改成**一次进入通话，麦克风常驻打开**，说话即输入，鲸鱼朗读时直接开口就打断。

打断的难点只有一个：**如何分清「用户说话」和「自己的扬声器」**。三层防护：

| 层 | 做法 | 作用 |
| --- | --- | --- |
| 采集 | `VOICE_COMMUNICATION` + `AcousticEchoCanceler` / `NoiseSuppressor` | 平台级回声消除，最理想 |
| 判定 | 朗读期间阈值抬到 `max(噪声峰值×3.5, 700)`，且要连续 320 ms 才算人声 | 单个噪声帧不会误打断 |
| 兜底 | 朗读期间起步、又始终没被判为人声的片段直接丢弃，不送识别 | 避免把自己的回声当成用户的话再问一遍模型 |

开口前保留 300 ms 预录，所以打断后的头几个字不会丢。

**实测结论（重要）**：这台 OPPO PLE110 / Android 16 上 `MediaRecorder.AudioSource.VOICE_COMMUNICATION` **返回全 0 采样**（RMS 恒为 0，等于没数据），`VOICE_RECOGNITION` 正常（静音底噪 RMS ≈ 10、peak ≈ 35，说话 peak 数百到上千）。当前代码因此固定用 `VOICE_RECOGNITION`，**拿不到平台 AEC**——打断只能靠「高阈值 + 持续时长」这条软防线，嘈杂环境或外放音量很大时存在自打断风险，尚未真机验收。

### 1.6 体积账

0.3.0 的 APK 构成（解压后字节）：

| 部分 | 0.2.0 | 0.3.0 |
| --- | --- | --- |
| `classes*.dex` | 55.2 MiB | 24.6 MiB |
| `assets/asr/*`（Vosk 模型） | 41.9 MiB | **0** |
| `lib/{arm64-v8a,armeabi-v7a}`（`libvosk.so` + `libjnidispatch.so`） | 18.4 MiB | **0** |
| res / 其他 | 约 0.5 MiB | 约 0.7 MiB |
| 合计 | 116.06 MiB | **25.31 MiB** |

dex 的 30 MiB 降幅来自两点：去掉 `material-icons-extended`（AAR 34.1 MiB，而全项目只用了 `Mic` / `Settings` 两个图标，它们本可在 core 里，`Mic` 甚至只在 extended），以及移除 Vosk/JNA。

### 1.7 依赖与许可

| 组件 | 位置 | 许可 | 说明 |
| --- | --- | --- | --- |
| `vosk`（PyPI 0.3.45） | `plugin/.venv` | Apache-2.0 | PC 侧识别引擎；`pip` 预编译 wheel，无需 C++ 工具链 |
| `vosk-model-small-cn-0.22` | `plugin/models` | Apache-2.0（`alphacephei.com/vosk/models` Chinese 段） | 43,898,754 B，SHA-256 `3AF8B0E7E0F835AE9D414CE5DF580237A3CFB08D586C9FBBB0F7FF29AD5B14BA`，解压 65.1 MB |
| `com.alphacephei:vosk-android` | 已移除 | — | 0.2.0 使用，0.3.0 起不再进 APK |
| `com.google.android.tts` | 不使用 | — | 专有组件，不随包分发、不引导安装 |

两者都在本地目录、已被 `.gitignore` 忽略，仓库不分发模型二进制。

### 1.8 代价与已知边界

- **必须 PC 在线**：插件没起、PC 没开，就只能录音不能识别。这本来也是对话的前提。
- **首请求慢**：模型加载约 580 ms，加第一条真实语音解码约 1.5 s，首次端到端约 4.6 s；之后约 0.27 s / 3 s 音频。
- **小模型同音字**：`请把空调调到二十六度` → `请把空调条道二十六度`（调→条、到→道）。这是 42 MB 小模型的水平，换 PC 侧大模型可解。
- **需要重载插件**：`/api/asr` 路由在 `index.js`，而 `index.js` 只在插件加载时执行一次（`chat.js` 才是每次请求动态 import）。所以升级后**要重载一次插件（或重启 Desktop）**，之后一直有效。
- **VAD 仍是能量法**：判据从帧 RMS 换成**帧内峰值**（100 ms 帧的 RMS 会把音节平均掉，远场说话很容易漏），噪声底用「标定取最小值 + 快降慢升」自适应。强噪声下仍可能把噪声当人声或提前断句。
- **无平台 AEC（本机）**：`VOICE_COMMUNICATION` 在该 ROM 不可用，朗读期间的自打断只能靠阈值兜底，见 1.5.1。
- **无流式**：要等一句话说完才上传，没有边说边出字。要做流式得改成分片上传 + 服务端增量解码。
- **通话不跨后台**：没有前台服务 / 唤醒词，切后台或锁屏后系统会掐掉采集；通话只在页面内有效。
- 只带中文模型；`start(locale)` 的 `locale` 目前只用于 TTS 音色，不影响 ASR。
- 独立 ASR 服务（8788）只适合本机调试：它是独立的 `node.exe`，与 `DeepSeek Harness.exe` 是不同的可执行文件，Windows 防火墙默认不放行，真机连不上。**生产路径是插件同端口的 `/api/asr`。**

---

## 2. 命令

### 2.1 环境

| 项 | 本机实际值 |
| --- | --- |
| JDK | 17.0.7（`D:\env\java\jdk17`） |
| Gradle | 8.11.1（`C:\Users\northboat\.gradle\wrapper\dists\gradle-8.11.1-bin\bpt9gzteqjrbo1mjrsomdt32c\gradle-8.11.1\bin\gradle.bat`） |
| AGP / Kotlin | 8.8.2 / 2.0.21 |
| Android SDK | `D:\Env\AndroidSDK`（`platforms/android-35`、`build-tools/35.0.0`）。原 `archive/build-cache/sdk` 已不存在，`compileSdk` 随之从 34 提到 **35**（`targetSdk` 仍 34） |
| adb | `D:\Env\AndroidSDK\platform-tools`，已加入用户 PATH，直接 `adb` 即可 |
| Node | v22.23.2（插件运行时） |
| Python | 3.12.14（`plugin/.venv` 由它创建） |
| 真机 | OPPO PLE110 / Android 16，`3B659P001H900000` |

项目不含二进制 `gradle-wrapper.jar`；Android Studio 打开 `android/` 时在 `local.properties` 写 `sdk.dir`。

### 2.2 PC 侧 ASR 准备（一次性）

```powershell
Set-Location 'e:\File\Repo\dsh-android\plugin'

# 1) 独立 venv（系统 Python 由 uv 管理，不能直接 pip install）
python -m venv .venv
& '.venv/Scripts/python.exe' -m pip install --upgrade pip vosk

# 2) 模型：解压到 plugin/models/（也可用官方 zip 直接解压）
& '.venv/Scripts/python.exe' -c "import zipfile; zipfile.ZipFile(r'vosk-model-small-cn-0.22.zip').extractall(r'models')"

# 3) 验证引擎
& '.venv/Scripts/python.exe' -c "from vosk import Model; print(Model(r'models/vosk-model-small-cn-0.22').__class__)"
```

模型目录结构（`Model(path)` 需要的是这个目录）：

```
plugin/models/vosk-model-small-cn-0.22/
├── am/final.mdl
├── conf/{mfcc.conf,model.conf}
├── graph/{Gr.fst,HCLr.fst,disambig_tid.int,phones/word_boundary.int}
└── ivector/{final.dubm,final.ie,final.mat,global_cmvn.stats,online_cmvn.conf,splice.conf}
```

可选环境变量：

| 变量 | 作用 |
| --- | --- |
| `DSH_ASR_MODEL` | 模型目录，默认 `plugin/models/vosk-model-small-cn-0.22` |
| `DSH_ASR_PYTHON` | Python 解释器，默认 `plugin/.venv/Scripts/python.exe` |
| `ASR_PORT` / `ASR_HOST` | 独立 ASR 服务的监听地址，默认 `0.0.0.0:8788` |

### 2.3 编译与安装

```powershell
$env:ANDROID_HOME = 'D:\Env\AndroidSDK'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
Set-Location 'e:\File\Repo\dsh-android\android'
& 'C:\Users\northboat\.gradle\wrapper\dists\gradle-8.11.1-bin\bpt9gzteqjrbo1mjrsomdt32c\gradle-8.11.1\bin\gradle.bat' :app:assembleDebug --no-daemon

$apk = 'e:\File\Repo\dsh-android\android\app\build\outputs\apk\debug\app-debug.apk'
adb install -r $apk
adb shell dumpsys package com.dsh.channel.whale | Select-String 'versionCode|versionName'
```

体积核对：

```powershell
(Get-Item $apk).Length            # 0.7.0 为 26,441,113
& 'D:\Env\AndroidSDK\build-tools\35.0.0\aapt2.exe' dump badging $apk
& 'D:\Env\AndroidSDK\build-tools\35.0.0\apksigner.bat' verify --verbose $apk
```

### 2.4 启动 ASR

生产路径（推荐）：**重载插件**后 `/api/asr` 与 `/api/chat` 同端口，手机只认一个地址。

本机调试路径（不重载插件）：

```powershell
Set-Location 'e:\File\Repo\dsh-android\plugin'
node asr-server.mjs            # http://0.0.0.0:8788/api/asr
```

### 2.5 ASR 自测

```powershell
# 静音 PCM：应返回 ok=true / text=""
$pcm = New-Object byte[] 32000
Invoke-WebRequest -Uri 'http://127.0.0.1:8787/api/asr' -Method Post `
  -Body $pcm -ContentType 'application/octet-stream' -UseBasicParsing

# 真实中文 WAV（用系统 SAPI 合成一条）
Add-Type -AssemblyName System.Speech
$s = New-Object System.Speech.Synthesis.SpeechSynthesizer
$s.SelectVoice('Microsoft Huihui Desktop')
$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000,
  [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,
  [System.Speech.AudioFormat.AudioChannel]::Mono)
$s.SetOutputToWaveFile("$env:TEMP\asr-test.wav", $fmt)
$s.Speak('你好，今天天气怎么样'); $s.Dispose()

Invoke-WebRequest -Uri 'http://127.0.0.1:8787/api/asr' -Method Post `
  -InFile "$env:TEMP\asr-test.wav" -ContentType 'audio/wav' -UseBasicParsing
# → {"ok":true,"text":"你好今天天气怎么样",...}
```

`/api/health` 会带 `asr` 字段，可看引擎状态、模型路径、加载耗时、请求数与上一条解码耗时。

### 2.6 真机诊断

```powershell
adb shell am force-stop com.dsh.channel.whale
adb shell am start -n com.dsh.channel.whale/.MainActivity
adb logcat -d -v brief | Select-String 'AndroidRuntime|FATAL'
adb shell run-as com.dsh.channel.whale cat shared_prefs/dsh-voice-channel.xml   # 看 endpoint / pin
adb shell uiautomator dump /sdcard/u.xml; adb shell cat /sdcard/u.xml
adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png "$env:TEMP\s.png"
```

网络排查（重要）：

```powershell
adb shell ip -o addr | Select-String 'inet '            # 手机有没有 wlan0
adb shell 'toybox nc -w 4 10.181.97.185 8787 </dev/null; echo rc=$?'
```

- 手机**必须和 PC 在同一网段**（实测中手机曾关闭 WiFi、只走蜂窝 + USB 共享，连 8787 都不通）。
- 同 WiFi 段已实测可通（2026-11-07，手机 `10.181.97.253` → PC `10.181.97.185:8787`，`nc` 返回 0 且 `/api/asr` 有响应）。此前「Windows 防火墙拦入站」的结论只适用于 USB 共享网段（`10.86.196.x`，8787 跑在 `DeepSeek Harness.exe` 里，独立的 `node.exe` 没有放行规则）。
- `adb reverse` 在这台 ROM 上能建连但不转发（`nc` 返回 0 却收不到数据），不要依赖它。

### 2.7 重新获取模型

```powershell
$dst = 'e:\File\Repo\dsh-android\plugin\models\vosk-model-small-cn-0.22.zip'
curl.exe -L -C - --retry 5 --retry-all-errors -o $dst `
  'https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip'
Get-FileHash $dst -Algorithm SHA256   # 3AF8B0E7…B14BA
& '.venv/Scripts/python.exe' -c "import zipfile; zipfile.ZipFile(r'$dst').extractall(r'models')"
```

`alphacephei.com` 单连接速度一般，`Invoke-WebRequest` 中途断流会留下截断 zip；用 `curl -C -` 续传后必须核对大小与 SHA-256。

---

## 3. 实现细节

### 3.1 模块

| 文件 | 职责 |
| --- | --- |
| `MainActivity.kt` | 唯一 Activity；Compose 宿主；`RECORD_AUDIO` 运行时权限 |
| `WhaleViewModel.kt` | 通话状态机 `IDLE / LISTENING / THINKING / SPEAKING` + `callActive`；打断决策 |
| `voice/SpeechInput.kt` | 语音输入接口 + `Event` 契约（常驻监听） |
| `voice/CallSpeechInput.kt` | 瘦客户端实现：常驻 `AudioRecord` + 峰值 VAD 切句 + 异步上传 |
| `net/AsrClient.kt` | `POST /api/asr`，带 PIN 与 `device-id` |
| `voice/TtsOutput.kt` | 插件端 TTS 播放：`MediaPlayer` + `generation` 打断，无本机合成 |
| `net/TtsClient.kt` | `POST /api/tts`（只发 `text`），mp3 落 cache 目录 |
| `net/PluginClient.kt` | `POST /api/chat` NDJSON 流；`GET /api/health` |
| `net/LanDiscovery.kt` | 本机 `/24` 扫 8787 + `/api/health` |
| `net/SettingsStore.kt` | SharedPreferences：endpoint / pin / 稳定 `device-id` |
| `ui/WhaleScreen.kt` | 主屏；`MicGlyph` 用 Canvas 自绘，避免扩展图标库 |
| `ui/SettingsSheet.kt` | 设置弹窗：地址 / 配对码 / 搜索 / 连接状态（不写技术说明） |
| `ui/AvatarPack.kt` | 形象包扫描与解析（`assets/packs/<id>/pack.json`） |
| `ui/WhaleAvatar.kt` | 按情绪取包内动画；无素材时画内置 Eva（全屏发光眼） |

插件侧新增：

| 文件 | 职责 |
| --- | --- |
| `plugin/lib/asr_worker.py` | 常驻 Python 进程：加载一次模型，逐条解码 |
| `plugin/lib/asr.js` | Node 侧管理：懒启动、串行队列、超时、崩溃重启 |
| `plugin/lib/asr_http.js` | `/api/asr` 的共用 HTTP 处理（PIN、二进制读取、WAV 解析） |
| `plugin/asr-server.mjs` | 独立 ASR 服务（8788），用于本机调试 |

### 3.2 语音链路

```
MainActivity（RECORD_AUDIO 权限）
  └─ WhaleViewModel.onCallToggle()            # 点亮 = 开始通话，再点 = 挂断
       ├─ Phase → LISTENING、callActive = true
       └─ CallSpeechInput.start()             # 麦克风常驻，直到挂断
            ├─ AudioRecord(16 kHz / mono / PCM16, VOICE_RECOGNITION)
            ├─ capture 线程：逐帧 100 ms → 峰值 VAD → 切句
            │    ├─ 连续 200 ms 有声 → CANDIDATE 转 SPEECH、emit SpeechStarted
            │    ├─ 700 ms 静音或 15 s 上限 → 切出一段 PCM
            │    └─ 朗读中起步却没被判定为人声 → 丢弃（自己的回声）
            └─ asr 线程（单线程池）：AsrClient.transcribe() → POST <endpoint>/api/asr
                 └─ emit Result → WhaleViewModel.sendToPlugin(text)
                      └─ PluginClient.chat() NDJSON
                           ├─ delta   → subtitle = "鲸鱼：…"
                           ├─ emotion → 换鲸鱼动画
                           └─ done    → TtsOutput.speak(full)         # 只发文本
                                          └─ TtsClient.synthesize() → POST <endpoint>/api/tts → mp3
                                               └─ MediaPlayer 播放 → onCompletion → Phase.LISTENING（通话中）

打断（SpeechStarted）
  ├─ 正在 SPEAKING → TtsOutput.stop() + Phase → LISTENING
  └─ 正在 THINKING → chatJob.cancel() + Phase → LISTENING
     两者都把这段话当新输入，识别完照常走上面那条链路。
```

采集与识别在两条线程上，识别耗时（PC 侧 0.3~3 s）不会挡住继续听；`stop()` 递增 `generation`，晚到的结果按代丢弃。

事件契约（0.7.0：`Partial` 删除，新增 `SpeechStarted`）：

| 事件 | 含义 |
| --- | --- |
| `Ready` | 麦克风已打开，进入常驻监听 |
| `SpeechStarted` | 用户开口（已过防误触窗口），上层据此打断朗读 / 撤请求 |
| `Result` | 一句的最终文本（空文本不上抛） |
| `Level` | 当前帧 RMS 转 dB（每 200 ms，UI 暂未消费） |
| `Error` | 失败原因（未连插件、麦克风不可用、插件端识别失败）；通话中只提示不退出 |
| `Recognizing` | 一段录音已结束，等 PC 返回文本 |
| `Finished` | 采集线程结束（挂断或麦克风起不来） |

### 3.3 `/api/asr` 协议

```
POST /api/asr
Content-Type: audio/pcm; rate=16000; channels=1; encoding=s16le   （或 audio/wav）
Authorization: Bearer <PIN>   /  X-PIN: <PIN>
device-id: <UUID>

body: s16le / 16 kHz / mono 裸 PCM，或同格式 WAV（服务端从 RIFF 里取 data chunk）
resp: {"ok":true,"text":"你好今天天气怎么样","ms":67,"totalMs":84,
       "engine":"vosk-python","model":"…/vosk-model-small-cn-0.22"}
```

- loopback 直接放行；跨机必须带 PIN（与 `/api/chat` 同一枚）。
- 请求体上限 8 MiB（约 4 分钟 16 kHz PCM），超出 413。
- 二进制读取单独实现（`readBinary`），不能复用聊天那条按字符串拼接的 `readBody`。
- 奇数长度会截掉最后 1 字节，保证 s16 对齐。
- 服务端用 `res.end()` 一次性返回，不做流式；客户端超时 60 s。

### 3.4 PC 侧 worker：为什么复用 recognizer

最初的实现是「每条请求 new 一个 `KaldiRecognizer`」，实测每条固定 ~1.05 s，与音频长度无关。基准拆分后定位到**首次解码预热**：

```
model_load_ms=569
recognizer_create_ms=152
decode_1s_ms=1072      ← 首个 decode 要热一次
decode_3s_ms=172       ← 之后 RTF≈0.057
```

改成「进程内复用同一个 recognizer + 启动时喂 3 s 静音预热 + 每条请求 `Reset()`」后：

| 音频 | 耗时 |
| --- | --- |
| 1 s 静音 | 67 ms |
| 3 s 静音 | 144 ms |
| 5 s 静音 | 224 ms |
| 3.1 s 真实中文语音（稳态） | 268~282 ms |
| 3.1 s 真实中文语音（模型加载后第一条） | 1560 ms |

静音只覆盖少量解码状态；真实语音的**第一条**仍要约 1.5 s（解码状态扩张），之后稳定在约 0.27 s / 3 s 音频，RTF≈0.09。

worker 协议（stdin/stdout，二进制帧）：

```
request  = <uint32 id><uint32 nbytes><pcm s16le>
response = {"id":..,"ok":true,"text":"..","ms":123}\n
```

`asr.js` 负责懒启动、串行化（worker 单线程）、60 s 超时、进程退出时拒绝挂起请求。进程放在独立 Python 里而不是 Node 里，一是 `vosk` 的 npm 包依赖 `ffi-napi`，Windows 上要 Visual Studio C++ 工具链，装不上；二是同步 native 解码会阻塞 DSH 的事件循环。

中文空格归一化也放在服务端（`normalize()`）：Vosk 输出 `"你 好 今 天"`，服务端按「相邻 CJK 去空格、ASCII 保留空格」拼回，客户端不再处理文本。

### 3.5 VAD 与断句

| 参数 | 值 | 说明 |
| --- | --- | --- |
| `SAMPLE_RATE` | 16000 | `AudioRecord` 与上传格式统一 |
| `FRAME_SAMPLES` | 1600 | 每帧 100 ms |
| 判据 | 帧内**峰值** > 阈值 | RMS 会把 100 ms 里的音节平均掉，远场说话容易漏（0.7.0 前就是栽在这） |
| `CALIBRATION_MS` | 300 | 开头取峰值**最小值**当噪声底（平均值会被麦克风启动瞬态带偏） |
| 噪声底游走 | 快降慢升 | 安静帧 `noise*0.6+peak*0.4`，变吵只 `*0.98+*0.02`，下限 `NOISE_FLOOR=20` |
| 静音阈值 | `clamp(噪声底 × 3.5, 120, 2500)` | 实测静音峰值 ~35，远场说话峰值 200~800 |
| 朗读阈值 | `max(噪声底 × 3.5, 700)` | 播放期间抬高，防自己的回声误打断 |
| `ONSET_MS` / `ONSET_PLAY_MS` | 200 / 320 | 确认「开口」/「打断」所需的连续有声时长 |
| `MIN_SPEECH_MS` | 300 | 短于此的片段直接丢 |
| `SILENCE_MS` | 700 | 检测到人声后，连续静音这么久就断句 |
| `MAX_MS` | 15000 | 单句上限 |
| `PRE_ROLL_FRAMES` | 3 | 开口前保留 300 ms，打断后头几个字不丢 |

切出一段就投给识别线程，麦克风不释放、也不停止采集（这是和 0.6.0 最大的区别）；`stop()` 丢弃缓冲并递增代次。

### 3.6 局域网发现

`LanDiscovery`：

- 取本机第一个 `isSiteLocalAddress` 的 IPv4，取前三段拼 `/24`；
- 对 `1..254` 每 32 个一批并发探测 `http://<host>:8787/api/health`；
- `connectTimeout 400 ms` / `readTimeout 900 ms`，命中即回调，扫描中逐步刷新列表；
- 判定条件：`ok == true && pluginLoaded == true`，`backend` 仅用于展示。

不是 mDNS：跨网段、AP 隔离、防火墙都会让扫描失败，所以设置页同时保留手动填地址。

### 3.7 连接与认证

- `SettingsStore.normalize()`：允许输入 `192.168.1.7`、`192.168.1.7:8787`、`http://192.168.1.7:8787`，自动补 `http://` 与 `:8787`。
- `/api/health` **不需要 PIN**，用于发现与连通性检查；`/api/chat`、`/api/asr` 需要。
- PIN 同时放三个头，兼容插件侧的取法：

```
Authorization: Bearer <PIN>
X-PIN: <PIN>
device-id: <SharedPreferences 里的稳定 UUID>
```

- 明文 HTTP：`android:usesCleartextTraffic="true"`（仅局域网用途）。
- 与 `docs/technical-proposal.md` C5 的口径差异仍在：方案写的是「设备显示 `activation.code` → 绑定 `Device-Id` → 签发 token → WS 校验 Bearer」，实际是**预共享 PIN**；token 签发、设备白名单、限次 / 过期、WS 长连接都未实现。`device-id` 目前只用于插件侧统计已连接 client。

### 3.8 通信协议（chat）

`PluginClient.chat()` 用 `callbackFlow` + OkHttp 异步 `enqueue`，`awaitClose { call.cancel() }` 保证取消时断流。响应按行解析 NDJSON：

| `type` | 字段 | client 行为 |
| --- | --- | --- |
| `state` | `state` | 忽略 |
| `delta` | `text` | 追加到字幕 |
| `emotion` | `emotion` | `Emotion.parse()` → 鲸鱼动画，未知回退 `neutral` |
| `done` | `text` | 用完整文本定稿字幕，再 `tts.speak(text)` 走 `/api/tts`（只发文本） |
| `error` | `message` | 错误卡片，停止本轮 |

超时：连接 5 s、读 90 s。请求体 `{"text","sessionId"}`，`sessionId` 复用 `device-id`。

### 3.9 音色（服务端决定，client 不参与）

- 请求只带文本：

```json
{ "text": "鲸鱼要说的话" }
```

- 音色 / 语速 / 音调由插件端定：管理页 `/admin` 的「语音音色」卡片（存 `plugin/data/state.json` 的 `tts`，带试听），`DSH_TTS_VOICE` 环境变量可临时覆盖。client 零改动。
- 因此 client 侧没有音色包、没有下载入口、没有系统 voice 选择；`assets/voices/` 已删除。
- 插件端仍支持 `voice/rate/pitch` 参数（网页演示页在用），只是 Android 不发。
- 注意：若把 TTS 音频再喂回 ASR 做自测闭环，别把语速调快（`+10%` 以上识别率会掉）；默认 `+0%` 是识别友好值。

### 3.10 情绪资源（合规）

形象分两条路：**内置 Eva**（代码画，零素材）和**素材包**（一个目录一个包，`pack.json` 声明映射，同目录放 gif / webp 动画，用系统 `ImageDecoder` 解码）。

- 内置 `BuiltInEva`：**不画边框**——屏幕本身就是她的脸，只有一双 EVE 式的发光眼睛（没有嘴）。眼睛用 `bar()` 四层同形叠加冒充 blur（`EvaEyeHalo` α0.09 → `EvaEyeRim` α0.22 → `EvaEyeMid` → `EvaEyeCore` α0.38），`arcEye()` 画笑眼、`cross()` 画 error；情绪只改眼形（neutral 圆润光斑 / happy 上弯弧 / sad / angry / surprised / thinking / sleepy / error），说话与聆听时下方浮出声波。不依赖任何文件，素材删光也不会白屏。
- **形象铺满整屏**（`ui/WhaleScreen.kt`）：形象是根 `Box` 的第一层 `Modifier.fillMaxSize()`，字幕与通话按钮用 `align(BottomCenter) + bottom 152dp` 浮在脸上，不再用 `Modifier.size(260.dp)` 那种固定方框——否则「按屏幕比例铺开」全部失真。当前比例：眼距 ±0.205w、眼长 0.25w、眼高 0.10w、眼线在 0.40h。
- 素材是**完全外置**的：APK 里没有任何素材文件，只能从「导入素材」选 zip 添加，落到 `filesDir/packs/<id>/`；之后可以改名、同 id 覆盖导入、删除。样本包在 `assets/deepy/`（打好的 `deepy.zip` 也在 `assets/`），推到手机 `Download/` 即可导入。
- 背景跟随素材：`AvatarPacks.tintOf()` 取素材首帧边缘平均色作页面纯色背景（透明底素材返回 null 用主题色）。**这条路径别加渐变**，渐变会让素材四边重新显出轮廓。

```json
{
  "schema": 1,
  "id": "deepy",
  "name": "Deepy",                  // 设置页显示名
  "states": {                       // 动画名 → 文件
    "idle": "notification.webp",
    "notification": "notification.webp",
    "happy": "happy.webp",
    "sleeping": "sleeping.webp",
    "typing": "typing.webp"
  },
  "emotions": {                     // 情绪 → 动画
    "neutral": "idle",
    "happy": "happy",
    "thinking": "typing",
    "sleepy": "sleeping"
  },
  "fallback": "idle"                // 该情绪没素材时退到哪个动画
}
```

- `states` 是「动画名 → 文件名」，省略则按 `<动画名>.gif` 猜；
- `emotions` 是「情绪 key → 动画名」，情绪枚举见 `Emotion`（neutral / happy / sad / angry / surprised / thinking / sleepy / error）；
- 解析链：情绪 → 动画 → 文件 → 包内 `fallback` → **内置 Eva**。任何一环缺失都不会白屏。
- 设置页「形象」里列出 `filesDir/packs/` 下的所有包 + Eva，选择存 `SettingsStore.packId`；`packChosen` 为 false（从没手动选过）时默认用扫到的第一个包。
- **素材包播哪一帧不看 `emotion`，看通话阶段**（`ui/WhaleScreen.kt` 的 `packEmotion`）：没通话 → `sleeping`；开始通话 / 聆听 → `happy`；思考 → `typing`；说完 → 先 `notification`（包内 `idle`）2 秒再回 `happy`。内置 Eva 仍按插件下发的 `emotion` 改眼形，两条路互不影响。
- 样本素材已去掉底部的说明文字并逐帧裁成 **224×224**（动画 WebP，48 / 32 / 64 / 48 帧，合计 54 KB，zip 43 KB）；裁切脚本流程见 `docs/dev-guide.md` 第 4 节。

**动画播放**（`ui/WhaleAvatar.kt`）：

- `ImageDecoder.decodeDrawable` → `AnimatedImageDrawable`，`repeatCount` 强制 `REPEAT_INFINITE`（素材的 NETSCAPE 循环次数可能是 1）；
- 画面用 Compose `Canvas` 直接画 drawable（不再用 `ImageView`）：View 层绘制在 Compose 之上，边缘羽化/同色融合都盖不住它，看起来像贴图；
- 没有 View 就没人 invalidate，需要自建 `Drawable.Callback`（`invalidateDrawable` 里自增一个 state）来推进帧；
- **`start()` 要在有 callback 之后调用**：解码线程里调用时 `isRunning` 会是 true 但画面不动；
- 不用 Coil：它的解码器链先命中位图解码器，多帧 gif 只解出第一帧（显式挂 `decoderFactory` 也没绕过去）。

**素材去底板**（`android/tools/strip-avatar-bg.py`）：deepy 的卡片底板是在素材层面抠掉的——逐帧把 `#0D1117` / `#181D29` 一类的底色置为透明，输出带 alpha 的动画 WebP（4 个文件合计约 66 KB，原 GIF 374 KB）。依赖 Pillow：`python -m venv .venv-tools && .venv-tools/Scripts/pip install pillow`，然后 `python android/tools/strip-avatar-bg.py <素材目录> <输出目录>`。

之所以废除旧的 `<key>-zh-dark.gif` 命名约定：不同素材的帧率、画布尺寸、留白都不一样，硬编码后缀没法适配，交给打包者在 `pack.json` 里声明最省事。

**素材许可**：`dsh-Go/ASSETS.md:3,19` 明确美术素材不在 AGPL-3.0 范围内，仅限其仓库构建 / 测试 / 展示。`assets/deepy/`（4 个动画 WebP：`notification` 32 帧 / `happy` 48 帧 / `sleeping` 64 帧 / `typing` 48 帧，224×224，已裁掉底部中文说明）是样本素材，**不随 APK 分发**，只作为导入 zip 的示例放在仓库里；`pack.json` 标 `license: private-repo-only`，不对外分发、不用于发布的产品。

### 3.11 包可见性与体积约束

- 清单里已无 `<queries>`：既不用系统 TTS，也不查询 `RecognitionService`，也没有 `market://` 引导。
- 依赖只留 `material-icons-core`；`Mic` 自绘，`Settings` 仍用 core 图标。
- 不再有 `abiFilters` / `noCompress` / `assets/asr` / `assets/voices`。

---

## 4. 实测记录（2026-11-07，0.9.0）

**构建**

| 项 | 值 |
| --- | --- |
| 包名 / 版本 | `com.dsh.channel.whale` / `0.10.1`（code 11） |
| SDK | minSdk 28 / targetSdk 34 / compileSdk 35 |
| APK | release 26,515,676 B（**25.29 MiB**）；形象改版后 `:app:assembleDebug` 为 26,691,658 B（25.45 MiB） |
| 资产 | 无（APK 只带内置 Eva）；样本素材与导入包在仓库根 `assets/` |
| 原生库 | 无 |

**PC 侧 ASR**（0.3.0 起未变）

| 项 | 值 |
| --- | --- |
| 引擎 | `vosk` 0.3.45（PyPI）+ `vosk-model-small-cn-0.22`（解压 65.1 MB） |
| 模型加载 | 约 580 ms（只一次） |
| 中文合成语音 | SAPI `Microsoft Huihui` 合成「你好，今天天气怎么样」→ 识别 **`你好今天天气怎么样`**；「请把空调调到二十六度」→ `请把空调条道二十六度`（同音字错，小模型水平） |
| 延迟 | 静音基准 1 s → 67 ms、5 s → 224 ms；真实语音 3.1 s → 268~282 ms（首条 1560 ms） |

**通话模式真机实测（OPPO PLE110 / Android 16）**

| 项 | 结果 |
| --- | --- |
| 点一下开始通话 | 成功，状态「在听你说…」，系统出现录音指示 |
| `VOICE_COMMUNICATION` 采集 | **全 0**（RMS 恒为 0），该 ROM 对普通 App 不放这个源 → 结论：改用 `VOICE_RECOGNITION` |
| `VOICE_RECOGNITION` 采集 | 正常：静音 RMS ≈ 10 / peak ≈ 35；用户说话 peak 200~1200 |
| 首版 VAD（RMS 判据、阈值 350） | **完全没反应**：说话峰值 220 打不到 350，一帧都没触发上传 |
| 改峰值判据后 | 阈值收敛到 ~216（噪声底 ~62），说话帧判定正常 |
| 手机 → PC 8787 | 通（同 WiFi `10.181.97.x`，`nc` 返回 0；早期「防火墙拦截」的结论本轮不成立） |
| 端到端「说话 → 字幕 → 朗读」 | **未验证**：测试时用户无法出声，本条留待下次 |

> 诊断方法：在 `captureLoop` 里临时打 `Log.d("dsh-vad", "rms=… peak=… noise=… thr=… voiced=…")`，`adb logcat` 直接看阈值是否合适；问题定位后已删除。下次调 VAD 沿用这个办法，比猜快得多。

其余：`adb install -r` 通过，`dumpsys package` 显示 `versionCode=7 / versionName=0.7.0`；冷启动无崩溃，进入通话后系统出现录音指示；体积 25.22 MiB（较 0.2.0 的 116.06 MiB 降 78%）。

---

## 5. 构建产物

- `android/app/build/outputs/apk/debug/app-debug.apk`（25.22 MiB，debug 签名）。
- `plugin/models/`、`plugin/.venv/` 体积大且可重建，已 gitignore。
- 旧方案 `archive/out/dsh-android.apk`（`com.dsh.android` 0.2.0，58 KB）与本版无关。

---

## 6. 下一步

1. **通话闭环验收**（唯一阻塞）：说一句 → 确认「你：…」「鲸鱼：…」字幕与朗读；再说几句确认连续对话。
2. **打断验收**：鲸鱼朗读时开口，确认立刻停声且不吞掉新句子的开头；重点看会不会**自己打断自己**（无 AEC 的直接后果）。
3. **AEC 补救**：先试 `AudioManager.MODE_IN_COMMUNICATION` + `setSpeakerphoneOn(true)` 能否让 `VOICE_COMMUNICATION` 恢复采集；不行就换基于 WebRTC 的 AEC，或接受「朗读期间不打断」的半双工降级。
4. **流式识别**：现在是「说完再传」，可改成分片上传 + 服务端增量解码，或换成 sherpa-onnx 的 streaming 模型。
5. **模型升级**：PC 侧换 `vosk-model-cn-0.22`（1.3 GB）或 SenseVoice，客户端零改动；后者中文准确率明显更好。
6. **VAD 升级**：能量法在噪声环境会误判，可换 WebRTC / Silero VAD；顺带把 `Level` 接到鲸鱼呼吸动画。
7. **鉴权收口**：把预共享 PIN 换成方案里的 `activation.code → token`，补设备白名单与限次。
8. **待做项**：唤醒词、后台 / 锁屏保活、WS 长连接、结构化情绪。
