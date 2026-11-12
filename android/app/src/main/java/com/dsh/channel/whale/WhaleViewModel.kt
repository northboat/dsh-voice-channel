package com.dsh.channel.whale

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.channel.whale.net.LanDiscovery
import com.dsh.channel.whale.net.PluginClient
import com.dsh.channel.whale.net.SettingsStore
import com.dsh.channel.whale.ui.AvatarPack
import com.dsh.channel.whale.ui.AvatarPacks
import com.dsh.channel.whale.voice.CallSpeechInput
import com.dsh.channel.whale.voice.SpeechInput
import com.dsh.channel.whale.voice.TtsOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val status: String = "轻触开始通话",
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
    /** assets/packs 里扫到的形象包。 */
    val packs: List<AvatarPack> = emptyList(),
    /** 当前形象包 id；空 = 内置 Eva。 */
    val packId: String = "",
    /** 页面背景色：取当前素材的底色，素材和背景同色才融得进去。 */
    val tint: Int = DEFAULT_TINT,
) {
    companion object {
        const val DEFAULT_TINT = 0xFF0B1020.toInt()
    }
}

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

    /**
     * 麦克风开关必须离开主线程。
     *
     * `CallSpeechInput.start()` 里要切音频模式、开 `AudioRecord`、再采 5 帧判断这个采集源是否
     * 可用（最坏 500 ms），`stop()` 还要 join 工作线程；直接在点击回调里跑，就是「点了半天
     * 才有反应」。单线程队列保证 start/stop 不会交叉执行。
     */
    private val speechWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "speech-lifecycle").apply { isDaemon = true }
    }

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
        val packs = AvatarPacks.load(getApplication())
        // 没选过形象时直接用扫到的第一个包：装机后默认就该是包里的动态形象，
        // 否则新用户看到的永远是内置 Eva，和形象包没关系。
        val packId = if (store.packChosen) store.packId else packs.firstOrNull()?.id.orEmpty()
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

    /** 切换形象包；空字符串回到内置 Eva。 */
    fun selectPack(id: String) {
        store.packId = id
        _ui.update { it.copy(packId = id) }
        viewModelScope.launch { refreshTint(id) }
    }

    /** 取素材底色当页面背景；没有素材（内置 Eva）时回到主题色。 */
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

    /** 删除素材包（含随包素材）；正在用时回退到内置 Eva。 */
    fun deletePack(id: String) {
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
        // UI 先切到「在听你说…」，麦克风在后台线程慢慢开，点击不会有卡顿。
        speechWorker.execute { runCatching { speech.start() } }
    }

    private fun endCall(status: String = "轻触开始通话") {
        speechWorker.execute { runCatching { speech.stop() } }
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
                status = "Eva 正在想…",
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
                        _ui.update { it.copy(subtitle = "Eva：$reply") }
                    }
                    is PluginClient.Event.Mood ->
                        _ui.update { it.copy(emotion = Emotion.parse(event.emotion)) }
                    is PluginClient.Event.Done -> {
                        val full = event.text.ifBlank { reply.toString() }
                        _ui.update {
                            it.copy(
                                subtitle = if (full.isBlank()) "Eva：…" else "Eva：$full",
                                phase = Phase.SPEAKING,
                                status = "Eva 在说话…（随口插话就能打断）",
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
                    it.copy(phase = Phase.IDLE, status = "轻触开始通话")
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
        speechWorker.execute { runCatching { speech.close() } }
        speechWorker.shutdown()
        tts.shutdown()
        super.onCleared()
    }
}
