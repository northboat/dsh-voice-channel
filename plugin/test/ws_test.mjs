// xiaozhi WS 协议验收（不起 DSH / 不用 python）：把 asr / tts / chat 换成固定桩，
// 真正走的是 lib/ws.js + lib/xiaozhi.js 的帧解析、Opus 编解码、断句、节奏下发。
import http from 'node:http'
import { readFileSync, existsSync } from 'node:fs'
import { attachWebSocket } from '../lib/ws.js'
import { XiaozhiSession } from '../lib/xiaozhi.js'
import { createOpusCodec } from '../lib/opus.js'
import { decodeMp3 } from '../lib/mp3.js'
import { TestClient } from './ws_client.mjs'

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
const FIXTURE = new URL('../testdata/tts-sample.mp3', import.meta.url)

let failures = 0
function check(name, ok, detail = '') {
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ` — ${detail}` : ''}`)
  if (!ok) failures += 1
}

/**
 * TTS 样本：仓库里带了一份 edge-tts 产物（`testdata/tts-sample.mp3`，11 KB），
 * 所以这个测试不依赖网络、也不依赖 python；缺失时回落到本机插件的 /api/tts。
 */
async function ensureMp3() {
  if (existsSync(FIXTURE)) return readFileSync(FIXTURE)
  const response = await fetch(`http://127.0.0.1:8787/api/tts?text=${encodeURIComponent('你好，我在。')}`)
  if (!response.ok) throw new Error(`拿不到 mp3 样本：${response.status}`)
  return Buffer.from(await response.arrayBuffer())
}

const REPLY = '你好呀，我是小鲸鱼。今天的天气很不错！有什么想聊的吗？'
// SentenceSplitter 的 MIN_SENTENCE=16 会把「你好呀，我是小鲸鱼。」并到下一句一起合成，
// 所以整段切成 2 个音频包（少一次在线 TTS 请求）。
const EXPECT_SENTENCES = 2
const transcribed = []

function makeSession(conn, req, mp3) {
  const deps = {
    loadChat: async () => async function* () {
      yield REPLY.slice(0, 6)
      await sleep(200)
      yield REPLY.slice(6)
    },
    asr: {
      transcribe: async (pcm) => {
        transcribed.push(pcm.length)
        return { text: '你好' }
      },
    },
    tts: { synthesize: async () => ({ audio: mp3 }) },
    noteClient: () => {},
  }
  return new XiaozhiSession(conn, req, deps, () => {})
}

function startServer(mp3, authorize = () => true) {
  const server = http.createServer((req, res) => { res.writeHead(404); res.end() })
  attachWebSocket(server, {
    paths: ['/xiaozhi/v1', '/ws'],
    authorize,
    onConnection: (conn, req) => makeSession(conn, req, mp3),
  })
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server)))
}

/** 600 Hz 正弦，16 kHz / 60 ms 一帧 → Opus。 */
function speechFrames(codec, ms, amplitude = 8000) {
  const frame = 960
  const frames = []
  const total = Math.round((16000 * ms) / 1000)
  for (let offset = 0; offset < total; offset += frame) {
    const pcm = new Int16Array(frame)
    for (let i = 0; i < frame; i += 1) {
      const t = (offset + i) / 16000
      pcm[i] = Math.round(amplitude * Math.sin(2 * Math.PI * 600 * t))
    }
    frames.push(codec.encode(pcm))
  }
  return frames
}

function silenceFrames(codec, ms) {
  const frame = 960
  const count = Math.round((16000 * ms) / 1000 / frame)
  return Array.from({ length: count }, () => codec.encode(new Int16Array(frame)))
}

