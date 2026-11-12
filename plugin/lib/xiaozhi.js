// xiaozhi（小智）WebSocket 协议服务端：与 xiaozhi-esp32 设备固件完全对齐。
// 协议依据：`xiaozhi-esp32/docs/websocket_zh.md` + `main/protocols/websocket_protocol.cc`
//          + `main/application.cc`（OnIncomingJson / OnIncomingAudio）。
//
// 与官方服务端一致的行为：
//   hello →（设备）  hello（服务端，必须带 transport:"websocket" 与 audio_params）
//   listen start/stop/detect（设备）→ stt / llm / tts(start|sentence_start|stop) + 二进制音频
//   abort（设备）→ 立即停声，回 tts stop
// 音频：上行裸 Opus（16 kHz / 60 ms，v2/v3 带 8~16 字节头），下行 Opus 24 kHz / 60 ms。
//
// 扩展（只影响我们自己的 client，不破坏 xiaozhi）：设备可以在 hello 里声明
// `audio_params.format = "pcm16"`，此时上下行都用裸 PCM（Android 用这条，省掉 Opus 依赖）。

import { Segmenter, BufferCollector } from './vad.js'
import { SentenceSplitter, cleanForSpeech, detectEmotion } from './text.js'
import { createOpusCodec, toInt16, toBuffer } from './opus.js'
import { decodeMp3 } from './mp3.js'

const PROTOCOL = 1
const DOWNLINK_RATE = 24000
const DOWNLINK_FRAME_MS = 60
const HELLO_TIMEOUT_MS = 5000
const MAX_TEXT = 8 * 1024

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

/** 生产/消费之间的小队列：满了就等，abort 立即放行，避免生产者把内存撑爆。 */
class Pipeline {
  constructor(limit = 240) {
    this.items = []
    this.limit = limit
    this.done = false
    this.aborted = false
    this.waiter = null
  }

  wake() {
    const waiter = this.waiter
    this.waiter = null
    waiter?.()
  }

  async push(item) {
    while (!this.aborted && this.items.length >= this.limit) await sleep(20)
    if (this.aborted) return false
    this.items.push(item)
    this.wake()
    return true
  }

  close() {
    this.done = true
    this.wake()
  }

  abort() {
    this.aborted = true
    this.items = []
    this.wake()
  }

  async next() {
    for (;;) {
      if (this.aborted) return null
      if (this.items.length) return this.items.shift()
      if (this.done) return null
      await new Promise((resolve) => { this.waiter = resolve })
    }
  }
}

export class XiaozhiSession {
  /**
   * deps: {
   *   loadChat(): Promise<(text, {sessionId, clientId}) => AsyncIterable<string>>,
   *   asr, tts,
   *   noteClient(conn, deviceId),
   * }
   */
  constructor(conn, req, deps, onClose) {
    this.conn = conn
    this.req = req
    this.deps = deps
    this.onClose = onClose
    this.sessionId = `xiaozhi-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`
    this.startedAt = Date.now()

    const header = (name) => String(conn.headers?.[name] ?? '').trim()
    this.deviceId = (header('device-id') || header('x-client-id') || conn.remote || 'unknown').slice(0, 64)
    this.clientId = header('client-id').slice(0, 64)
    this.protocolHeader = header('protocol-version')

    this.helloDone = false
    this.version = 1
    this.format = 'opus'
    this.uplinkRate = 16000
    this.uplinkFrameMs = 60
    this.uplinkFrameSamples = 960
    this.decoder = null
    this.encoder = null

    this.listening = false
    this.mode = 'auto'
    this.segmenter = null
    this.collector = null
    this.queue = []
    this.pumping = false
    this.abortToken = 0
    this.speaking = false
    this.pipeline = null
    this.turns = 0
    this.utterances = 0
    this.lastStt = ''

    this.helloTimer = setTimeout(() => {
      if (!this.helloDone) this.close(1008, 'hello timeout')
    }, HELLO_TIMEOUT_MS)
    this.helloTimer.unref?.()

    conn.on('message', (data, isBinary) => {
      try {
        if (isBinary) this.onBinary(data)
        else this.onText(data)
      } catch (error) {
        console.error('[dsh-voice-channel][ws] message failed:', error?.message ?? error)
        this.notify('error', String(error?.message ?? error))
      }
    })
    conn.on('close', () => this.dispose())

    deps.noteClient?.(conn, this.deviceId)
    console.log(`[dsh-voice-channel][ws] connect ${this.deviceId} ${conn.remote}`)
  }

