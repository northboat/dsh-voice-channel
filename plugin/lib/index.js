import http from 'node:http'
import path from 'node:path'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { readState, writeState, patchState, MEMORY_PATH, validPin } from './state.js'
import { asr } from './asr.js'
import { handleAsr } from './asr_http.js'
import { tts } from './tts.js'
import { handleTts, handleTtsVoices } from './tts_http.js'
import { detectEmotion } from './text.js'
import { attachWebSocket } from './ws.js'
import { XiaozhiSession } from './xiaozhi.js'
import { opusAvailable } from './opus.js'
import { mp3Available } from './mp3.js'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const PAGE_PATH = path.join(HERE, '..', 'public', 'index.html')
const ADMIN_PATH = path.join(HERE, '..', 'public', 'admin.html')
const PORT = Number(process.env.PORT || 8787)
const HOST = process.env.HOST || '0.0.0.0'

let runtimeCtx = null
let runtimeInfo = {}
let lastBackend = 'demo'
let lastError = ''
const eventListeners = new Set()
const clients = new Map()
/** WS 长连接的会话表（管理页「在线设备」用；连接关掉就删）。 */
const wsSessions = new Map()
const codecs = { opus: false, mp3: false }
let wsHub = null
let wsTotal = 0

// 懒探测一次：缺依赖时 HTTP 路由照常可用，只有 xiaozhi 设备的 Opus 链路会不可用。
Promise.all([opusAvailable(), mp3Available()]).then(([opus, mp3]) => {
  codecs.opus = opus
  codecs.mp3 = mp3
  if (!opus && !mp3) console.warn('[dsh-voice-channel] opusscript / mpg123-decoder 不可用，仅 pcm16 client 能出声')
}).catch(() => {})

function isLoopback(req) {
  const ip = String(req.socket?.remoteAddress ?? '')
  return ip === '::1' || ip === '127.0.0.1' || ip.startsWith('::ffff:127.')
}

function pinFrom(req) {
  const auth = String(req.headers.authorization ?? '')
  return (auth.startsWith('Bearer ') ? auth.slice(7).trim() : '') || String(req.headers['x-pin'] ?? '').trim()
}

/** 设备标识：client 侧固定带 device-id，没有就退回 payload 里的字段。 */
function clientIdOf(req, payload) {
  return String(
    req.headers['device-id'] ?? req.headers['x-device-id'] ?? payload?.deviceId ?? payload?.sessionId ?? 'unknown',
  ).slice(0, 64)
}

function noteClient(req, payload, auth) {
  const id = clientIdOf(req, payload)
  const now = Date.now()
  const row = clients.get(id) ?? { id, firstSeen: now, requests: 0 }
  row.ip = String(req.socket?.remoteAddress ?? row.ip ?? '')
  row.lastSeen = now
  row.requests += 1
  row.auth = auth
  clients.set(id, row)
}

function clientList() {
  const now = Date.now()
  return [...clients.values()]
    .sort((a, b) => b.lastSeen - a.lastSeen)
    .map((row) => ({
      ...row,
      online: now - row.lastSeen < 5 * 60 * 1000,
      ws: wsSessions.get(row.id)?.stats() ?? null,
    }))
}

/** WS 连接也算一次「设备上线」：长连接没有请求头，握手信息里取 device-id。 */
function noteWsClient(conn, deviceId) {
  const id = String(deviceId || conn.remote || 'unknown').slice(0, 64)
  const now = Date.now()
  const row = clients.get(id) ?? { id, firstSeen: now, requests: 0 }
  row.ip = conn.remote || row.ip || ''
  row.lastSeen = now
  row.requests += 1
  row.auth = 'ws'
  row.transport = 'ws'
  clients.set(id, row)
  return id
}

function ownNames(value) {
  if (!value) return []
  const out = new Set()
  for (let obj = value; obj && obj !== Object.prototype; obj = Object.getPrototypeOf(obj)) {
    Object.getOwnPropertyNames(obj).forEach((key) => out.add(key))
  }
  return [...out].filter((key) => !key.startsWith('_') && !['constructor', 'toString', 'valueOf'].includes(key))
}

