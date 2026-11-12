// PC 侧 TTS：Edge 在线神经语音（edge-tts）跑在常驻 Python 子进程里。
// 零本地模型、零显存，中文音色 8+（含方言），代价是必须联网。
// 协议见 tts_worker.py；换引擎（piper / sherpa-onnx）不用改这一层与 HTTP 层。

import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { readState, normalizeTts } from './state.js'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(HERE, '..')
const WORKER = path.join(HERE, 'tts_worker.py')

/**
 * 当前生效的音色：`DSH_TTS_VOICE` 环境变量优先（临时调试），否则用 state.json 里管理页保存的配置。
 * client 不传参数时就落到这里。
 */
export function ttsSettings() {
  const env = String(process.env.DSH_TTS_VOICE ?? '').trim()
  const state = normalizeTts(readState().tts)
  return env ? { ...state, voice: env } : state
}

function pythonBin() {
  if (process.env.DSH_TTS_PYTHON) return process.env.DSH_TTS_PYTHON
  const local =
    process.platform === 'win32'
      ? path.join(ROOT, '.venv', 'Scripts', 'python.exe')
      : path.join(ROOT, '.venv', 'bin', 'python')
  if (existsSync(local)) return local
  return process.platform === 'win32' ? 'python' : 'python3'
}

/** 内置 zh 音色表：在线清单要 7–9 s（322 条），先给这份，后台再补全量。 */
const BUILTIN_ZH = [
  { name: 'zh-CN-XiaoxiaoNeural', gender: 'Female', locale: 'zh-CN' },
  { name: 'zh-CN-XiaoyiNeural', gender: 'Female', locale: 'zh-CN' },
  { name: 'zh-CN-YunjianNeural', gender: 'Male', locale: 'zh-CN' },
  { name: 'zh-CN-YunxiNeural', gender: 'Male', locale: 'zh-CN' },
  { name: 'zh-CN-YunxiaNeural', gender: 'Male', locale: 'zh-CN' },
  { name: 'zh-CN-YunyangNeural', gender: 'Male', locale: 'zh-CN' },
  { name: 'zh-CN-liaoning-XiaobeiNeural', gender: 'Female', locale: 'zh-CN' },
  { name: 'zh-CN-shaanxi-XiaoniNeural', gender: 'Female', locale: 'zh-CN' },
]

/** 语速 / 音调 / 音量：数字按百分比 / 赫兹换算成 edge 的 "+10%" / "-20Hz"。 */
function rate(value, unit) {
  if (value === undefined || value === null || value === '') return `+0${unit}`
  if (typeof value === 'number') return `${value >= 0 ? '+' : ''}${Math.round(value)}${unit}`
  return String(value)
}

class EdgeTts {
  constructor() {
    this.child = null
    this.ready = false
    this.error = ''
    this.booting = null
    this.pending = new Map()
    this.seq = 0
    this.queue = Promise.resolve()
    this.buffer = Buffer.alloc(0)
    this.expect = null
    this.cache = new Map()
    this.maxCache = 64
    this.requests = 0
    this.lastMs = null
    this.voicesCache = null
    this.voicesAt = 0
    this.voiceFetch = null
  }

  status() {
    const settings = ttsSettings()
    return {
      engine: 'edge-tts',
      mode: 'online',
      ready: this.ready,
      voice: settings.voice,
      rate: settings.rate,
      pitch: settings.pitch,
      envVoice: Boolean(String(process.env.DSH_TTS_VOICE ?? '').trim()),
      error: this.error || null,
      pending: this.pending.size,
      requests: this.requests,
      lastMs: this.lastMs,
      cache: this.cache.size,
    }
  }

  async start() {
    if (this.ready) return
    if (this.booting) return this.booting
    this.booting = new Promise((resolve, reject) => {
      let settled = false
      const done = (fn) => { if (!settled) { settled = true; fn() } }
      const child = spawn(pythonBin(), [WORKER], { cwd: ROOT, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true })
      this.child = child
      const timer = setTimeout(() => {
        this.error = 'TTS worker 启动超时（20s）'
        try { child.kill() } catch { /* ignore */ }
        done(() => reject(new Error(this.error)))
      }, 20000)

      child.stdout.on('data', (chunk) => this.onData(chunk, () => {
        if (!this.ready) {
          clearTimeout(timer)
          this.ready = true
          this.error = ''
          done(() => resolve())
        }
      }))

      child.stderr.on('data', (chunk) => {
        const text = chunk.toString('utf8').trim()
        if (text) console.error('[dsh-voice-channel][tts]', text)
      })

      child.on('exit', (code) => {
        this.ready = false
        this.child = null
        this.expect = null
        this.buffer = Buffer.alloc(0)
        for (const item of this.pending.values()) {
          clearTimeout(item.timer)
          item.reject(new Error(`TTS worker 退出（code=${code}）`))
        }
        this.pending.clear()
        clearTimeout(timer)
        done(() => reject(new Error(this.error || `TTS worker 退出（code=${code}）`)))
      })

      child.on('error', (error) => {
        clearTimeout(timer)
        this.error = `无法启动 python：${error.message}`
        done(() => reject(error))
      })
    }).finally(() => { this.booting = null })
    return this.booting
  }