  stats() {
    return {
      deviceId: this.deviceId,
      sessionId: this.sessionId,
      protocol: this.version,
      format: this.format,
      talking: this.speaking,
      listening: this.listening,
      turns: this.turns,
      since: this.startedAt,
    }
  }

  dispose() {
    clearTimeout(this.helloTimer)
    this.listening = false
    this.pipeline?.abort()
    this.pipeline = null
    this.queue = []
    this.decoder?.close?.()
    this.encoder?.close?.()
    this.decoder = null
    this.encoder = null
    this.onClose?.(this)
  }

  send(message) {
    this.conn.send({ session_id: this.sessionId, ...message })
  }

  notify(status, message) {
    this.send({ type: 'alert', status, message: String(message).slice(0, 200), emotion: status === 'error' ? 'error' : 'neutral' })
  }

  close(code, reason) {
    this.conn.close(code, reason)
  }

  // ---- 文本消息 ----

  onText(text) {
    let message
    try { message = JSON.parse(text) } catch {
      this.notify('error', 'invalid JSON')
      return
    }
    const type = String(message?.type ?? '')
    if (!this.helloDone && type !== 'hello') {
      this.notify('error', 'hello required')
      this.close(1008, 'hello required')
      return
    }
    switch (type) {
      case 'hello': this.onHello(message).catch((error) => this.notify('error', error?.message ?? error)); break
      case 'listen': this.onListen(message); break
      case 'abort': this.onAbort(message); break
      case 'ping': this.send({ type: 'pong', at: Date.now() }); break
      case 'mcp': break // 设备能力发现：本项目不用 MCP，静默忽略
      default:
        if (type) console.log(`[dsh-voice-channel][ws] ignore message type=${type}`)
    }
  }

  async onHello(message) {
    const audio = message?.audio_params && typeof message.audio_params === 'object' ? message.audio_params : {}
    const format = String(audio.format ?? 'opus').toLowerCase()
    this.format = format === 'pcm16' || format === 'pcm' ? 'pcm16' : 'opus'
    this.uplinkRate = Number(audio.sample_rate) > 0 ? Number(audio.sample_rate) : 16000
    this.uplinkFrameMs = Number(audio.frame_duration) > 0 ? Number(audio.frame_duration) : 60
    this.uplinkFrameSamples = Math.max(1, Math.round((this.uplinkRate * this.uplinkFrameMs) / 1000))
    const declared = Number(message?.version)
    this.version = declared >= 1 && declared <= 3 ? declared : (Number(this.protocolHeader) || 1)
    if (message?.device) this.deviceId = String(message.device).slice(0, 64)

    if (this.format === 'opus') {
      try {
        this.decoder = await createOpusCodec(this.uplinkRate)
        this.encoder = await createOpusCodec(DOWNLINK_RATE)
      } catch (error) {
        this.notify('error', `Opus 编解码不可用：${error?.message ?? error}`)
        console.error('[dsh-voice-channel][ws] opus unavailable:', error?.message ?? error)
      }
    }

    this.helloDone = true
    clearTimeout(this.helloTimer)
    this.send({
      type: 'hello',
      transport: 'websocket',
      audio_params: {
        format: this.format,
        sample_rate: DOWNLINK_RATE,
        channels: 1,
        frame_duration: DOWNLINK_FRAME_MS,
      },
    })
    console.log(
      `[dsh-voice-channel][ws] hello ${this.deviceId} uplink=${this.uplinkRate}/${this.format}/v${this.version}`,
    )
  }

