package com.dsh.channel.whale

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.channel.whale.net.AudioSpec
import com.dsh.channel.whale.net.LanDiscovery
import com.dsh.channel.whale.net.PluginClient
import com.dsh.channel.whale.net.SettingsStore
import com.dsh.channel.whale.net.WsSession
import com.dsh.channel.whale.ui.AvatarPack
import com.dsh.channel.whale.ui.AvatarPacks
import com.dsh.channel.whale.voice.CallSpeechInput
import com.dsh.channel.whale.voice.SpeechInput
import com.dsh.channel.whale.voice.StreamingPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

enum class Emotion(val key: String) {
    NEUTRAL("neutral"),
    HAPPY("happy"),
    SAD("sad"),
    ANGRY("angry"),
    SURPRISED("surprised"),
    THINKING("thinking"),
    SLEEPY("sleepy"),
    ERROR("error");

    companion object {
        fun parse(value: String?): Emotion = entries.firstOrNull { it.key == value } ?: NEUTRAL
    }
}

enum class Phase { IDLE, LISTENING, THINKING, SPEAKING }

data class UiState(
    val phase: Phase = Phase.IDLE,
    val emotion: Emotion = Emotion.NEUTRAL,
    val subtitle: String = "",
    /** 字幕已经「念」到第几个字：由播放头推进，音画同步就靠它。 */
    val captionChars: Int = 0,
    val status: String = "轻触开始通话",
    /** 是否处于通话（麦克风常驻打开，说的话照常排队）。 */
    val callActive: Boolean = false,
    val showSettings: Boolean = false,
    val endpointInput: String = "",
    val pinInput: String = "",
    /** 本次连接是否已被桌面端确认（不是「填过地址」）。 */
    val reachable: Boolean = false,
    val found: List<LanDiscovery.Found> = emptyList(),
    val scanning: Boolean = false,
    val message: String? = null,
    /** assets/packs 里扫到的形象包。 */
    val packs: List<AvatarPack> = emptyList(),
    /** 当前形象包 id；默认是内置的 Deepy。 */
    val packId: String = AvatarPacks.BUILT_IN,
    /** 页面背景色：取当前素材的底色，素材和背景同色才融得进去。 */
    val tint: Int = DEFAULT_TINT,
) {
    companion object {
        const val DEFAULT_TINT = 0xFF0B1020.toInt()
    }
}

/**
 * 通话状态机：一条 WS 长连接跑完「说话 → 识别 → 对话 → 朗读」。
 *
 * 与服务端的分工（xiaozhi 协议，见 docs/ws-protocol.md）：
 * - 上行：本地能量 VAD 断句，每句发 `listen start` + PCM + `listen stop`；
 * - 服务端：识别 → DSH 会话 → 逐句合成 → 按 60 ms 节奏推 PCM，同时下发
 *   `stt` / `llm` / `tts start|sentence_start|stop`；
 * - 播放：AudioTrack 流式边收边播，字幕跟播放头走（[StreamingPlayer]）。
 *
 * 打断规则：朗读不打断。说话时照常识别，服务端把新回复排在当前音频后面播；
 * 只有挂断 / 服务端异常才停声。
 */
class WhaleViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val health = PluginClient(store)
    private val discovery = LanDiscovery()

    /** 麦克风开关必须离开主线程（AudioRecord + 采集源探测，最坏 500 ms）。 */
    private val speechWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "speech-lifecycle").apply { isDaemon = true }
    }

    private val player = StreamingPlayer(
        onCaption = { text, chars -> onCaption(text, chars) },
        onDrained = { onSpeechDrained() },
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val ws = WsSession(store)

    private val speech: SpeechInput = CallSpeechInput(
        app,
        sink = object : CallSpeechInput.Sink {
            override fun ready(): Boolean = ws.isReady
            override fun begin() = ws.beginUtterance()
            override fun write(pcm: ByteArray) = ws.sendAudio(pcm)
            override fun end() = ws.endUtterance()
        },
        playing = { player.isActive() },
    ) { event -> onSpeechEvent(event) }

    /** 通话中麦克风是否已经打开（WS 重连不要反复重启采集）。 */
    private var micStarted = false

    init {
        ws.listener = wsListener()
        val packs = AvatarPacks.load(getApplication())
        // 没选过形象就用内置的 Deepy；旧版本存下的空 id（当年代表内置 Eva）也归到 Deepy。
        val packId = if (store.packChosen) store.packId.ifBlank { AvatarPacks.BUILT_IN } else AvatarPacks.BUILT_IN
        _ui.update {
            it.copy(
                endpointInput = store.endpoint,
                pinInput = store.pin,
                packId = packId,
                packs = packs,
            )
        }
        viewModelScope.launch { refreshTint(packId) }
    }

    /** 切换形象包（内置的是 [AvatarPacks.BUILT_IN]，删不掉）。 */
    fun selectPack(id: String) {
        store.packId = id
        _ui.update { it.copy(packId = id) }
        viewModelScope.launch { refreshTint(id) }
    }

    /** 取素材底色当页面背景；取不到就用主题色。 */
    private suspend fun refreshTint(packId: String) {
        val pack = AvatarPacks.find(_ui.value.packs, packId)
        val tint = withContext(Dispatchers.IO) {
            pack?.let { AvatarPacks.tintOf(it) }
        } ?: UiState.DEFAULT_TINT
        _ui.update { it.copy(tint = tint) }
    }

    /** 导入 zip 形象包：校验 pack.json → 落到 filesDir/packs/<id> → 立即切过去。 */
    fun importPack(uri: Uri) {
        viewModelScope.launch {
            _ui.update { it.copy(message = "正在导入…") }
            val result = runCatching {
                withContext(Dispatchers.IO) { AvatarPacks.importZip(getApplication(), uri) }
            }
            result.fold(
                onSuccess = { pack ->
                    val packs = AvatarPacks.load(getApplication())
                    store.packId = pack.id
                    _ui.update {
                        it.copy(packs = packs, packId = pack.id, message = "已导入「${pack.name}」")
                    }
                    refreshTint(pack.id)
                },
                onFailure = { error ->
                    _ui.update { it.copy(message = error.message ?: "导入失败") }
                },
            )
        }
    }

    /** 删除素材包（含随包素材）；正在用时回退到内置 Deepy。内置包不允许删。 */
    fun deletePack(id: String) {
        if (id == AvatarPacks.BUILT_IN) {
            _ui.update { it.copy(message = "内置形象不能删除") }
            return
        }
        val pack = _ui.value.packs.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            val removed = runCatching {
                withContext(Dispatchers.IO) { AvatarPacks.delete(pack) }
            }.getOrDefault(false)
            if (!removed) {
                _ui.update { it.copy(message = "删除失败") }
                return@launch
            }
            val packs = AvatarPacks.load(getApplication())
            val nextId = if (_ui.value.packId == id) AvatarPacks.BUILT_IN else _ui.value.packId
            store.packId = nextId
            _ui.update {
                it.copy(packs = packs, packId = nextId, message = "已删除「${pack.name}」")
            }
            refreshTint(nextId)
        }
    }

    /** 重命名素材包（只改显示名，目录 id 不变，当前选中状态不受影响）。 */
    fun renamePack(id: String, name: String) {
        val pack = _ui.value.packs.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            val renamed = runCatching {
                withContext(Dispatchers.IO) { AvatarPacks.rename(pack, name) }
            }.getOrNull()
            if (renamed == null) {
                _ui.update { it.copy(message = "改名失败，名字不能为空") }
                return@launch
            }
            _ui.update {
                it.copy(packs = AvatarPacks.load(getApplication()), message = "已改名为「${renamed.name}」")
            }
        }
    }

    // ---- WS 事件（全部在主线程） ----

    private fun wsListener(): WsSession.Listener = object : WsSession.Listener {
        override fun onOpen(sessionId: String, audio: AudioSpec) {
            Log.d("dsh-dbg", "ws ready session=$sessionId audio=$audio")
            player.configure(audio.sampleRate)
            if (!_ui.value.callActive) return
            _ui.update {
                it.copy(reachable = true, phase = Phase.LISTENING, status = "在听你说…", message = null)
            }
            startMicIfNeeded()
        }

        override fun onStt(text: String) {
            if (!_ui.value.callActive) return
            Log.d("dsh-dbg", "stt len=${text.length} text=$text")
            val line = if (text.isBlank()) "没听清，再说一次" else "你：$text"
            _ui.update { state ->
                // 正在朗读时不抢字幕：这句话的回复会排在当前音频后面。
                if (state.phase == Phase.SPEAKING) {
                    state.copy(emotion = Emotion.THINKING)
                } else {
                    state.copy(
                        phase = Phase.THINKING,
                        emotion = Emotion.THINKING,
                        subtitle = line,
                        captionChars = line.length,
                        status = if (text.isBlank()) "没听清，再说一次" else "Deepy 正在想…",
                        message = null,
                    )
                }
            }
        }

        override fun onEmotion(emotion: String) {
            _ui.update { it.copy(emotion = Emotion.parse(emotion)) }
        }

        override fun onSpeechStart() {
            if (!_ui.value.callActive) return
            // 第一句还在合成（edge-tts 是在线服务，偶尔要十几秒），先别谎称「在说话」。
            _ui.update {
                if (it.phase == Phase.SPEAKING) it
                else it.copy(phase = Phase.SPEAKING, status = "正在准备回复…", message = null)
            }
        }

        override fun onSentence(text: String) {
            if (!_ui.value.callActive) return
            // 只登记句子边界：字幕在哪一刻切过去由播放头决定（见 StreamingPlayer）。
            _ui.update {
                if (it.phase == Phase.SPEAKING) it.copy(status = "Deepy 在说话…")
                else it.copy(phase = Phase.SPEAKING, status = "Deepy 在说话…", message = null)
            }
            player.mark(text)
        }

        override fun onAudio(pcm: ByteArray) {
            player.write(pcm)
        }

        override fun onSpeechStop() {
            player.finishSpeech()
        }

        override fun onAlert(message: String) {
            fail(message)
        }

        override fun onClosed() {
            if (!_ui.value.callActive) return
            Log.d("dsh-dbg", "ws closed, waiting reconnect")
            _ui.update {
                it.copy(
                    reachable = false,
                    status = "连接断开，正在重连…",
                    phase = if (it.phase == Phase.SPEAKING) it.phase else Phase.LISTENING,
                )
            }
        }
    }

    /** 字幕：句子文本 + 已经念到的字数（都由播放头折算，所以切换句子不会抢在声音前面）。 */
    private fun onCaption(text: String, chars: Int) {
        _ui.update {
            if (it.phase != Phase.SPEAKING) it else it.copy(subtitle = text, captionChars = chars)
        }
    }

    /** 队列播完：回聆听（通话中）。 */
    private fun onSpeechDrained() {
        _ui.update {
            when {
                it.callActive && it.phase == Phase.SPEAKING -> it.copy(phase = Phase.LISTENING, status = "在听你说…")
                !it.callActive && it.phase == Phase.SPEAKING -> it.copy(phase = Phase.IDLE, status = "轻触开始通话")
                else -> it.copy(captionChars = it.subtitle.length)
            }
        }
    }

    // ---- 通话 ----

    /** 通话开关：第一次点亮麦克风，再点挂断。权限由 Activity 保证后再调用。 */
    fun onCallToggle() {
        if (_ui.value.callActive) {
            endCall()
            return
        }
        startCall()
    }

    private fun startCall() {
        if (store.endpoint.isBlank()) {
            _ui.update { it.copy(showSettings = true, message = "请先连接桌面端") }
            return
        }
        micStarted = false
        _ui.update {
            it.copy(
                callActive = true,
                phase = Phase.LISTENING,
                emotion = Emotion.NEUTRAL,
                subtitle = "",
                captionChars = 0,
                status = "正在连接…",
                message = null,
            )
        }
        ws.connect()
    }

    private fun startMicIfNeeded() {
        if (micStarted) return
        micStarted = true
        speechWorker.execute { runCatching { speech.start() } }
    }

    private fun endCall(status: String = "轻触开始通话") {
        micStarted = false
        speechWorker.execute { runCatching { speech.stop() } }
        ws.close()
        player.stop()
        _ui.update {
            it.copy(
                callActive = false,
                phase = Phase.IDLE,
                emotion = Emotion.NEUTRAL,
                captionChars = it.subtitle.length,
                status = status,
            )
        }
    }

    private fun onSpeechEvent(event: SpeechInput.Event) {
        if (event !is SpeechInput.Event.Level) Log.d("dsh-dbg", "speech=$event")
        when (event) {
            SpeechInput.Event.Ready -> _ui.update {
                if (it.callActive) it.copy(status = "在听你说…") else it
            }
            SpeechInput.Event.SpeechStarted -> onUserSpeak()
            SpeechInput.Event.Recognizing -> Unit
            is SpeechInput.Event.Level -> Unit
            is SpeechInput.Event.Error -> fail(event.message)
            SpeechInput.Event.Finished -> {
                if (_ui.value.callActive) endCall("通话已结束")
            }
        }
    }

    /** 用户开口不再打断朗读：这句话照常上行、服务端排队，当前音频播完再播下一段。 */
    private fun onUserSpeak() {
        Log.d("dsh-dbg", "user spoke, phase=${_ui.value.phase}")
    }

    /** 通话中失败只提示、继续听；没有通话时回待机。 */
    private fun fail(message: String) {
        _ui.update {
            val shown = it.copy(captionChars = it.subtitle.length)
            if (shown.callActive) {
                shown.copy(status = "在听你说…", message = message)
            } else {
                shown.copy(phase = Phase.IDLE, emotion = Emotion.ERROR, status = message, message = message)
            }
        }
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    fun openSettings(show: Boolean) {
        _ui.update {
            it.copy(
                showSettings = show,
                endpointInput = store.endpoint,
                pinInput = store.pin,
            )
        }
    }

    fun editEndpoint(value: String) = _ui.update { it.copy(endpointInput = value) }
    fun editPin(value: String) = _ui.update { it.copy(pinInput = value) }

    fun scan() {
        if (_ui.value.scanning) return
        _ui.update { it.copy(scanning = true, found = emptyList(), message = null) }
        viewModelScope.launch {
            val result = runCatching {
                discovery.scan { found ->
                    _ui.update { state ->
                        state.copy(found = (state.found + found).distinctBy { it.host })
                    }
                }
            }
            _ui.update { state ->
                val found = result.getOrNull()
                state.copy(
                    scanning = false,
                    message = when {
                        result.isFailure -> "搜索失败，请手动填写地址"
                        found.isNullOrEmpty() -> "没找到设备，可手动填写地址"
                        else -> "找到 ${found?.size ?: 0} 台设备"
                    },
                )
            }
        }
    }

    fun connect() {
        val endpoint = _ui.value.endpointInput
        if (endpoint.isBlank()) {
            _ui.update { it.copy(message = "请填写桌面端地址") }
            return
        }
        store.endpoint = endpoint
        store.pin = _ui.value.pinInput
        _ui.update { it.copy(reachable = false, endpointInput = store.endpoint, message = "正在连接…") }
        viewModelScope.launch {
            val result = health.check()
            _ui.update { state ->
                state.copy(
                    reachable = result.isSuccess,
                    message = result.fold(
                        { "已连接" },
                        { "连接失败，请检查地址和配对码" },
                    ),
                )
            }
        }
    }

    override fun onCleared() {
        ws.close()
        speechWorker.execute { runCatching { speech.close() } }
        speechWorker.shutdown()
        player.release()
        super.onCleared()
    }
}
