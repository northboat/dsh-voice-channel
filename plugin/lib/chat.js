// 插件专用会话：client 输入进同一个 DSH session，回复从 session/event 收回。
// 每次 /api/chat 动态 import，改本文件无需重载 Desktop 插件。
import { readState, writeState, promptOf, contextOf, appendTurn } from './state.js'

const SESSION_PREFIX = 'dsh-voice-channel-'
const WORKSPACE_TITLE = 'dsh-voice-channel'
// 角色 / 记忆改由 system prompt 承载：只注册一个 section，不写进用户消息与会话历史。
const SECTION_NAME = 'dsh-voice-channel:context'
const SECTION_ORDER = 20000
// 改 section 闭包时递增：热加载会先 dispose 旧 section 再注册，不必重载 Desktop。
const SECTION_VERSION = 1

// 事件字段见 dsh-Go/packages/dsh_client/lib/src/models.dart:310-441：
// assistant/message  data.message.content[] = {type:'text', text}
// assistant/attempt  data.stream[] = {type:'text-chunks', texts:[]}
//                                  | {type:'chunk', chunk:{type:'text-delta', text}}
// 错误：attempt 的 {type:'chunk', chunk:{type:'finish', reason:{kind:'error', failure:{code,message}}}}
//       turn/end 的 data.reason = {kind:'error', error:{code,message}}
function textOfMessage(message) {
  const content = message?.content
  if (typeof content === 'string') return content
  if (!Array.isArray(content)) return ''
  return content
    .filter((block) => block && (block.type === 'text' || block.type === 'output_text'))
    .map((block) => String(block.text ?? ''))
    .join('')
}

function textOfStream(stream) {
  if (!Array.isArray(stream)) return ''
  let out = ''
  for (const record of stream) {
    if (!record || typeof record !== 'object') continue
    if (record.type === 'text-chunks' && Array.isArray(record.texts)) {
      out += record.texts.filter((text) => typeof text === 'string').join('')
    } else if (record.type === 'chunk' && record.chunk?.type === 'text-delta' && typeof record.chunk.text === 'string') {
      out += record.chunk.text
    }
  }
  return out
}

function textOfEvent(event) {
  if (!event || typeof event !== 'object') return ''
  const type = String(event.type ?? '')
  const data = event.data ?? event
  if (type === 'assistant/message') return textOfMessage(data?.message ?? data)
  if (type === 'assistant/attempt') return textOfStream(data?.stream)
  return ''
}

function reasonError(reason) {
  const failure = reason?.error ?? reason?.failure ?? {}
  const code = failure?.code ?? reason?.code
  const message = failure?.message ?? reason?.message ?? 'model call failed'
  return `${code ? `[${code}] ` : ''}${message}`
}

function errorOfEvent(event) {
  if (!event || typeof event !== 'object') return ''
  const type = String(event.type ?? '')
  const data = event.data ?? event
  if (type === 'turn/end' && data?.reason?.kind === 'error') return reasonError(data.reason)
  if (type === 'assistant/attempt' && Array.isArray(data?.stream)) {
    for (const record of data.stream) {
      const chunk = record?.type === 'chunk' ? record.chunk : null
      if (chunk?.type === 'finish' && chunk.reason?.kind === 'error') return reasonError(chunk.reason)
    }
  }
  return ''
}

// attempt 事件可能带增量批次，也可能带累积快照；只补发新增后缀，避免重复。
function mergeDelta(full, incoming, authoritative) {
  if (!incoming) return ''
  if (full && incoming.startsWith(full)) return incoming.slice(full.length)
  if (authoritative) return full ? '' : incoming
  return incoming
}