  onListen(message) {
    const state = String(message?.state ?? '')
    if (state === 'start') {
      this.beginListening(message)
    } else if (state === 'stop') {
      this.endListening()
    } else if (state === 'detect') {
      // 唤醒词：设备已经自己开始推流，保持当前监听状态即可。
      if (!this.listening) this.beginListening(message)
    }
  }

  beginListening(message) {
    const mode = String(message?.mode ?? 'auto')
    // realtime 需要服务端 AEC，这里按 auto 处理（设备侧不会因为 mode 不同而改变推流行为）。
    this.mode = mode === 'manual' ? 'manual' : 'auto'
    this.listening = true
    if (this.mode === 'auto') {
      this.collector = null
      this.segmenter = new Segmenter({
        sampleRate: this.uplinkRate,
        onUtterance: (pcm) => this.enqueue(pcm),
      })
    } else {
      this.segmenter = null
      this.collector = new BufferCollector({ sampleRate: this.uplinkRate })
    }
  }

  endListening() {
    this.listening = false
    if (this.segmenter) this.segmenter.flush()
    if (this.collector) {
      const pcm = this.collector.take()
      this.collector = null
      if (pcm) this.enqueue(pcm)
    }
  }

  onAbort(reason) {
    console.log(`[dsh-voice-channel][ws] abort ${this.deviceId} reason=${reason?.reason ?? ''}`)
    this.abortToken += 1
    this.queue = []
    this.pipeline?.abort()
    this.pipeline = null
    this.segmenter?.reset()
    this.collector?.clear()
    if (this.speaking) {
      this.speaking = false
      this.send({ type: 'tts', state: 'stop' })
    }
  }

  // ---- 音频上行 ----

  onBinary(payload) {
    const packet = this.unwrap(payload)
    if (!packet || !packet.length) return
    let samples
    if (this.format === 'opus') {
      if (!this.decoder) return
      try { samples = this.decoder.decode(packet, this.uplinkFrameSamples) } catch { return }
    } else {
      samples = toInt16(packet)
    }
    if (!samples.length) return
    if (this.segmenter) this.segmenter.feed(samples)
    else if (this.collector) this.collector.push(samples)
  }

  /** v2/v3 的二进制头去掉；v1 是裸 Opus。 */
  unwrap(buffer) {
    if (this.version === 2) {
      if (buffer.length < 16) return null
      const size = buffer.readUInt32BE(12)
      return buffer.subarray(16, 16 + size)
    }
    if (this.version === 3) {
      if (buffer.length < 4) return null
      const size = buffer.readUInt16BE(2)
      return buffer.subarray(4, 4 + size)
    }
    return buffer
  }

  enqueue(pcm) {
    this.queue.push(pcm)
    this.utterances += 1
    this.pump().catch((error) => this.notify('error', error?.message ?? error))
  }

  /** 一轮一轮串行跑：同一个 DSH 会话交叉提问会让回复串味。 */
  async pump() {
    if (this.pumping) return
    this.pumping = true
    try {
      while (this.queue.length && !this.conn.closed) {
        const pcm = this.queue.shift()
        await this.runTurn(pcm, this.abortToken)
      }
    } finally {
      this.pumping = false
    }
  }

  // ---- 一轮对话 ----