async function testOpusClient(port) {
  console.log('\n== Opus client（xiaozhi 设备口径）==')
  const client = await TestClient.open(`ws://127.0.0.1:${port}/xiaozhi/v1/`, {
    Authorization: 'Bearer 123456',
    'Protocol-Version': '1',
    'Device-Id': 'aa:bb:cc:dd:ee:ff',
    'Client-Id': 'test-client',
  })
  const uplink = await createOpusCodec(16000)
  client.sendJson({
    type: 'hello',
    version: 1,
    transport: 'websocket',
    features: { mcp: true },
    audio_params: { format: 'opus', sample_rate: 16000, channels: 1, frame_duration: 60 },
  })
  const hello = await client.next()
  const helloBody = JSON.parse(hello.text)
  check('hello: type/transport', helloBody.type === 'hello' && helloBody.transport === 'websocket')
  check('hello: 下行 24000/opus/60', helloBody.audio_params?.format === 'opus'
    && helloBody.audio_params?.sample_rate === 24000
    && helloBody.audio_params?.frame_duration === 60, JSON.stringify(helloBody.audio_params))
  check('hello: session_id', typeof helloBody.session_id === 'string' && helloBody.session_id.length > 0)

  client.sendJson({ session_id: helloBody.session_id, type: 'listen', state: 'start', mode: 'auto' })
  // 按 60 ms 实时推流（和设备的节奏一致），否则瞬时灌完测不出 VAD 的断句边界。
  let pushed = 0
  for (const frame of [...silenceFrames(uplink, 300), ...speechFrames(uplink, 900), ...silenceFrames(uplink, 1200)]) {
    client.sendBinary(frame)
    pushed += 1
    if (pushed % 4 === 0) await sleep(15)
  }

  const stt = await client.next(8000)
  const sttBody = JSON.parse(stt.text)
  check('stt 回包', sttBody.type === 'stt' && sttBody.text === '你好', JSON.stringify(sttBody))
  const utteranceMs = (transcribed[0] ?? 0) / 2 / 16
  // 说话 900 ms + 句尾静音 700 ms（含 200 ms 开口确认前的帧），应当落在 1.4~2.2 s
  check('VAD 只切一段、边界合理', transcribed.length === 1 && utteranceMs > 1400 && utteranceMs < 2400,
    `${transcribed.length} 段 / ${utteranceMs.toFixed(0)}ms`)

  const frames = []
  let audio = []
  let sentences = []
  let ttsStart = false
  let llm = null
  const deadline = Date.now() + 12000
  while (Date.now() < deadline) {
    const frame = await client.next(6000)
    frames.push(frame)
    if (frame.binary) { audio.push(frame); continue }
    const body = JSON.parse(frame.text)
    if (body.type === 'tts' && body.state === 'start') ttsStart = true
    if (body.type === 'tts' && body.state === 'sentence_start') sentences.push(body.text)
    if (body.type === 'llm') llm = body.emotion
    if (body.type === 'tts' && body.state === 'stop') break
  }
  check('tts start', ttsStart)
  check('llm 情绪', typeof llm === 'string' && llm.length > 0, `emotion=${llm}`)
  check('逐句 sentence_start', sentences.length === EXPECT_SENTENCES, JSON.stringify(sentences))
  check('收到二进制音频帧', audio.length > 20, `${audio.length} 帧`)

  const decoder = await createOpusCodec(24000)
  let samples = 0
  for (const frame of audio) samples += decoder.decode(frame.binary, 1440).length
  const seconds = samples / 24000
  // 每句都用同一段样本音频，按 1440 采样补零对齐后下发
  const { pcm } = await decodeMp3(mp3)
  const expected = EXPECT_SENTENCES * Math.ceil(pcm.length / 1440) * 1440
  check('下行音频是 24 kHz Opus 且长度对得上', Math.abs(samples - expected) <= 1440 * 3,
    `${seconds.toFixed(2)}s / ${audio.length} 帧 / expect ${(expected / 24000).toFixed(2)}s`)

  const gaps = audio.slice(1, 20).map((frame, i) => frame.at - audio[i].at)
  const avg = gaps.reduce((a, b) => a + b, 0) / Math.max(1, gaps.length)
  check('下行按 60 ms 节奏发送', avg > 40 && avg < 110, `avg=${avg.toFixed(1)}ms`)
  client.close()
}

async function testAbort(port) {
  console.log('\n== abort 立即停声 ==')
  const client = await TestClient.open(`ws://127.0.0.1:${port}/woops-not-allowed`)
    .then(() => null)
    .catch(() => 'rejected')
  check('/woops-not-allowed 被拒', client === 'rejected')

  const c = await TestClient.open(`ws://127.0.0.1:${port}/ws`)
  c.sendJson({ type: 'hello', version: 1, audio_params: { format: 'pcm16', sample_rate: 16000, frame_duration: 60 } })
  await c.next()

  c.sendJson({ type: 'listen', state: 'start', mode: 'manual' })
  const chunk = Buffer.alloc(16000 * 2 * 2) // 2 s 静音（manual 模式下不看能量）
  c.sendBinary(chunk)
  c.sendJson({ type: 'listen', state: 'stop' })

  // 等到入声（tts start + 若干音频帧）
  let sawAudio = 0
  for (;;) {
    const frame = await c.next(8000)
    if (frame.binary) { sawAudio += 1; if (sawAudio >= 3) break }
    else {
      const body = JSON.parse(frame.text)
      if (body.type === 'tts' && body.state === 'start') continue
    }
  }
  const abortedAt = Date.now()
  c.sendJson({ type: 'abort', reason: 'wake_word_detected' })
  let stopped = false
  let lateAudio = 0
  for (;;) {
    const frame = await c.next(3000)
    if (frame.binary) { lateAudio += 1; continue }
    const body = JSON.parse(frame.text)
    if (body.type === 'tts' && body.state === 'stop') {
      stopped = true
      check('abort 后 300 ms 内 tts stop', Date.now() - abortedAt < 300, `${Date.now() - abortedAt}ms`)
      break
    }
  }
  check('abort 后停声', stopped)
  // abort 之后仍可能有 1~2 帧在途，但不能继续整段发
  await sleep(400)
  check('abort 后不再持续下行', lateAudio < 6, `额外 ${lateAudio} 帧`)
  c.close()
}

