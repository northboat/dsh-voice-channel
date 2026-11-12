import http from 'node:http'
import path from 'node:path'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { readState, writeState, patchState, MEMORY_PATH, validPin } from './state.js'
import { asr } from './asr.js'
import { handleAsr } from './asr_http.js'
import { tts } from './tts.js'
import { handleTts, handleTtsVoices } from './tts_http.js'

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
    .map((row) => ({ ...row, online: now - row.lastSeen < 5 * 60 * 1000 }))
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

function detectEmotion(text) {
  const t = String(text || '')
  if (/失败|异常|错误|无法|error/i.test(t)) return 'error'
  if (/开心|高兴|太棒|成功|完成|哈哈|恭喜/.test(t)) return 'happy'
  if (/难过|遗憾|可惜|伤心|抱歉/.test(t)) return 'sad'
  if (/生气|愤怒|烦|讨厌/.test(t)) return 'angry'
  if (/居然|竟然|惊讶|没想到|不会吧/.test(t)) return 'surprised'
  if (/思考|分析|计算|首先|让我想|步骤/.test(t)) return 'thinking'
  if (/困|睡|晚安|休息/.test(t)) return 'sleepy'
  return 'neutral'
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

async function* endpointChat(endpoint, text, sessionId) {
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
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() || ''
    for (const line of lines) {
      if (!line.trim()) continue
      try { yield String(JSON.parse(line).text ?? JSON.parse(line).delta ?? '') } catch { yield line }
    }
  }
  if (buffer.trim()) { try { yield String(JSON.parse(buffer).text ?? '') } catch { yield buffer } }
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

async function handleBridge(req, res, pathname) {
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
      if (await handleBridge(req, res, url.pathname)) return
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
      send(res, 200, { ok: true, pluginLoaded: Boolean(runtimeCtx), backend: lastBackend, lastError, sessionId: runtimeInfo.__sessionId ?? null, lastVia: runtimeInfo.__lastVia ?? null, asr: asr.status(), tts: tts.status(), probe, port: PORT })
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
  return server
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
      const pathname = new URL(req.url || '/', 'http://localhost').pathname
      const handled = await handleBridge(req, res, pathname)
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
  server.listen(PORT, HOST, () => console.log(`[dsh-voice-channel] client endpoint http://localhost:${PORT}`))
  server.on('error', (error) => console.error('[dsh-voice-channel] server error:', error.message))
  return () => {
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