  async runTurn(pcm, token) {
    if (token !== this.abortToken) return
    let text = ''
    try {
      const result = await this.deps.asr.transcribe(toBuffer(pcm))
      text = String(result?.text ?? '').trim()
    } catch (error) {
      this.notify('error', `识别服务不可用：${error?.message ?? error}`)
      return
    }
    if (token !== this.abortToken) return
    this.send({ type: 'stt', text })
    if (!text) return
    this.lastStt = text
    this.turns += 1

    let chat
    try {
      chat = await this.deps.loadChat()
    } catch (error) {
      this.notify('error', String(error?.message ?? error))
      return
    }

    this.speaking = true
    this.send({ type: 'tts', state: 'start' })
    const pipeline = new Pipeline()
    this.pipeline = pipeline

    const splitter = new SentenceSplitter()
    let full = ''
    let emotion = ''
    let announced = false
    const announce = () => {
      if (announced) return
      announced = true
      emotion = detectEmotion(full)
      this.send({ type: 'llm', emotion })
    }

    const producer = (async () => {
      try {
        for await (const chunk of chat(text, { sessionId: this.deviceId, clientId: this.deviceId })) {
          if (token !== this.abortToken) return
          full += chunk
          for (const sentence of splitter.push(chunk)) {
            announce()
            if (!(await this.synth(pipeline, sentence, token))) return
          }
        }
        for (const sentence of splitter.flush()) {
          announce()
          if (!(await this.synth(pipeline, sentence, token))) return
        }
        const final = detectEmotion(full)
        if (final !== emotion) this.send({ type: 'llm', emotion: final })
        console.log(`[dsh-voice-channel][ws] reply chars=${full.length}`)
        if (!cleanForSpeech(full)) this.notify('error', '这次没收到回复，再说一次吧')
      } catch (error) {
        this.notify('error', String(error?.message ?? error))
      } finally {
        pipeline.close()
      }
    })()

    try {
      await this.play(pipeline, token)
    } finally {
      pipeline.abort()
    }
    await producer
    if (this.pipeline === pipeline) this.pipeline = null
    if (token === this.abortToken) {
      this.speaking = false
      this.send({ type: 'tts', state: 'stop' })
    }
  }

  /** 合成一句 → 解码成 PCM → 交给下行队列（合成与播放节奏解耦，句间不留空）。 */
  async synth(pipeline, sentence, token) {
    const clean = cleanForSpeech(sentence)
    if (!clean) return true
    if (clean.length > MAX_TEXT) return true
    let result
    try {
      result = await this.deps.tts.synthesize(clean)
    } catch (error) {
      this.notify('error', `合成失败：${error?.message ?? error}`)
      return true
    }
    if (token !== this.abortToken) return false
    let pcm
    try {
      ({ pcm } = await decodeMp3(result.audio))
    } catch (error) {
      this.notify('error', `音频解码失败：${error?.message ?? error}`)
      return true
    }
    if (token !== this.abortToken) return false
    return pipeline.push({ text: clean, pcm })
  }

  /** 按下行节奏发帧：60 ms 一帧，先到先发，合成慢了就自然等。 */
  async play(pipeline, token) {
    const frameSamples = (DOWNLINK_RATE * DOWNLINK_FRAME_MS) / 1000
    let nextAt = 0
    for (;;) {
      const item = await pipeline.next()
      if (!item) return
      if (token !== this.abortToken) return
      this.send({ type: 'tts', state: 'sentence_start', text: item.text })
      for (let offset = 0; offset < item.pcm.length; offset += frameSamples) {
        if (token !== this.abortToken) return
        let frame = item.pcm.subarray(offset, offset + frameSamples)
        if (frame.length < frameSamples) {
          const padded = new Int16Array(frameSamples)
          padded.set(frame)
          frame = padded
        }
        if (!this.sendAudio(frame)) return
        nextAt = Math.max(nextAt, Date.now()) + DOWNLINK_FRAME_MS
        const wait = nextAt - Date.now()
        if (wait > 0) await sleep(wait)
      }
    }
  }

  sendAudio(frame) {
    if (this.conn.closed) return false
    if (this.encoder) return this.conn.sendBinary(this.encoder.encode(frame))
    return this.conn.sendBinary(toBuffer(frame))
  }
}