  /** 一帧 = JSON 行头 + header.bytes 个音频字节（粘包 / 拆包都在这里拼）。 */
  onData(chunk, onReady) {
    this.buffer = this.buffer.length ? Buffer.concat([this.buffer, chunk]) : chunk
    for (;;) {
      if (this.expect) {
        if (this.buffer.length < this.expect.size) return
        const audio = this.buffer.subarray(0, this.expect.size)
        this.buffer = this.buffer.subarray(this.expect.size)
        const { item, header: expectHeader } = this.expect
        this.expect = null
        // 超时/取消的请求没有 item：音频字节照样要吃掉，否则会被当成下一行 JSON 解析，帧流从此失步。
        if (!item) continue
        clearTimeout(item.timer)
        item.resolve({ ...expectHeader, audio })
        continue
      }
      const index = this.buffer.indexOf(10)
      if (index < 0) return
      const line = this.buffer.toString('utf8', 0, index).trim()
      this.buffer = this.buffer.subarray(index + 1)
      if (!line) continue
      let header
      try { header = JSON.parse(line) } catch { continue }
      if (header.ready === true) { onReady(); continue }
      if (header.ready === false) {
        this.error = String(header.error || 'TTS worker 未就绪')
        try { this.child?.kill() } catch { /* ignore */ }
        continue
      }
      const item = this.pending.get(header.id)
      if (header.ok && header.bytes > 0) {
        // 迟到响应（已超时）也把音频长度记进 expect，保证下面的二进制被整块跳过。
        if (item) this.pending.delete(header.id)
        this.expect = { size: Number(header.bytes), item: item ?? null, header }
        continue
      }
      if (!item) continue
      this.pending.delete(header.id)
      clearTimeout(item.timer)
      if (header.ok) item.resolve({ ...header, audio: Buffer.alloc(0) })
      else item.reject(new Error(header.error || 'tts failed'))
    }
  }

  // edge-tts 实测单次 2~17 s，worker 对网络异常还会重试一次（最坏 ~2×）；
  // 30 s 会把「慢但成功」误判成失败。放宽到 90 s，超时后由 onData 吃掉迟到音频，不会拖垮后续请求。
  call(payload, timeoutMs = 90000) {
    return this.start().then(() => {
      if (!this.ready || !this.child) throw new Error(this.error || 'TTS 未就绪')
      const id = ++this.seq
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          this.pending.delete(id)
          reject(new Error(`TTS 超时（${Math.max(1, Math.round(timeoutMs / 1000))}s）`))
        }, timeoutMs)
        this.pending.set(id, { resolve, reject, timer })
        const body = Buffer.from(JSON.stringify({ ...payload, id }), 'utf8')
        const head = Buffer.alloc(4)
        head.writeUInt32LE(body.length, 0)
        this.child.stdin.write(head)
        this.child.stdin.write(body)
      })
    })
  }

  /** 合成一段文本；命中缓存直接返回，串行入队避免并发打爆在线服务。 */
  synthesize(text, options = {}) {
    const run = async () => {
      const value = String(text ?? '').trim()
      if (!value) throw new Error('text 为空')
      const settings = ttsSettings()
      const params = {
        text: value,
        voice: String(options.voice || settings.voice),
        rate: rate(options.rate ?? settings.rate, '%'),
        pitch: rate(options.pitch ?? settings.pitch, 'Hz'),
        volume: rate(options.volume, '%'),
      }
      const key = JSON.stringify(params)
      const hit = this.cache.get(key)
      if (hit) return { ...hit, cached: true }
      const result = await this.call({ op: 'speak', ...params })
      if (!result.audio?.length) throw new Error('empty audio')
      const row = { audio: result.audio, ms: result.ms, codec: result.codec || 'audio/mpeg', voice: params.voice }
      this.cache.set(key, row)
      if (this.cache.size > this.maxCache) this.cache.delete(this.cache.keys().next().value)
      this.requests += 1
      this.lastMs = result.ms ?? null
      return { ...row, cached: false }
    }
    this.queue = this.queue.then(run, run)
    return this.queue
  }

  /** 音色清单：有全量缓存就过滤，否则先回内置 zh 表、后台拉全量（在线清单 7–9 s）。 */
  async voices(locale = '', { live = false } = {}) {
    const prefix = String(locale || '')
    const filter = (rows) => (prefix ? rows.filter((row) => String(row.locale || '').startsWith(prefix)) : rows)
    const fresh = this.voicesCache && Date.now() - this.voicesAt < 10 * 60 * 1000
    if (!fresh && !this.voiceFetch) {
      this.voiceFetch = this.call({ op: 'voices' }, 60000)
        .then((result) => { this.voicesCache = result.voices || []; this.voicesAt = Date.now() })
        .catch(() => { this.voicesAt = 0 })
        .finally(() => { this.voiceFetch = null })
    }
    if (live && this.voiceFetch) await this.voiceFetch
    return filter(this.voicesCache || BUILTIN_ZH)
  }

  /** 'live' = 已缓存在线全量，'builtin' = 还在用内置表。 */
  voicesSource() {
    return this.voicesCache ? 'live' : 'builtin'
  }

  dispose() {
    try { this.child?.kill() } catch { /* ignore */ }
    this.child = null
    this.ready = false
    this.pending.clear()
    this.cache.clear()
  }
}

export const tts = new EdgeTts()
