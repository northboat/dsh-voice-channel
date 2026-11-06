package com.dsh.channel.whale

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.channel.whale.net.LanDiscovery
import com.dsh.channel.whale.net.PluginClient
import com.dsh.channel.whale.net.SettingsStore
import com.dsh.channel.whale.voice.CallSpeechInput
import com.dsh.channel.whale.voice.SpeechInput
import com.dsh.channel.whale.voice.TtsOutput
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val status: String = "轻触鲸鱼开始通话",
    /** 是否处于通话（麦克风常驻打开，可随时插话）。 */
    val callActive: Boolean = false,
    val showSettings: Boolean = false,
    val endpointInput: String = "",
    val pinInput: String = "",
    /** 本次连接是否已被桌面端确认（不是「填过地址」）。 */
    val reachable: Boolean = false,
    val found: List<LanDiscovery.Found> = emptyList(),
    val scanning: Boolean = false,
    val message: String? = null,
)

/**
 * 通话状态机：常驻监听 → 一句话识别 → 插件 NDJSON → 字幕 / 情绪 → 朗读。
 *
 * 打断规则（不需要按钮）：
 * - 朗读中检测到人声 → 立刻停朗读，接着把这句话当作新输入（[SpeechInput.Event.SpeechStarted]）；
 * - 思考中检测到人声 → 取消还在进行的请求，改听新的；
 * - 识别失败 / 网络失败只提示，不退出通话；麦克风起不来才退出。
 */
class WhaleViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val client = PluginClient(store)
    private val discovery = LanDiscovery()
    private var chatJob: Job? = null

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val tts = TtsOutput(
        app,
        endpoint = { store.endpoint },
        pin = { store.pin },
        deviceId = { store.deviceId },
        onDone = { onTtsDone() },
        onError = { message -> fail(message) },
    )

    private val speech: SpeechInput = CallSpeechInput(
        app,
        endpoint = { store.endpoint },
        pin = { store.pin },
        deviceId = { store.deviceId },
        playing = { tts.isSpeaking() },
    ) { event -> onSpeechEvent(event) }

    init {
        _ui.update {
            it.copy(
                endpointInput = store.endpoint,
                pinInput = store.pin,
            )
        }
    }

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
            _ui.update {
                it.copy(showSettings = true, message = "请先连接桌面端")
            }
            return
        }
        _ui.update {
            it.copy(
                callActive = true,
                phase = Phase.LISTENING,
                emotion = Emotion.NEUTRAL,
                subtitle = "",
                status = "正在打开麦克风…",
                message = null,
            )
        }
        speech.start()
    }

    private fun endCall(status: String = "轻触鲸鱼开始通话") {
        speech.stop()
        chatJob?.cancel()
        chatJob = null
        tts.stop()
        _ui.update {
            it.copy(
                callActive = false,
                phase = Phase.IDLE,
                emotion = Emotion.NEUTRAL,
                status = status,
            )
        }
    }

    private fun onSpeechEvent(event: SpeechInput.Event) {
        when (event) {
            SpeechInput.Event.Ready -> _ui.update {
                if (it.callActive) it.copy(phase = Phase.LISTENING, status = "在听你说…") else it
            }
            SpeechInput.Event.SpeechStarted -> onUserSpeak()
            is SpeechInput.Event.Result -> sendToPlugin(event.text)
            SpeechInput.Event.Recognizing -> Unit
            is SpeechInput.Event.Level -> Unit
            is SpeechInput.Event.Error -> fail(event.message)
            SpeechInput.Event.Finished -> {
                if (_ui.value.callActive) endCall("通话已结束")
            }
        }
    }

    /** 用户开口：朗读中就停朗读，思考中就撤请求，随后由本句识别结果接管。 */
    private fun onUserSpeak() {
        val state = _ui.value
        if (!state.callActive) return
        when (state.phase) {
            Phase.SPEAKING -> {
                tts.stop()
                _ui.update {
                    it.copy(phase = Phase.LISTENING, emotion = Emotion.NEUTRAL, status = "已打断，你继续说")
                }
            }
            Phase.THINKING -> {
                chatJob?.cancel()
                _ui.update {
                    it.copy(phase = Phase.LISTENING, status = "已打断，你继续说")
                }
            }
            else -> Unit
        }
    }

    private fun sendToPlugin(text: String) {
        chatJob?.cancel()
        _ui.update {
            it.copy(
                phase = Phase.THINKING,
                emotion = Emotion.THINKING,
                subtitle = "你：$text",
                status = "小鲸鱼正在想…",
                message = null,
            )
        }
        val reply = StringBuilder()
        var failed = false
        chatJob = viewModelScope.launch {
            client.chat(text).collect { event ->
                if (failed) return@collect
                when (event) {
                    is PluginClient.Event.Delta -> {
                        reply.append(event.text)
                        _ui.update { it.copy(subtitle = "鲸鱼：$reply") }
                    }
                    is PluginClient.Event.Mood ->
                        _ui.update { it.copy(emotion = Emotion.parse(event.emotion)) }
                    is PluginClient.Event.Done -> {
                        val full = event.text.ifBlank { reply.toString() }
                        _ui.update {
                            it.copy(
                                subtitle = if (full.isBlank()) "鲸鱼：…" else "鲸鱼：$full",
                                phase = Phase.SPEAKING,
                                status = "小鲸鱼在说话…（随口插话就能打断）",
                            )
                        }
                        tts.speak(full)
                    }
                    is PluginClient.Event.Failure -> {
                        failed = true
                        fail(event.message)
                    }
                    is PluginClient.Event.State -> Unit
                }
            }
        }
    }

    /** 朗读结束：通话中自动回到「听」，否则回待机。 */
    private fun onTtsDone() {
        _ui.update {
            when {
                !it.callActive && it.phase == Phase.SPEAKING ->
                    it.copy(phase = Phase.IDLE, status = "轻触鲸鱼开始通话")
                it.callActive && it.phase == Phase.SPEAKING ->
                    it.copy(phase = Phase.LISTENING, status = "在听你说…")
                else -> it
            }
        }
    }

    /** 通话中失败只提示、继续听；没有通话时回待机。 */
    private fun fail(message: String) {
        _ui.update {
            if (it.callActive) {
                it.copy(phase = Phase.LISTENING, status = "在听你说…", message = message)
            } else {
                it.copy(phase = Phase.IDLE, emotion = Emotion.ERROR, status = message, message = message)
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
            val result = client.health()
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
        chatJob?.cancel()
        speech.close()
        tts.shutdown()
        super.onCleared()
    }
}
