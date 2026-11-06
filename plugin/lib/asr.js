// PC 侧离线 ASR：把 Vosk 放到常驻 Python 子进程里，避免阻塞 DSH 的事件循环。
// 引擎可替换：只要实现同样的 stdin/stdout 协议，换成 sherpa-onnx / SenseVoice 无需改 HTTP 层。

import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(HERE, '..')
const WORKER = path.join(HERE, 'asr_worker.py')
const DEFAULT_MODEL = path.join(ROOT, 'models', 'vosk-model-small-cn-0.22')

function pythonBin() {
  if (process.env.DSH_ASR_PYTHON) return process.env.DSH_ASR_PYTHON
  const local =
    process.platform === 'win32'
      ? path.join(ROOT, '.venv', 'Scripts', 'python.exe')
      : path.join(ROOT, '.venv', 'bin', 'python')
  if (existsSync(local)) return local
  return process.platform === 'win32' ? 'python' : 'python3'
}

class VoskAsr {
  constructor() {
    this.model = process.env.DSH_ASR_MODEL || DEFAULT_MODEL
    this.child = null
    this.ready = false
    this.error = ''
    this.loadMs = null
    this.pending = new Map()
    this.seq = 0
    this.booting = null
    this.queue = Promise.resolve()
    this.requests = 0
    this.lastMs = null
  }

  status() {
    return {
      engine: 'vosk-python',
      ready: this.ready,
      model: this.model,
      loadMs: this.loadMs,
      error: this.error || null,
      pending: this.pending.size,
      requests: this.requests,
      lastMs: this.lastMs,
    }
  }

  async start() {
    if (this.ready) return
    if (this.booting) return this.booting
    this.booting = new Promise((resolve, reject) => {
      let settled = false
      const done = (fn) => { if (!settled) { settled = true; fn() } }
      const child = spawn(pythonBin(), [WORKER, this.model], {
        cwd: ROOT,
        stdio: ['pipe', 'pipe', 'pipe'],
        windowsHide: true,
      })
      this.child = child
      let buffer = ''
      const timer = setTimeout(() => {
        this.error = 'ASR worker 启动超时（模型加载 > 120s）'
        try { child.kill() } catch { /* ignore */ }
        done(() => reject(new Error(this.error)))
      }, 120000)

      child.stdout.on('data', (chunk) => {
        buffer += chunk.toString('utf8')
        let index
        while ((index = buffer.indexOf('\n')) >= 0) {
          const line = buffer.slice(0, index).trim()
          buffer = buffer.slice(index + 1)
          if (!line) continue
          let message
          try { message = JSON.parse(line) } catch { continue }
          if (message.ready === true) {
            clearTimeout(timer)
            this.ready = true
            this.loadMs = message.loadMs ?? null
            this.error = ''
            done(() => resolve())
            continue
          }
          if (message.ready === false) {
            clearTimeout(timer)
            this.error = String(message.error || '模型加载失败')
            done(() => reject(new Error(this.error)))
            continue
          }
          const item = this.pending.get(message.id)
          if (!item) continue
          this.pending.delete(message.id)
          clearTimeout(item.timer)
          if (message.ok) item.resolve({ text: String(message.text ?? ''), ms: Number(message.ms ?? 0) })
          else item.reject(new Error(message.error || 'asr failed'))
        }
      })

      child.stderr.on('data', (chunk) => {
        const text = chunk.toString('utf8').trim()
        if (text) console.error('[dsh-channel][asr]', text)
      })

      child.on('exit', (code) => {
        this.ready = false
        this.child = null
        for (const item of this.pending.values()) {
          clearTimeout(item.timer)
          item.reject(new Error(`ASR worker 退出（code=${code}）`))
        }
        this.pending.clear()
        clearTimeout(timer)
        done(() => reject(new Error(this.error || `ASR worker 退出（code=${code}）`)))
      })

      child.on('error', (error) => {
        clearTimeout(timer)
        this.error = `无法启动 python：${error.message}`
        done(() => reject(error))
      })
    }).finally(() => { this.booting = null })
    return this.booting
  }

  /** pcm: s16le / 16 kHz / mono 的 Buffer。串行执行，worker 本身是单线程。 */
  transcribe(pcm) {
    const run = async () => {
      await this.start()
      if (!this.ready || !this.child) throw new Error(this.error || 'ASR 未就绪')
      const id = ++this.seq
      const result = await new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          this.pending.delete(id)
          reject(new Error('ASR 超时（60s）'))
        }, 60000)
        this.pending.set(id, { resolve, reject, timer })
        const head = Buffer.alloc(8)
        head.writeUInt32LE(id, 0)
        head.writeUInt32LE(pcm.length, 4)
        this.child.stdin.write(head)
        this.child.stdin.write(pcm)
      })
      this.requests += 1
      this.lastMs = result.ms
      return result
    }
    this.queue = this.queue.then(run, run)
    return this.queue
  }

  dispose() {
    try { this.child?.kill() } catch { /* ignore */ }
    this.child = null
    this.ready = false
  }
}

export const asr = new VoskAsr()