function cors(res) {
  res.setHeader('access-control-allow-origin', '*')
  res.setHeader('access-control-allow-headers', 'content-type, authorization, x-pin, device-id, x-device-id')
  res.setHeader('access-control-allow-methods', 'GET, POST, OPTIONS')
  res.setHeader('cache-control', 'no-store')
}

function send(res, code, body, type = 'application/json; charset=utf-8') {
  cors(res)
  res.writeHead(code, { 'content-type': type })
  res.end(typeof body === 'string' ? body : JSON.stringify(body))
}

async function* demoChat(text) {
  const reply = text
    ? `收到：「${text}」。\n\n演示后端：DSH 会话未就绪。请重新加载插件后查看 /api/health。`
    : '说点什么试试。'
  for (const chunk of reply.match(/[\s\S]{1,10}/g) || []) {
    await new Promise((resolve) => setTimeout(resolve, 24))
    yield chunk
  }
}

async function* endpointChat(endpoint, text, context) {
  // 调用方统一传 `{ sessionId, clientId }`（与 chat.js 的 createChat 一致），这里兼容旧的字符串写法。
  const sessionId = typeof context === 'string' ? context : context?.sessionId
  const response = await fetch(endpoint, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ text, sessionId }),
  })
  if (!response.ok) throw new Error(`DSH endpoint returned ${response.status}`)
  if (!response.body) { yield await response.text(); return }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let sawDelta = false
  // NDJSON：`delta` 是增量，`done` 又带一遍完整文本。两个都吐会把整段回复说两遍，
  // 所以只在「一条 delta 都没见过」时才拿 done 兜底。
  const emit = function* (line) {
    if (!line.trim()) return
    let message
    try { message = JSON.parse(line) } catch { yield line; return }
    const type = String(message?.type ?? '')
    if (type === 'delta') {
      if (message.text) { sawDelta = true; yield String(message.text) }
      return
    }
    if (type === 'done' || type === '') {
      if (!sawDelta && message.text) { sawDelta = true; yield String(message.text) }
      return
    }
    if (type === 'error' && message.error) throw new Error(String(message.error))
  }
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() || ''
    for (const line of lines) yield* emit(line)
  }
  yield* emit(buffer)
}

async function loadChat() {
  const endpoint = process.env.DSH_CHAT_ENDPOINT
  if (endpoint) {
    lastBackend = 'endpoint'
    return (text, sessionId) => endpointChat(endpoint, text, sessionId)
  }
  if (runtimeCtx) {
    try {
      const mod = await import(`./chat.js?t=${Date.now()}`)
      if (typeof mod.createChat === 'function') {
        lastBackend = 'dsh'
        return mod.createChat(runtimeCtx, runtimeInfo)
      }
    } catch (error) {
      lastError = String(error?.message ?? error)
    }
  }
  lastBackend = 'demo'
  return demoChat
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = ''
    req.on('data', (chunk) => {
      data += chunk
      if (data.length > 64 * 1024) req.destroy()
    })
    req.on('end', () => resolve(data))
    req.on('error', reject)
  })
}

async function handleChat(req, res) {
  let payload
  try {
    payload = JSON.parse((await readBody(req)) || '{}')
  } catch {
    send(res, 400, { error: 'invalid JSON' })
    return
  }
  const pin = readState().pin
  const auth = isLoopback(req) ? 'loopback' : (pinFrom(req) === pin ? 'pin' : 'denied')
  noteClient(req, payload, auth)
  if (auth === 'denied') {
    send(res, 401, { error: 'PIN required', hint: 'Authorization: Bearer <PIN> 或 X-PIN: <PIN>' })
    return
  }
  const text = String(payload.text ?? '')
  const sessionId = String(payload.sessionId ?? 'default')
  const clientId = clientIdOf(req, payload)
  const chat = await loadChat()
  cors(res)
  res.writeHead(200, { 'content-type': 'application/x-ndjson; charset=utf-8', 'connection': 'keep-alive' })
  const write = (message) => res.write(`${JSON.stringify(message)}\n`)
  write({ type: 'state', state: 'thinking' })
  let full = ''
  try {
    for await (const chunk of chat(text, { sessionId, clientId })) {
      full += chunk
      write({ type: 'delta', text: chunk })
    }
  } catch (error) {
    lastError = String(error?.message ?? error)
    write({ type: 'error', message: lastError })
  }
  const emotion = detectEmotion(full)
  write({ type: 'emotion', emotion })
  write({ type: 'done', text: full, emotion })
  res.end()
}

