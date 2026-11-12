// 服务端断句（xiaozhi 的 auto 模式没有「说完了」标记，设备只是一直推流）。
// 判据与 Android 端 CallSpeechInput 一致：帧内峰值 + 噪声底（快降慢升）+ 开口确认 + 句尾静音。
// Android 自己已经断好句（manual 模式），这条路只服务 ESP32 这类只推流的设备。

const NOISE_FLOOR = 20
const NOISE_GAIN = 3.5
const MIN_THRESHOLD = 120
const MAX_THRESHOLD = 2500
const ONSET_MS = 200
const SILENCE_MS = 700
const MIN_SPEECH_MS = 300
const MAX_MS = 15_000
const CALIBRATION_MS = 300

export class Segmenter {
  constructor({ sampleRate = 16000, onUtterance } = {}) {
    this.sampleRate = sampleRate
    this.onUtterance = onUtterance ?? (() => {})
    this.reset()
  }

  reset() {
    this.state = 'silent'
    this.noise = NOISE_FLOOR
    this.elapsedMs = 0
    this.calibrated = false
    this.noiseMin = Infinity
    this.candMs = 0
    this.speechMs = 0
    this.silenceMs = 0
    this.chunks = []
    this.frames = 0
  }

  /** Int16Array（16 kHz 单声道）；断句成功时通过 onUtterance 抛出整段 PCM。 */
  feed(samples) {
    if (!samples.length) return
    const frameMs = (samples.length * 1000) / this.sampleRate
    this.elapsedMs += frameMs
    const peak = peakOf(samples)

    if (!this.calibrated) {
      this.noiseMin = Math.min(this.noiseMin, peak)
      this.frames += 1
      if (this.elapsedMs >= CALIBRATION_MS) {
        this.calibrated = true
        this.noise = Math.max(this.noiseMin, NOISE_FLOOR)
      }
    } else if (this.state === 'silent') {
      // 快降慢升：环境变安静立刻跟，变吵只慢慢抬，避免被一声咳嗽顶死门限。
      this.noise = peak < this.noise
        ? Math.max(this.noise * 0.6 + peak * 0.4, NOISE_FLOOR)
        : Math.max(this.noise * 0.98 + peak * 0.02, NOISE_FLOOR)
    }

    const threshold = Math.min(Math.max(this.noise * NOISE_GAIN, MIN_THRESHOLD), MAX_THRESHOLD)
    const voiced = peak > threshold

    if (this.state === 'silent') {
      if (!voiced) return
      this.state = 'candidate'
      this.candMs = 0
      this.chunks = []
      this.push(samples)
    } else {
      this.push(samples)
    }

    if (this.state === 'candidate') {
      if (!voiced) {
        this.state = 'silent'
        this.chunks = []
        return
      }
      this.candMs += frameMs
      if (this.candMs >= ONSET_MS) {
        this.state = 'speech'
        this.speechMs = this.candMs
        this.silenceMs = 0
      }
      return
    }

    if (this.state === 'speech') {
      this.speechMs += frameMs
      if (voiced) this.silenceMs = 0
      else this.silenceMs += frameMs
      if (this.silenceMs >= SILENCE_MS || this.speechMs >= MAX_MS) this.emit()
    }
  }

  /** listen stop 时收尾：还在一句里就当作说完了，太短则丢弃。 */
  flush() {
    if (this.state === 'speech' && this.speechMs >= MIN_SPEECH_MS) this.emit()
    else this.reset()
  }

  push(samples) {
    this.chunks.push(samples)
  }

  emit() {
    const length = this.chunks.reduce((sum, chunk) => sum + chunk.length, 0)
    const all = new Int16Array(length)
    let offset = 0
    for (const chunk of this.chunks) {
      all.set(chunk, offset)
      offset += chunk.length
    }
    const speechMs = this.speechMs
    this.reset()
    if (length && speechMs >= MIN_SPEECH_MS) this.onUtterance(all)
  }
}

function peakOf(samples) {
  let max = 0
  for (let i = 0; i < samples.length; i += 1) {
    const value = samples[i] < 0 ? -samples[i] : samples[i]
    if (value > max) max = value
  }
  return max
}

/** 收集原始帧并在 stop 时一次性交出去（manual 模式：client 已经自己断好句）。 */
export class BufferCollector {
  constructor({ sampleRate = 16000, maxMs = 30_000 } = {}) {
    this.sampleRate = sampleRate
    this.maxSamples = Math.floor((sampleRate * maxMs) / 1000)
    this.chunks = []
    this.total = 0
  }

  push(samples) {
    if (this.total >= this.maxSamples) return
    const chunk = this.total + samples.length > this.maxSamples
      ? samples.subarray(0, this.maxSamples - this.total)
      : samples
    if (!chunk.length) return
    this.chunks.push(chunk)
    this.total += chunk.length
  }

  /** 取出全部缓冲并清空。 */
  take(minMs = 100) {
    const chunks = this.chunks
    const total = this.total
    this.chunks = []
    this.total = 0
    if (!total || (total * 1000) / this.sampleRate < minMs) return null
    const all = new Int16Array(total)
    let offset = 0
    for (const chunk of chunks) {
      all.set(chunk, offset)
      offset += chunk.length
    }
    return all
  }

  clear() {
    this.chunks = []
    this.total = 0
  }
}