function webConfig(info) {
  const web = info.__webServer
  let base = null
  let token = info.__token ?? null
  try {
    const origin = web?.getOrigin?.()
    if (typeof origin === 'string' && /^https?:/.test(origin)) {
      base = origin.replace(/[/?#].*$/, '')
      const fromUrl = origin.match(/[?&]token=([^&]+)/)
      if (fromUrl) token = decodeURIComponent(fromUrl[1])
    }
  } catch { /* ignore */ }
  if (!base && info.webPort) base = `http://127.0.0.1:${info.webPort}`
  if (!token) {
    const config = web?.config
    if (config && typeof config === 'object') {
      for (const [key, value] of Object.entries(config)) {
        if (/token|secret/i.test(key) && typeof value === 'string') { token = value; break }
      }
    }
  }
  return { base, token }
}

async function rpcViaHttp(info, method, args) {
  const { base, token } = webConfig(info)
  if (!base) throw new Error('no web origin')
  const headers = { 'content-type': 'application/json', origin: base }
  if (token) {
    const login = await fetch(`${base}/?token=${encodeURIComponent(token)}`, { redirect: 'manual', headers: { origin: base } })
    const cookie = login.headers.getSetCookie?.().map((v) => v.split(';')[0]).join('; ')
    if (cookie) headers.cookie = cookie
  }
  const response = await fetch(`${base}/api/${method}`, {
    method: 'POST',
    headers,
    body: JSON.stringify({ type: 'client-request', rpcId: `esp32-${Date.now()}`, method, payload: { args } }),
  })
  const raw = await response.text()
  let body
  try { body = JSON.parse(raw) } catch { throw new Error(`${method}: ${response.status} ${raw.slice(0, 80)}`) }
  if (!response.ok || body?.result?.ok === false) throw new Error(`${method}: ${JSON.stringify(body?.result?.error ?? response.status)}`)
  return body?.result?.value ?? body?.value ?? body
}

function findRpc(ctx, info) {
  const candidates = []
  for (const service of [ctx, info.__webServer, info.__controller, info.__sessions]) {
    if (!service) continue
    for (const key of ['call', 'rpc', 'invoke', 'request', 'dispatch', 'callRpc', 'handleRpc']) {
      try {
        if (typeof service[key] === 'function') candidates.push((method, args) => service[key](method, { args }))
      } catch { /* not injectable */ }
    }
  }
  candidates.push((method, args) => rpcViaHttp(info, method, args))
  return candidates
}

async function createSession(ctx, info, rpcs) {
  const errors = []
  for (const rpc of rpcs) {
    try {
      const created = await rpc('session/create', { request: { cwd: process.cwd() } })
      if (created?.sessionId) { info.__createVia = 'session/create'; return created.sessionId }
    } catch (error) {
      errors.push(`session/create: ${error?.message ?? error}`)
    }
  }
  const id = `${SESSION_PREFIX}${Date.now()}`
  const controller = info.__controller
  if (typeof controller?.create === 'function') {
    try {
      const created = await controller.create({ sessionId: id, cwd: process.cwd() })
      const sid = created?.sessionId ?? created?.id ?? id
      for (const [target, key] of [[info.__sessions, 'enter'], [info.__agents, 'enter'], [info.__sessions, 'prepare'], [info.__agents, 'resume']]) {
        if (typeof target?.[key] === 'function') {
          try { await target[key](sid) } catch { /* best effort */ }
        }
      }
      info.__createVia = 'controller.create+enter'
      return sid
    } catch (error) {
      errors.push(`controller.create: ${error?.message ?? error}`)
    }
  }
  for (const [target, key] of [[controller, 'list'], [info.__sessions, 'list']]) {
    if (typeof target?.[key] !== 'function') continue
    try {
      const rows = await target[key]()
      const first = Array.isArray(rows) ? rows[0] : rows?.sessions?.[0] ?? rows?.items?.[0]
      const sid = first?.sessionId ?? first?.id
      if (sid) { info.__createVia = 'reuse-existing'; return String(sid) }
    } catch (error) {
      errors.push(`${key}: ${error?.message ?? error}`)
    }
  }
  info.__createErrors = errors
  throw new Error(`cannot create DSH session: ${errors.join(' | ')}`)
}

async function sendPrompt(ctx, info, rpcs, sessionId, text) {
  const request = {
    sessionId,
    requestId: `${SESSION_PREFIX}${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
    mode: 'queue',
    content: [{ type: 'text', text }],
  }
  const signal = new AbortController().signal
  const errors = []
  const controller = info.__controller
  if (typeof controller?.prompt === 'function') {
    try {
      await controller.prompt(request, signal)
      return 'sessionController.prompt'
    } catch (error) {
      errors.push(`controller.prompt: ${error?.message ?? error}`)
    }
  }
  for (const rpc of rpcs) {
    try {
      await rpc('session/prompt', { request })
      return 'session/prompt'
    } catch (error) {
      errors.push(`session/prompt: ${error?.message ?? error}`)
    }
  }
  const session = info.__sessions?.get?.(sessionId)
  const agent = info.__agents?.get?.(sessionId)
  const userMessage = { id: request.requestId, role: 'user', content: request.content, source: { kind: 'user', rpcId: request.requestId } }
  for (const [target, key, args] of [
    [agent, 'followup', userMessage],
    [session, 'prompt', request],
    [session, 'send', request],
  ]) {
    if (typeof target?.[key] !== 'function') continue
    try { await target[key](args); return key } catch (error) { errors.push(`${key}: ${error?.message ?? error}`) }
  }
  throw new Error(`no prompt entry: ${errors.join(' | ')}`)
}

function sessionEventsOf(info, sessionId) {
  const session = info.__sessions?.get?.(sessionId) ?? info.__agents?.get?.(sessionId)?.session
  const events = session?.snapshotEvents?.()
  return Array.isArray(events) ? events : []
}

function replyFrom(events) {
  let full = ''
  for (const event of events) {
    const type = String(event?.type ?? '')
    if (type !== 'assistant/attempt' && type !== 'assistant/message') continue
    full += mergeDelta(full, textOfEvent(event), type === 'assistant/message')
  }
  return full
}

// 会话默认模型可能仍指向需要 API Key 的 deepseek-official；把专用会话切到当前有凭据的路由
// （DeepSeek 账号登录时为 deepseek-account，走账号余额，无需 API Key）。
async function ensureModel(info, sessionId) {
  if (info.__modelFor === sessionId) return
  const controller = info.__controller
  if (typeof controller?.modelCatalog !== 'function' || typeof controller?.selectModel !== 'function') return
  const catalog = await controller.modelCatalog()
  const providers = catalog?.routableProviders ?? []
  const provider = providers.includes('deepseek-account')
    ? 'deepseek-account'
    : providers.find((id) => id !== 'deepseek-official') ?? providers[0]
  const model = catalog?.groups?.find((group) => group.id === provider)?.models?.[0]?.id
  if (!provider || !model) return
  await controller.selectModel({ sessionId, provider, model })
  info.__modelFor = sessionId
  info.modelSelected = `${provider}/${model}`
}

// 只给 cwd 建的会话会落到「未分组」；建一个专属命名工作区并把会话挂进去。
// ctx.get('workspaceRegistry') 可直接取，无需改 lib/index.js。
async function ensureWorkspace(ctx, info, sessionId) {
  if (info.__workspaceFor === sessionId) return
  try {
    const registry = ctx.get?.('workspaceRegistry')
    if (!registry) return
    let workspace = registry.list?.().find?.((item) => item.title === WORKSPACE_TITLE)
    if (!workspace) workspace = await registry.create(process.cwd(), WORKSPACE_TITLE)
    await workspace.attachSession(sessionId)
    info.__workspaceFor = sessionId
    info.workspaceTitle = WORKSPACE_TITLE
    info.workspaceAttached = Boolean(workspace.sessionIds?.includes?.(sessionId))
  } catch (error) {
    info.workspaceError = String(error?.message ?? error)
  }
}

function personaText(state) {
  return contextOf({ persona: state.persona, memory: [] })
}

function memoryText(state) {
  return contextOf({ persona: '', memory: state.memory })
}

function registerPromptSection(service, info) {
  if (info.__promptSectionVersion === SECTION_VERSION) return
  if (typeof service?.section !== 'function') throw new Error('systemPrompt.section unavailable')
  try { info.__promptSectionDispose?.() } catch { /* 旧注册已失效 */ }
  info.__promptSectionDispose = service.section({
    name: SECTION_NAME,
    order: SECTION_ORDER,
    interpolate: false,
    text: (assembly) => {
      const agent = assembly?.agent ?? assembly?.scope
      if (!info.__sessionId || ![agent?.id, agent?.session?.id].includes(info.__sessionId)) return ''
      return [personaText(readState()), info.__memorySnapshot ?? ''].filter(Boolean).join('\n\n')
    },
  })
  info.__promptSection = true
  info.__promptSectionVersion = SECTION_VERSION
  info.promptSection = 'system'
  delete info.promptSectionError
}

// 注册一次 section；引擎不支持时回退到「每轮拼进用户消息」，保证链路不回归。
// cordis 禁止直接属性取服务（"cannot get property without inject"）：先 ctx.get 直取，
// 拿不到再走官方 inject 回调（服务就绪时同步执行）。
function ensurePromptSection(ctx, info) {
  // 兼容改造前已注册的 section（只写了 __promptSection），避免热加载后重名重注册。
  if (info.__promptSectionVersion === undefined && info.__promptSection === true) info.__promptSectionVersion = SECTION_VERSION
  if (info.__promptSectionVersion === SECTION_VERSION) return true
  try {
    const service = typeof ctx?.get === 'function' ? ctx.get('systemPrompt') : null
    if (typeof service?.section === 'function') {
      registerPromptSection(service, info)
    } else if (info.__promptInjectedVersion !== SECTION_VERSION && typeof ctx?.inject === 'function') {
      info.__promptInjectedVersion = SECTION_VERSION
      ctx.inject(['systemPrompt'], (scoped) => {
        try { registerPromptSection(scoped.systemPrompt, info) } catch (error) {
          info.promptSection = 'fallback'
          info.promptSectionError = String(error?.message ?? error)
        }
      })
      if (info.__promptSectionVersion !== SECTION_VERSION) info.promptSection = 'pending'
    } else if (info.__promptSectionVersion !== SECTION_VERSION) {
      throw new Error('systemPrompt service unavailable')
    }
  } catch (error) {
    info.__promptSection = false
    info.promptSection = 'fallback'
    info.promptSectionError = String(error?.message ?? error)
  }
  return info.__promptSectionVersion === SECTION_VERSION
}

// 记忆是会话级快照：本进程每轮自动追加的两条已在会话历史里，不算变化；
// 外部编辑 / 新会话 / 重启才重新载入，避免 system prompt 每轮变化拖累前缀缓存。
function loadMemory(info, state, sessionId) {
  const own = info.__ownTimes ?? (info.__ownTimes = new Set())
  if (info.__memoryFor !== sessionId) {
    info.__memoryFor = sessionId
    info.__memorySignature = null
    own.clear()
  }
  const external = (Array.isArray(state.memory) ? state.memory : [])
    .filter((item) => !own.has(item?.time))
  const signature = JSON.stringify(external)
  if (signature === info.__memorySignature) return
  info.__memorySignature = signature
  info.__memorySnapshot = memoryText(state)
  info.memoryLoads = (info.memoryLoads ?? 0) + 1
}

export function createChat(ctx, info) {
  const sectionReady = ensurePromptSection(ctx, info)
  return async function* chat(text) {
    const rpcs = findRpc(ctx, info)
    if (!info.__sessionId) {
      const saved = readState().sessionId
      if (saved && typeof info.__agents?.get === 'function') {
        try { if (info.__agents.get(saved)) info.__sessionId = saved } catch { /* ignore */ }
      }
    }
    if (info.__sessionId && typeof info.__agents?.get === 'function') {
      try { if (!info.__agents.get(info.__sessionId)) info.__sessionId = null } catch { /* ignore */ }
    }
    if (!info.__sessionId) info.__sessionId = await createSession(ctx, info, rpcs)
    const sessionId = info.__sessionId
    await ensureModel(info, sessionId)
    await ensureWorkspace(ctx, info, sessionId)
    const before = sessionEventsOf(info, sessionId).length

    const chunks = []
    const eventTypes = []
    let settled = false
    let full = ''
    let errorMessage = ''
    let lastChunkAt = Date.now()
    const unsubscribe = info.__subscribe?.((session, event) => {
      if (sessionId && session?.id && session.id !== sessionId) return
      const type = String(event?.type ?? '?')
      eventTypes.push(type)
      const error = errorOfEvent(event)
      if (error) errorMessage = error
      const delta = mergeDelta(full, textOfEvent(event), type === 'assistant/message')
      if (delta) { chunks.push(delta); full += delta; lastChunkAt = Date.now() }
      if (/idle|finished|done|turn_end|completed|turn\/end/i.test(type)) settled = true
    })

    const state = readState()
    loadMemory(info, state, sessionId)
    const via = await sendPrompt(ctx, info, rpcs, sessionId, sectionReady ? text : promptOf(state, text))
    info.__lastVia = via

    const deadline = Date.now() + 60000
    const noEventDeadline = Date.now() + 12000
    let emitted = 0
    while (Date.now() < deadline) {
      while (emitted < chunks.length) yield chunks[emitted++]
      const quiet = chunks.length > 0 && emitted === chunks.length && Date.now() - lastChunkAt > 1500
      if ((settled && emitted === chunks.length) || quiet) break
      if (chunks.length === 0 && Date.now() > noEventDeadline) break
      await new Promise((resolve) => setTimeout(resolve, 120))
    }
    unsubscribe?.()
    while (emitted < chunks.length) yield chunks[emitted++]

    let events = []
    if (!full) {
      events = sessionEventsOf(info, sessionId).slice(before)
      const reply = replyFrom(events)
      if (reply) { yield reply; full = reply }
      else if (!errorMessage) errorMessage = events.map(errorOfEvent).find(Boolean) ?? ''
    }
    if (!full) {
      const detail = `via=${via}, sid=${sessionId}, create=${info.__createVia ?? 'none'}, snapshot=${events.length}, eventTypes=${JSON.stringify([...new Set(eventTypes)].slice(-30))}`
      if (errorMessage) throw new Error(`llm: ${errorMessage} (${detail})`)
      const samples = events
        .filter((event) => /assistant|attempt|turn\/end/i.test(String(event?.type ?? '')))
        .slice(-2)
        .map((event) => JSON.stringify(event).slice(0, 400))
      throw new Error(`no reply (${detail}, samples=${JSON.stringify(samples)})`)
    }
    const next = appendTurn(readState(), text, full)
    writeState({ ...next, sessionId })
    const own = info.__ownTimes ?? (info.__ownTimes = new Set())
    for (const item of next.memory.slice(-2)) own.add(item.time)
  }
}
