// 独立 ASR 服务：不依赖 Desktop 的插件生命周期，改代码即时生效。
//   node asr-server.mjs            # 0.0.0.0:8788
//   ASR_PORT=9000 node asr-server.mjs
import http from 'node:http'
import { handleAsr, send, cors } from './lib/asr_http.js'
import { asr } from './lib/asr.js'
import { tts } from './lib/tts.js'
import { handleTts, handleTtsVoices } from './lib/tts_http.js'

const PORT = Number(process.env.ASR_PORT || 8788)
const HOST = process.env.ASR_HOST || '0.0.0.0'

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`)
  if (req.method === 'OPTIONS') {
    cors(res)
    res.writeHead(204)
    res.end()
    return
  }
  try {
    if (req.method === 'GET' && url.pathname === '/api/health') {
      send(res, 200, { ok: true, service: 'dsh-voice-channel-asr', port: PORT, asr: asr.status(), tts: tts.status() })
      return
    }
    if (req.method === 'POST' && url.pathname === '/api/asr') {
      await handleAsr(req, res)
      return
    }
    if ((req.method === 'GET' || req.method === 'POST') && url.pathname === '/api/tts') {
      await handleTts(req, res, url)
      return
    }
    if (req.method === 'GET' && url.pathname === '/api/tts/voices') {
      await handleTtsVoices(req, res, url)
      return
    }
    send(res, 404, { error: 'not found' })
  } catch (error) {
    if (!res.headersSent) send(res, 500, { error: String(error?.message ?? error) })
    else res.end()
  }
})

server.listen(PORT, HOST, () => {
  console.log(`[dsh-voice-channel][asr] listening on http://${HOST}:${PORT}/api/asr`)
})
server.on('error', (error) => console.error('[dsh-voice-channel][asr] server error:', error.message))

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    asr.dispose()
    tts.dispose()
    server.close(() => process.exit(0))
  })
}
