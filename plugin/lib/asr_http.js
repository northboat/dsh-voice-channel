// /api/asr 的共用实现：插件内路由（8787）与独立 ASR 服务（8788）都走这里。
// 独立服务让 ASR 不必重载 Desktop 插件即可上线，也把 CPU 峰值与 DSH 进程隔开。

import { readState } from './state.js'
import { asr } from './asr.js'

const MAX_AUDIO = 8 * 1024 * 1024

export function isLoopback(req) {
  const ip = String(req.socket?.remoteAddress ?? '')
  return ip === '::1' || ip === '127.0.0.1' || ip.startsWith('::ffff:127.')
}

function pinFrom(req) {
  const auth = String(req.headers.authorization ?? '')
  return (auth.startsWith('Bearer ') ? auth.slice(7).trim() : '') || String(req.headers['x-pin'] ?? '').trim()
}

export function cors(res) {
  res.setHeader('access-control-allow-origin', '*')
  res.setHeader('access-control-allow-headers', 'content-type, authorization, x-pin, device-id, x-device-id')
  res.setHeader('access-control-allow-methods', 'GET, POST, OPTIONS')
  res.setHeader('cache-control', 'no-store')
}

export function send(res, code, body, type = 'application/json; charset=utf-8') {
  cors(res)
  res.writeHead(code, { 'content-type': type })
  res.end(typeof body === 'string' ? body : JSON.stringify(body))
}

function readBinary(req, limit) {
  return new Promise((resolve, reject) => {
    const chunks = []
    let size = 0
    req.on('data', (chunk) => {
      size += chunk.length
      if (size > limit) {
        reject(new Error(`body 超过 ${limit} 字节`))
        req.destroy()
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => resolve(Buffer.concat(chunks)))
    req.on('error', reject)
  })
}

// 兼容裸 PCM（application/octet-stream）与 WAV（RIFF 里找 data chunk）。
function pcmFromBody(body) {
  if (body.length >= 12 && body.toString('latin1', 0, 4) === 'RIFF') {
    let offset = 12
    while (offset + 8 <= body.length) {
      const id = body.toString('latin1', offset, offset + 4)
      const size = body.readUInt32LE(offset + 4)
      if (id === 'data') return body.subarray(offset + 8, Math.min(offset + 8 + size, body.length))
      offset += 8 + size + (size % 2)
    }
    throw new Error('WAV 缺少 data chunk')
  }
  return body
}

/**
 * POST /api/asr
 *   body: s16le / 16 kHz / mono 的 PCM，或同格式 WAV
 *   auth: loopback 直接放行；否则 Authorization: Bearer <PIN> 或 X-PIN
 *   resp: { ok, text, ms, totalMs, engine, model }
 */
export async function handleAsr(req, res) {
  const pin = readState().pin
  const auth = isLoopback(req) ? 'loopback' : (pinFrom(req) === pin ? 'pin' : 'denied')
  if (auth === 'denied') {
    send(res, 401, { error: 'PIN required', hint: 'Authorization: Bearer <PIN> 或 X-PIN: <PIN>' })
    return
  }
  let body
  try {
    body = await readBinary(req, MAX_AUDIO)
  } catch (error) {
    send(res, 413, { error: String(error?.message ?? error) })
    return
  }
  let pcm
  try {
    pcm = pcmFromBody(body)
  } catch (error) {
    send(res, 400, { error: String(error?.message ?? error) })
    return
  }
  if (pcm.length % 2) pcm = pcm.subarray(0, pcm.length - 1)
  const started = Date.now()
  try {
    const result = await asr.transcribe(pcm)
    send(res, 200, {
      ok: true,
      text: result.text,
      ms: result.ms,
      totalMs: Date.now() - started,
      engine: 'vosk-python',
      model: asr.status().model,
    })
  } catch (error) {
    send(res, 503, { ok: false, error: String(error?.message ?? error), asr: asr.status() })
  }
}
