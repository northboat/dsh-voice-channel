// /api/tts 的共用实现：插件内路由（8787）与独立调试服务（8788）都走这里。

import { readState } from './state.js'
import { tts, ttsSettings } from './tts.js'
import { isLoopback, cors, send } from './asr_http.js'

const MAX_TEXT = 2000
const pick = (value, fallback) => (value === '' || value === undefined || value === null ? fallback : value)

function pinFrom(req) {
  const auth = String(req.headers.authorization ?? '')
  return (auth.startsWith('Bearer ') ? auth.slice(7).trim() : '') || String(req.headers['x-pin'] ?? '').trim()
}

function authorized(req) {
  const pin = readState().pin
  return isLoopback(req) ? 'loopback' : (pinFrom(req) === pin && pin ? 'pin' : 'denied')
}

function readText(req, limit = 16 * 1024) {
  return new Promise((resolve, reject) => {
    let data = ''
    req.on('data', (chunk) => {
      data += chunk
      if (data.length > limit) { reject(new Error('body 过大')); req.destroy() }
    })
    req.on('end', () => resolve(data))
    req.on('error', reject)
  })
}

async function params(req, url) {
  if (req.method === 'GET') {
    const q = url.searchParams
    return {
      text: q.get('text') ?? '',
      voice: q.get('voice') ?? '',
      rate: q.get('rate') ?? '',
      pitch: q.get('pitch') ?? '',
      volume: q.get('volume') ?? '',
    }
  }
  let payload
  try { payload = JSON.parse((await readText(req)) || '{}') } catch { throw new Error('invalid JSON') }
  return {
    text: String(payload.text ?? ''),
    voice: payload.voice ?? '',
    rate: payload.rate ?? '',
    pitch: payload.pitch ?? '',
    volume: payload.volume ?? '',
  }
}

/**
 * GET  /api/tts?text=..&voice=..&rate=10&pitch=-2   （ESP32 / 手机可直接拉流）
 * POST /api/tts  { text, voice, rate, pitch, volume }
 *   auth: loopback 直接放行；否则 Authorization: Bearer <PIN> 或 X-PIN
 *   resp: audio/mpeg（头 x-tts-voice / x-tts-ms / x-tts-cache）
 */
export async function handleTts(req, res, url) {
  if (authorized(req) === 'denied') {
    send(res, 401, { error: 'PIN required', hint: 'Authorization: Bearer <PIN> 或 X-PIN: <PIN>' })
    return
  }
  let input
  try {
    input = await params(req, url)
  } catch (error) {
    send(res, 400, { error: String(error?.message ?? error) })
    return
  }
  const text = String(input.text || '').trim()
  if (!text) { send(res, 400, { error: 'text required' }); return }
  if (text.length > MAX_TEXT) { send(res, 413, { error: `text 超过 ${MAX_TEXT} 字` }); return }
  try {
    // 请求没带参数就用管理页保存的音色（client 只发 text 时就落在这里）。
    const settings = ttsSettings()
    const result = await tts.synthesize(text, {
      voice: pick(input.voice, settings.voice),
      rate: pick(input.rate, settings.rate),
      pitch: pick(input.pitch, settings.pitch),
      volume: input.volume,
    })
    cors(res)
    res.writeHead(200, {
      'content-type': 'audio/mpeg',
      'content-length': result.audio.length,
      'x-tts-voice': result.voice,
      'x-tts-ms': String(result.ms ?? ''),
      'x-tts-cache': result.cached ? 'hit' : 'miss',
      'x-tts-synth-ms': String(result.cached ? 0 : result.ms ?? 0),
    })
    res.end(result.audio)
  } catch (error) {
    send(res, 503, { ok: false, error: String(error?.message ?? error), tts: tts.status() })
  }
}

/**
 * GET /api/tts/voices?locale=zh[&live=1]
 *   → { ok, count, defaultVoice, source:'builtin'|'live', voices:[{name,gender,locale,tags}] }
 *   默认先回内置 zh 表；live=1 等在线全量（7–9 s）拉完再返回。
 */
export async function handleTtsVoices(req, res, url) {
  if (authorized(req) === 'denied') {
    send(res, 401, { error: 'PIN required', hint: 'Authorization: Bearer <PIN> 或 X-PIN: <PIN>' })
    return
  }
  try {
    const live = url.searchParams.get('live') === '1'
    const list = await tts.voices(url.searchParams.get('locale') ?? '', { live })
    const settings = ttsSettings()
    send(res, 200, {
      ok: true,
      count: list.length,
      defaultVoice: settings.voice,
      current: settings,
      source: tts.voicesSource(),
      voices: list,
    })
  } catch (error) {
    send(res, 503, { ok: false, error: String(error?.message ?? error), tts: tts.status() })
  }
}
