package com.dsh.channel.whale.voice

/**
 * 语音输入契约：麦克风常驻打开，按句切分后交给 WS 上行（[CallSpeechInput]）。
 * 识别结果从连接上以 `stt` 回来，不走这里。所有事件统一投递到主线程。
 */
interface SpeechInput {
    sealed interface Event {
        /** 麦克风已打开，进入常驻监听。 */
        data object Ready : Event

        /** 检测到用户开口（已过防误触窗口）；用于 UI/日志，不打断朗读。 */
        data object SpeechStarted : Event

        /** 一段录音已结束，已交给 WS 上行。 */
        data object Recognizing : Event

        /** 当前帧音量（dBFS，每 200 ms 一次；UI 暂未消费）。 */
        data class Level(val rmsDb: Float) : Event

        /** 失败原因；通话模式下不终止会话，继续听。 */
        data class Error(val message: String) : Event

        /** 会话结束（[stop] 之后）。 */
        data object Finished : Event
    }

    /** 打开麦克风并常驻监听，直到 [stop]。 */
    fun start()

    /** 结束监听并释放麦克风。 */
    fun stop()

    fun close()
}