function bridgeState(res, req) {
  const state = readState()
  const probe = {}
  for (const [key, value] of Object.entries(runtimeInfo)) {
    if (!key.startsWith('__')) probe[key] = value
  }
  const local = isLoopback(req)
  send(res, 200, {
    persona: state.persona,
    roles: state.roles,
    activeRoleId: state.activeRoleId,
    bindings: state.bindings,
    memory: local ? state.memory : [],
    sessionId: state.sessionId,
    tts: state.tts,
    ...(local ? { pin: state.pin } : {}),
    memoryLogPath: MEMORY_PATH,
    runtime: {
      backend: lastBackend,
      sessionId: runtimeInfo.__sessionId ?? null,
      modelSelected: probe.modelSelected ?? null,
      workspaceTitle: probe.workspaceTitle ?? null,
    },
  })
}

function wsStats() {
  return {
    live: wsSessions.size,
    total: wsTotal,
    sessions: [...wsSessions.values()].map((session) => session.stats()),
  }
}

/** 运行状态：管理页「检查运行状态」用同一份，不再单开模块/接口。 */
function healthPayload() {
  return {
    ok: true,
    lastError: lastError ?? null,
    backend: lastBackend,
    sessionId: runtimeInfo.__sessionId ?? null,
    modelSelected: runtimeInfo.modelSelected ?? null,
    workspaceTitle: runtimeInfo.workspaceTitle ?? null,
    asr: asr.status(),
    tts: tts.status(),
    ws: wsStats(),
    codecs,
  }
}

/**
 * OTA（设备开机第一步）：xiaozhi 设备靠这里的响应把 WS 地址与 token 写进 NVS，
 * 不改固件就能指向本插件。字段见 `xiaozhi-esp32/main/ota.cc:180-199`。
 */
function handleOta(req, res) {
  const host = String(req.headers.host ?? '').trim() || `127.0.0.1:${PORT}`
  send(res, 200, {
    server_time: { timestamp: Date.now(), timezone_offset: 480 },
    websocket: { url: `ws://${host}/xiaozhi/v1/`, token: readState().pin ?? '', version: 1 },
  })
}

async function handleBridge(req, res, url) {
  const pathname = url.pathname
  // 管理页是嵌在 DSH 里的同源 iframe，/api/* 会打到引擎自己身上（回 "not found"）。
  // 所以管理页需要的东西全部挂在插件自己的前缀下。
  // 纯 ping：不读引擎、不建会话，能回包就说明这条通道是通的。
  if (pathname === '/dsh-voice-channel/ping' && req.method === 'GET') {
    send(res, 200, { ok: true, at: Date.now() })
    return true
  }
  if (pathname === '/dsh-voice-channel/health' && req.method === 'GET') {
    send(res, 200, healthPayload())
    return true
  }
  if (pathname === '/dsh-voice-channel/voices' && req.method === 'GET') {
    await handleTtsVoices(req, res, url)
    return true
  }
  if (pathname === '/dsh-voice-channel/tts' && (req.method === 'GET' || req.method === 'POST')) {
    await handleTts(req, res, url)
    return true
  }
  if (pathname === '/dsh-voice-channel/memory.log' && req.method === 'GET') {
    if (!isLoopback(req)) { send(res, 401, { error: 'local only' }); return true }
    let text = ''
    try { text = readFileSync(MEMORY_PATH, 'utf8') } catch { /* 尚未产生记忆 */ }
    send(res, 200, text, 'text/plain; charset=utf-8')
    return true
  }
  if (pathname === '/dsh-voice-channel/clients' && req.method === 'GET') {
    if (!isLoopback(req)) { send(res, 401, { error: 'local only' }); return true }
    send(res, 200, clientList())
    return true
  }
  if (pathname === '/admin' || pathname === '/dsh-voice-channel/admin') {
    send(res, 200, readFileSync(ADMIN_PATH, 'utf8'), 'text/html; charset=utf-8')
    return true
  }
  if (pathname !== '/api/dsh-voice-channel/state') return false
  if (req.method === 'GET') { bridgeState(res, req); return true }
  if (req.method === 'POST') {
    let raw = ''
    for await (const chunk of req) raw += chunk
    let payload
    try { payload = JSON.parse(raw || '{}') } catch { send(res, 400, { error: 'invalid JSON' }); return true }
    const current = readState()
    writeState({
      ...patchState(current, payload),
      pin: isLoopback(req) && validPin(payload.pin) ? String(payload.pin) : current.pin,
      tts: payload.tts && typeof payload.tts === 'object' ? payload.tts : current.tts,
    })
    bridgeState(res, req)
    return true
  }
  send(res, 405, { error: 'method not allowed' })
  return true
}

