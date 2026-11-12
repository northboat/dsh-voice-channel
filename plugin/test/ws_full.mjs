// 全链路（除 DSH 模型外都是真的）：插件真实 createServer + 真 Vosk ASR + 真 edge-tts，
// 用 testdata/zh.wav 当「用户说的话」，走 /xiaozhi/v1/ 一条 WS 跑完 上行 → 识别 → 回复 → 逐句音频下行。
import { readFileSync } from 'node:fs'
import { createServer } from '../lib/index.js'
import { createOpusCodec } from '../lib/opus.js'
import { TestClient } from './ws_client.mjs'

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
let failures = 0
const check = (name, ok, detail = '') => {
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ` — ${detail}` : ''}`)
  if (!ok) failures += 1
}

function wavPcm(buffer) {
  let offset = 12
  while (offset + 8 <= buffer.length) {
    const id = buffer.toString('latin1', offset, offset + 4)
    const size = buffer.readUInt32LE(offset + 4)
    if (id === 'data') return buffer.subarray(offset + 8, Math.min(offset + 8 + size, buffer.length))
    offset += 8 + size + (size % 2)
  }
  throw new Error('wav 里没有 data')
}

const wav = readFileSync(new URL('../testdata/zh.wav', import.meta.url))
const pcm = wavPcm(wav)
console.log(`zh.wav pcm ${pcm.length} bytes (${(pcm.length / 2 / 16000).toFixed(1)}s)`)

const server = createServer()
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
const port = server.address().port
console.log(`plugin server on 127.0.0.1:${port}`)

const client = await TestClient.open(`ws://127.0.0.1:${port}/xiaozhi/v1/`)
const uplink = await createOpusCodec(16000)

client.sendJson({
  type: 'hello',
  version: 1,
  transport: 'websocket',
  audio_params: { format: 'opus', sample_rate: 16000, channels: 1, frame_duration: 60 },
})
const hello = JSON.parse((await client.next()).text)
check('hello', hello.type === 'hello' && hello.audio_params?.sample_rate === 24000, JSON.stringify(hello.audio_params))

client.sendJson({ type: 'listen', state: 'start', mode: 'manual' })
const frameSamples = 960
for (let offset = 0; offset < pcm.length; offset += frameSamples * 2) {
  const slice = pcm.subarray(offset, Math.min(offset + frameSamples * 2, pcm.length))
  const samples = new Int16Array(frameSamples)
  for (let i = 0; i < samples.length; i += 1) {
    const at = i * 2
    samples[i] = at + 1 < slice.length ? slice.readInt16LE(at) : 0
  }
  client.sendBinary(uplink.encode(samples))
}
const sentAt = Date.now()
client.sendJson({ type: 'listen', state: 'stop' })

const first = await client.next(20000)
const stt = JSON.parse(first.text)
const asrMs = Date.now() - sentAt
check('stt 是真实识别结果', stt.type === 'stt' && stt.text.length >= 5, `"${stt.text}" (${asrMs}ms)`)
check('识别内容与样本一致', /打开|镜像|文件/.test(stt.text), stt.text)

const frames = []
let audio = 0
let sentences = []
let firstAudioMs = null
let emotion = null
let ttsStartMs = null
for (;;) {
  const frame = await client.next(30000)
  if (frame.binary) {
    audio += 1
    if (firstAudioMs === null) firstAudioMs = Date.now() - sentAt
    if (audio <= 400) frames.push(frame.binary)
    continue
  }
  const body = JSON.parse(frame.text)
  if (body.type === 'llm') emotion = body.emotion
  if (body.type === 'tts' && body.state === 'start') ttsStartMs = Date.now() - sentAt
  if (body.type === 'tts' && body.state === 'sentence_start') sentences.push(body.text)
  if (body.type === 'tts' && body.state === 'stop') break
}

check('tts start', ttsStartMs !== null, `${ttsStartMs}ms`)
check('llm 情绪', typeof emotion === 'string', `emotion=${emotion}`)
check('逐句下发', sentences.length >= 1, JSON.stringify(sentences))
check('首帧音频延迟', firstAudioMs !== null && firstAudioMs < 20000, `${firstAudioMs}ms`)
check('音频帧数', audio > 10, `${audio} 帧 ≈ ${((audio * 60) / 1000).toFixed(1)}s`)

const decoder = await createOpusCodec(24000)
let samples = 0
let peak = 0
for (const packet of frames) {
  const out = decoder.decode(packet, 1440)
  samples += out.length
  for (let i = 0; i < out.length; i += 17) peak = Math.max(peak, Math.abs(out[i]))
}
check('下行 Opus 解码出非静音 PCM', samples > 24000 && peak > 500, `samples=${samples} peak=${peak}`)

console.log('\n== health ==')
const health = await (await fetch(`http://127.0.0.1:${port}/api/health`)).json()
check('asr ready', health.asr?.ready === true, String(health.asr?.error ?? ''))
check('tts ready', health.tts?.ready === true, String(health.tts?.error ?? ''))
check('codecs', health.codecs?.opus === true && health.codecs?.mp3 === true)

console.log(`\n${failures === 0 ? 'ALL PASS' : `${failures} FAILED`}`)
client.close()
server.hub?.close()
server.close()
setTimeout(() => process.exit(failures === 0 ? 0 : 1), 300)
