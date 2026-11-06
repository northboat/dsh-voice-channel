// 角色定义与长期记忆：插件自管的本地状态，重启后仍在，每个会话都会载入。
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const DIR = path.join(HERE, '..', 'data')
const FILE = path.join(DIR, 'state.json')
export const MEMORY_PATH = path.join(DIR, 'memory.log')

export const DEFAULT_PERSONA = '你是 dsh-voice-channel 的桌宠助手。用中文简洁、友好地回答，不写废话。'
export const INJECT_MEMORY = 20
export const MAX_ROLES = 30
export const MAX_PERSONA = 8000
const DEFAULT_ROLE_NAME = '默认角色'

// 音色是服务端的唯一出口（client 只发文本），所以配置存在这里，管理页可改、重启后仍在。
export const DEFAULT_TTS = { voice: 'zh-CN-XiaoxiaoNeural', rate: 0, pitch: 0 }

export function randomPin() {
  return String(Math.floor(100000 + Math.random() * 900000))
}

export function validPin(value) {
  return /^\d{4,8}$/.test(String(value ?? ''))
}

/** Edge 音色名（ShortName），如 zh-CN-XiaoxiaoNeural / zh-CN-liaoning-XiaobeiNeural。 */
export function validVoice(value) {
  return /^[A-Za-z0-9._-]{3,64}$/.test(String(value ?? '').trim())
}

function clampInt(value, min, max, fallback) {
  const number = Number(value)
  if (!Number.isFinite(number)) return fallback
  return Math.max(min, Math.min(max, Math.round(number)))
}

/** tts 配置归一化：音色非法回默认，语速 -50..100 %、音调 -50..50 Hz。 */
export function normalizeTts(value) {
  const raw = value && typeof value === 'object' ? value : {}
  const voice = String(raw.voice ?? '').trim()
  return {
    voice: validVoice(voice) ? voice : DEFAULT_TTS.voice,
    rate: clampInt(raw.rate, -50, 100, DEFAULT_TTS.rate),
    pitch: clampInt(raw.pitch, -50, 50, DEFAULT_TTS.pitch),
  }
}

export function newRoleId() {
  return `r${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`
}

/** 角色列表归一化：补 id、去重复、截断超长字段；空列表回落到一个默认角色。 */
export function normalizeRoles(value, fallbackPersona) {
  const rows = Array.isArray(value) ? value.slice(0, MAX_ROLES) : []
  const seen = new Set()
  const roles = []
  for (const row of rows) {
    if (!row || typeof row !== 'object') continue
    let id = String(row.id ?? '').trim().slice(0, 64)
    if (!id || seen.has(id)) id = newRoleId()
    seen.add(id)
    roles.push({
      id,
      name: String(row.name ?? '').trim().slice(0, 40) || DEFAULT_ROLE_NAME,
      persona: String(row.persona ?? '').slice(0, MAX_PERSONA),
    })
  }
  if (!roles.length) {
    roles.push({ id: newRoleId(), name: DEFAULT_ROLE_NAME, persona: String(fallbackPersona ?? DEFAULT_PERSONA).slice(0, MAX_PERSONA) })
  }
  return roles
}

function activeOf(roles, wanted) {
  return roles.some((role) => role.id === wanted) ? String(wanted) : roles[0].id
}

function defaults() {
  const roles = normalizeRoles([], DEFAULT_PERSONA)
  return { roles, activeRoleId: roles[0].id, persona: DEFAULT_PERSONA, memory: [], sessionId: null, pin: randomPin(), tts: DEFAULT_TTS }
}

// memory.log 是 JSONL：每行 {time, role, text}，全量保留；注入 prompt 时只取最近 INJECT_MEMORY 条。
function readMemory() {
  try {
    return readFileSync(MEMORY_PATH, 'utf8')
      .split(/\r?\n/)
      .filter(Boolean)
      .map((line) => { try { return JSON.parse(line) } catch { return null } })
      .filter((item) => item && typeof item.text === 'string')
  } catch {
    return null
  }
}

function writeMemory(memory) {
  mkdirSync(DIR, { recursive: true })
  const lines = (Array.isArray(memory) ? memory : [])
    .filter((item) => item && typeof item.text === 'string')
    .map((item) => JSON.stringify({
      time: item.time ?? Date.now(),
      role: item.role === 'assistant' ? 'assistant' : 'user',
      text: String(item.text).slice(0, 500),
    }))
  writeFileSync(MEMORY_PATH, lines.length ? `${lines.join('\n')}\n` : '', 'utf8')
}