async function testPcmClient(port) {
  console.log('\n== pcm16 client（Android 口径）==')
  const c = await TestClient.open(`ws://127.0.0.1:${port}/ws`)
  c.sendJson({
    type: 'hello',
    version: 1,
    audio_params: { format: 'pcm16', sample_rate: 16000, channels: 1, frame_duration: 60 },
  })
  const hello = JSON.parse((await c.next()).text)
  check('hello: 下行 pcm16/24000', hello.audio_params?.format === 'pcm16' && hello.audio_params?.sample_rate === 24000)

  c.sendJson({ type: 'listen', state: 'start', mode: 'manual' })
  c.sendBinary(Buffer.alloc(16000 * 2))
  c.sendJson({ type: 'listen', state: 'stop' })

  let audioFrames = 0
  let pcmBytes = 0
  let sentences = 0
  for (;;) {
    const frame = await c.next(12000)
    if (frame.binary) {
      audioFrames += 1
      pcmBytes += frame.binary.length
      if (audioFrames === 1) check('PCM 帧 = 1440 采样（2880 字节）', frame.binary.length === 2880, `len=${frame.binary.length}`)
      continue
    }
    const body = JSON.parse(frame.text)
    if (body.type === 'tts' && body.state === 'sentence_start') sentences += 1
    if (body.type === 'tts' && body.state === 'stop') break
  }
  check('pcm16 分句 + 音频', sentences === EXPECT_SENTENCES && audioFrames > 20, `sentences=${sentences} frames=${audioFrames} bytes=${pcmBytes}`)
  c.close()
}

async function testAuth(mp3) {
  console.log('\n== 鉴权 ==')
  const strict = await startServer(mp3, () => false)
  const port2 = strict.address().port
  const result = await TestClient.open(`ws://127.0.0.1:${port2}/ws`).then(() => 'accepted').catch(() => 'rejected')
  check('authorize=false 时 401', result === 'rejected')
  strict.close()
}

/** 插件真实入口：upgrade 路由、OTA、health 里的 ws 字段。 */
async function testPluginWiring() {
  console.log('\n== 插件入口接线（/api/health、/xiaozhi/ota/、/xiaozhi/v1/）==')
  const { createServer } = await import('../lib/index.js')
  const instance = createServer()
  await new Promise((resolve) => instance.listen(0, '127.0.0.1', resolve))
  const p = instance.address().port

  const health = await (await fetch(`http://127.0.0.1:${p}/api/health`)).json()
  check('health.ws 存在', typeof health.ws?.live === 'number' && typeof health.ws?.total === 'number',
    JSON.stringify(health.ws))
  check('health.codecs 已探测', typeof health.codecs?.opus === 'boolean' && typeof health.codecs?.mp3 === 'boolean',
    JSON.stringify(health.codecs))

  const ota = await (await fetch(`http://127.0.0.1:${p}/xiaozhi/ota/`, { method: 'POST', body: '{}' })).json()
  check('OTA 下发 websocket 配置', ota.websocket?.url === `ws://127.0.0.1:${p}/xiaozhi/v1/`
    && typeof ota.websocket?.token === 'string' && ota.websocket?.version === 1, JSON.stringify(ota.websocket))
  check('OTA 不下发激活码', !ota.activation)

  const client = await TestClient.open(`ws://127.0.0.1:${p}/xiaozhi/v1/`, { 'Device-Id': 'ota-device' })
  client.sendJson({ type: 'hello', version: 1, audio_params: { format: 'pcm16', sample_rate: 16000, frame_duration: 60 } })
  const hello = JSON.parse((await client.next()).text)
  check('插件入口接受升级并回 hello', hello.type === 'hello' && hello.transport === 'websocket')

  const after = await (await fetch(`http://127.0.0.1:${p}/api/health`)).json()
  check('health.ws.live 统计到连接', after.ws.live === 1, `live=${after.ws.live}`)
  client.close()
  await sleep(200)
  const closed = await (await fetch(`http://127.0.0.1:${p}/api/health`)).json()
  check('连接关闭后计数归零', closed.ws.live === 0, `live=${closed.ws.live}`)
  instance.hub?.close()
  instance.close()
}

const mp3 = await ensureMp3()
check('mp3 样本可用', mp3.length > 1000, `${mp3.length} bytes`)
const server = await startServer(mp3)
const port = server.address().port
console.log(`test server on 127.0.0.1:${port}`)

await testOpusClient(port)
await testAbort(port)
await testPcmClient(port)
await testAuth(mp3)
await testPluginWiring()

console.log(`\n${failures === 0 ? 'ALL PASS' : `${failures} FAILED`}`)
server.close()
process.exit(failures === 0 ? 0 : 1)