export function createServer() {
  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`)
    if (req.method === 'OPTIONS') { cors(res); res.writeHead(204); res.end(); return }
    try {
      if (await handleBridge(req, res, url)) return
    } catch (error) {
      send(res, 500, { error: String(error?.message ?? error) })
      return
    }
    if (req.method === 'GET' && url.pathname === '/') { send(res, 200, readFileSync(PAGE_PATH, 'utf8'), 'text/html; charset=utf-8'); return }
    if (req.method === 'GET' && url.pathname === '/api/health') {
      const probe = {}
      for (const [key, value] of Object.entries(runtimeInfo)) {
        if (!key.startsWith('__')) probe[key] = value
      }
      send(res, 200, { ok: true, pluginLoaded: Boolean(runtimeCtx), backend: lastBackend, lastError, sessionId: runtimeInfo.__sessionId ?? null, lastVia: runtimeInfo.__lastVia ?? null, asr: asr.status(), tts: tts.status(), ws: wsStats(), codecs, probe, port: PORT })
      return
    }
    if ((req.method === 'POST' || req.method === 'GET') && url.pathname.startsWith('/xiaozhi/ota')) {
      handleOta(req, res)
      return
    }
    if (req.method === 'POST' && url.pathname === '/api/chat') {
      handleChat(req, res).catch((error) => {
        if (!res.headersSent) send(res, 500, { error: String(error?.message ?? error) })
        else res.end()
      })
      return
    }
    if (req.method === 'POST' && url.pathname === '/api/asr') {
      handleAsr(req, res).catch((error) => {
        if (!res.headersSent) send(res, 500, { error: String(error?.message ?? error) })
        else res.end()
      })
      return
    }
    if ((req.method === 'GET' || req.method === 'POST') && url.pathname === '/api/tts') {
      handleTts(req, res, url).catch((error) => {
        if (!res.headersSent) send(res, 500, { error: String(error?.message ?? error) })
        else res.end()
      })
      return
    }
    if (req.method === 'GET' && url.pathname === '/api/tts/voices') {
      handleTtsVoices(req, res, url).catch((error) => {
        if (!res.headersSent) send(res, 500, { error: String(error?.message ?? error) })
        else res.end()
      })
      return
    }
    send(res, 404, { error: 'not found' })
  })
  server.hub = attachWebSocket(server, {
    paths: ['/ws', '/xiaozhi/v1'],
    authorize: (req, url) => authorizeUpgrade(req, url),
    onConnection: (conn, req) => {
      const ref = { id: '' }
      const session = new XiaozhiSession(conn, req, {
        loadChat,
        asr,
        tts,
        noteClient: (c, deviceId) => { ref.id = noteWsClient(c, deviceId) },
      }, (closed) => {
        for (const [key, value] of wsSessions) if (value === closed) wsSessions.delete(key)
      })
      wsSessions.set(ref.id || session.deviceId, session)
      wsTotal += 1
    },
  })
  return server
}

/** 握手鉴权：本机免 PIN；LAN 用 Authorization: Bearer <PIN> / X-PIN / ?pin=。 */
function authorizeUpgrade(req, url) {
  if (isLoopback(req)) return true
  const pin = String(readState().pin ?? '')
  if (!pin) return false
  const auth = String(req.headers.authorization ?? '')
  const token = (auth.startsWith('Bearer ') ? auth.slice(7).trim() : '')
    || String(req.headers['x-pin'] ?? '').trim()
    || String(url.searchParams.get('pin') ?? '').trim()
  return token === pin
}

export const name = 'dsh-voice-channel'

export function apply(ctx) {
  runtimeCtx = ctx || null
  runtimeInfo = { hasCtx: true, sessionEvents: 0 }

  // 引擎的会话事件：chat.js 订阅它来拿回复。on() 本身不需要 inject。
  try {
    ctx.on('session/event', (session, event) => {
      runtimeInfo.sessionEvents = (runtimeInfo.sessionEvents ?? 0) + 1
      for (const listener of eventListeners) {
        try { listener(session, event) } catch { /* listener must not break the engine */ }
      }
    })
  } catch (error) {
    runtimeInfo.eventError = String(error?.message ?? error)
  }

  // 服务必须先 inject 才能取（dsh-session-delete: inject=['webServer']）。
  const inject = (name, collect) => {
    try {
      ctx.inject([name], (scoped) => collect(scoped, scoped[name]))
    } catch (error) {
      runtimeInfo[`${name}InjectError`] = String(error?.message ?? error)
    }
  }

  inject('sessions', (_scoped, sessions) => {
    runtimeInfo.hasSessions = true
    runtimeInfo.sessionsMethods = ownNames(sessions).slice(0, 40)
    runtimeInfo.__sessions = sessions
  })

  inject('webServer', (_scoped, web) => {
    runtimeInfo.hasWebServer = true
    runtimeInfo.webServerMethods = ownNames(web).slice(0, 40)
    runtimeInfo.__webServer = web
    try { runtimeInfo.webPort = web.port ?? web.address?.()?.port ?? web.server?.address?.()?.port } catch { /* ignore */ }
    const token = web.token ?? web.launchToken ?? web.config?.token
    if (token) { runtimeInfo.webTokenFound = true; runtimeInfo.__token = String(token) }
    // 同源路由：左侧边栏主面板用 iframe 打开 /dsh-voice-channel/admin，管理 API 走同源。
    // prefix 不能带尾斜杠：WebServer.match() 用 startsWith(prefix + '/') 判定。
    const bridgeHandler = async (req, res) => {
      const url = new URL(req.url || '/', 'http://localhost')
      const handled = await handleBridge(req, res, url)
      if (!handled) send(res, 404, { error: 'not found' })
    }
    try {
      web.register({ kind: 'prefix', path: '/dsh-voice-channel', handler: bridgeHandler })
      web.register({ kind: 'exact', path: '/api/dsh-voice-channel/state', handler: bridgeHandler })
      runtimeInfo.bridgeRoutes = true
    } catch (error) {
      runtimeInfo.bridgeRouteError = String(error?.message ?? error)
    }
  })

  inject('sessionController', (_scoped, controller) => {
    runtimeInfo.hasController = true
    runtimeInfo.controllerMethods = ownNames(controller).slice(0, 40)
    runtimeInfo.__controller = controller
  })

  inject('agents', (_scoped, agents) => {
    runtimeInfo.hasAgents = true
    runtimeInfo.agentsMethods = ownNames(agents).slice(0, 40)
    runtimeInfo.__agents = agents
  })

  runtimeInfo.__subscribe = (listener) => {
    eventListeners.add(listener)
    return () => eventListeners.delete(listener)
  }

  const server = createServer()
  wsHub = server.hub ?? null
  server.listen(PORT, HOST, () => console.log(`[dsh-voice-channel] client endpoint http://localhost:${PORT} ws://localhost:${PORT}/xiaozhi/v1/`))
  server.on('error', (error) => console.error('[dsh-voice-channel] server error:', error.message))
  return () => {
    wsHub?.close()
    wsHub = null
    server.close()
    asr.dispose()
    tts.dispose()
    eventListeners.clear()
    runtimeCtx = null
    runtimeInfo = {}
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const server = createServer()
  server.listen(PORT, HOST, () => console.log(`[dsh-voice-channel] demo backend, open http://localhost:${PORT}`))
  server.on('error', (error) => {
    console.error(`[dsh-voice-channel] cannot listen on ${HOST}:${PORT}: ${error.message}`)
    process.exitCode = 1
  })
}