export function readState() {
  let raw = {}
  try { raw = JSON.parse(readFileSync(FILE, 'utf8')) } catch { return persistDefaults() }
  let memory = readMemory()
  const migrated = memory === null
  if (migrated) {
    // 旧版把 memory[] 存进 state.json；一次性迁到 memory.log。
    memory = Array.isArray(raw.memory) ? raw.memory : []
  }
  // 旧版只有一个 persona 字段：首次读到时把它迁成唯一角色。
  const legacyPersona = typeof raw.persona === 'string' ? raw.persona : DEFAULT_PERSONA
  const roles = normalizeRoles(raw.roles, legacyPersona)
  const activeRoleId = activeOf(roles, raw.activeRoleId)
  const state = {
    roles,
    activeRoleId,
    persona: roles.find((role) => role.id === activeRoleId).persona,
    memory,
    sessionId: typeof raw.sessionId === 'string' ? raw.sessionId : null,
    pin: validPin(raw.pin) ? String(raw.pin) : randomPin(),
    tts: normalizeTts(raw.tts),
  }
  // 首次运行生成 PIN / 清理旧 state.json 里的 memory 字段 / 迁移角色列表。
  if (migrated || !validPin(raw.pin) || Array.isArray(raw.memory) || !Array.isArray(raw.roles)) writeState(state)
  return state
}

function persistDefaults() {
  const state = defaults()
  writeState(state)
  return state
}

export function writeState(state) {
  mkdirSync(DIR, { recursive: true })
  // persona 始终是「当前角色」的文本，chat.js 只读它，不必知道角色的存在。
  const roles = normalizeRoles(state.roles, String(state.persona ?? DEFAULT_PERSONA))
  const activeRoleId = activeOf(roles, state.activeRoleId)
  writeFileSync(FILE, JSON.stringify({
    persona: roles.find((role) => role.id === activeRoleId).persona,
    roles,
    activeRoleId,
    sessionId: state.sessionId ?? null,
    pin: validPin(state.pin) ? String(state.pin) : randomPin(),
    tts: normalizeTts(state.tts),
  }, null, 2), 'utf8')
  writeMemory(state.memory)
}

/**
 * 管理页的局部写入：payload 里出现哪个字段就改哪个。
 * roles 整表替换（新增 / 编辑 / 删除都走它）；persona 改的是当前角色。
 */
export function patchState(current, payload) {
  const next = { ...current }
  if (Array.isArray(payload.roles)) {
    next.roles = normalizeRoles(payload.roles, current.persona)
    const wanted = typeof payload.activeRoleId === 'string' ? payload.activeRoleId : current.activeRoleId
    next.activeRoleId = activeOf(next.roles, wanted)
  } else if (typeof payload.activeRoleId === 'string' && current.roles.some((role) => role.id === payload.activeRoleId)) {
    next.activeRoleId = payload.activeRoleId
  }
  if (typeof payload.persona === 'string') {
    next.roles = next.roles.map((role) => (role.id === next.activeRoleId ? { ...role, persona: payload.persona } : role))
  }
  return next
}

export function contextOf(state) {
  const parts = []
  const persona = String(state.persona ?? '').trim()
  if (persona) parts.push(`[角色定义]\n${persona}`)
  const lines = (Array.isArray(state.memory) ? state.memory : [])
    .slice(-INJECT_MEMORY)
    .filter((item) => item && typeof item.text === 'string' && item.text.trim())
    .map((item) => `- ${item.role === 'assistant' ? '助手' : '用户'}：${item.text.trim().slice(0, 200)}`)
  if (lines.length) parts.push(`[长期记忆]\n${lines.join('\n')}`)
  return parts.join('\n\n')
}

export function promptOf(state, text) {
  const context = contextOf(state)
  return context ? `${context}\n\n[本轮消息]\n${text}` : text
}

export function appendTurn(state, userText, replyText) {
  const now = Date.now()
  const memory = [...(Array.isArray(state.memory) ? state.memory : []),
    { time: now, role: 'user', text: String(userText).slice(0, 500) },
    { time: now, role: 'assistant', text: String(replyText).slice(0, 500) },
  ]
  return { ...state, memory }
}
