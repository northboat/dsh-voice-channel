// 最小 RFC6455 服务端：只服务本项目 client（xiaozhi 设备 / Android / 调试脚本）。
// 支持文本帧、二进制帧、分片续帧、ping/pong、close；不协商 permessage-deflate，也没有子协议。
// 目的：插件继续零框架依赖，同时把「三条 HTTP」收敛成一条长连接（见 docs/ws-protocol.md）。

import crypto from 'node:crypto'
import { EventEmitter } from 'node:events'

const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'
const MAX_PAYLOAD = 4 * 1024 * 1024 // 单条消息上限，音频帧远小于它
const PING_MS = 20_000
const IDLE_MS = 90_000

const OP_CONT = 0x0
const OP_TEXT = 0x1
const OP_BINARY = 0x2
const OP_CLOSE = 0x8
const OP_PING = 0x9
const OP_PONG = 0xa

/** 一条已升级的连接。事件：`message`(data, isBinary)、`close`(code)、`error`。 */
export class WsConnection extends EventEmitter {
  constructor(socket, req) {
    super()
    this.socket = socket
    this.req = req
    this.closed = false
    this.buffer = Buffer.alloc(0)
    this.fragments = null
    this.fragmentOpcode = 0
    this.lastSeen = Date.now()
    this.remote = String(req?.socket?.remoteAddress ?? '')
    this.headers = req?.headers ?? {}
    this.bytesIn = 0
    this.bytesOut = 0
    this.messages = 0

    socket.setNoDelay(true)
    socket.setTimeout(0)
    socket.on('data', (chunk) => this.onData(chunk))
    socket.on('error', (error) => {
      this.closed = true
      this.emit('error', error)
    })
    socket.on('close', () => this.finish(1006))
    socket.on('end', () => this.finish(1006))
  }

  /** 握手后 socket 里可能已经带着首帧字节。 */
  feed(head) {
    if (head && head.length) this.onData(head)
  }

  onData(chunk) {
    if (this.closed) return
    this.lastSeen = Date.now()
    this.bytesIn += chunk.length
    this.buffer = this.buffer.length ? Buffer.concat([this.buffer, chunk]) : chunk
    for (;;) {
      if (this.buffer.length < 2) return
      const b0 = this.buffer[0]
      const b1 = this.buffer[1]
      const fin = (b0 & 0x80) !== 0
      const opcode = b0 & 0x0f
      const masked = (b1 & 0x80) !== 0
      let length = b1 & 0x7f
      let offset = 2
      if (length === 126) {
        if (this.buffer.length < 4) return
        length = this.buffer.readUInt16BE(2)
        offset = 4
      } else if (length === 127) {
        if (this.buffer.length < 10) return
        const big = this.buffer.readBigUInt64BE(2)
        if (big > BigInt(MAX_PAYLOAD)) { this.close(1009, 'frame too large'); return }
        length = Number(big)
        offset = 10
      }
      if (length > MAX_PAYLOAD) { this.close(1009, 'frame too large'); return }
      const maskLength = masked ? 4 : 0
      if (this.buffer.length < offset + maskLength + length) return
      let payload = this.buffer.subarray(offset + maskLength, offset + maskLength + length)
      if (masked) {
        const mask = this.buffer.subarray(offset, offset + 4)
        const copy = Buffer.allocUnsafe(length)
        for (let i = 0; i < length; i += 1) copy[i] = payload[i] ^ mask[i & 3]
        payload = copy
      }
      this.buffer = this.buffer.subarray(offset + maskLength + length)
      this.onFrame(fin, opcode, payload)
      if (this.closed) return
    }
  }

  onFrame(fin, opcode, payload) {
    if (opcode === OP_CLOSE) {
      const code = payload.length >= 2 ? payload.readUInt16BE(0) : 1005
      this.sendFrame(OP_CLOSE, payload.subarray(0, 2))
      this.finish(code)
      return
    }
    if (opcode === OP_PING) { this.sendFrame(OP_PONG, payload); return }
    if (opcode === OP_PONG) return

    if (opcode === OP_CONT && this.fragments) {
      this.fragments.push(payload)
      if (fin) {
        const data = Buffer.concat(this.fragments)
        const type = this.fragmentOpcode
        this.fragments = null
        this.dispatch(type, data)
      }
      return
    }
    if (!fin) {
      this.fragments = [payload]
      this.fragmentOpcode = opcode
      return
    }
    this.dispatch(opcode, payload)
  }

  dispatch(opcode, data) {
    if (opcode !== OP_TEXT && opcode !== OP_BINARY) return
    this.messages += 1
    this.emit('message', opcode === OP_BINARY ? data : data.toString('utf8'), opcode === OP_BINARY)
  }

  sendFrame(opcode, payload = Buffer.alloc(0)) {
    if (this.closed || this.socket.destroyed) return false
    const length = payload.length
    let header
    if (length < 126) {
      header = Buffer.allocUnsafe(2)
      header[1] = length
    } else if (length < 65536) {
      header = Buffer.allocUnsafe(4)
      header[1] = 126
      header.writeUInt16BE(length, 2)
    } else {
      header = Buffer.allocUnsafe(10)
      header[1] = 127
      header.writeBigUInt64BE(BigInt(length), 2)
    }
    header[0] = 0x80 | opcode
    try {
      this.socket.write(header)
      if (length) this.socket.write(payload)
      this.bytesOut += length
      return true
    } catch {
      this.finish(1006)
      return false
    }
  }

  /** 文本帧；对象会被 JSON 序列化。 */
  send(value) {
    const text = typeof value === 'string' ? value : JSON.stringify(value)
    return this.sendFrame(OP_TEXT, Buffer.from(text, 'utf8'))
  }

  sendBinary(payload) {
    return this.sendFrame(OP_BINARY, Buffer.isBuffer(payload) ? payload : Buffer.from(payload))
  }

  ping() {
    this.sendFrame(OP_PING)
  }

  close(code = 1000, reason = '') {
    if (this.closed) return
    const body = Buffer.alloc(2 + Buffer.byteLength(reason))
    body.writeUInt16BE(code, 0)
    if (reason) body.write(reason, 2, 'utf8')
    this.sendFrame(OP_CLOSE, body)
    this.finish(code)
  }

  finish(code) {
    if (this.closed) return
    this.closed = true
    try { this.socket.end() } catch { /* 已经断了 */ }
    setTimeout(() => { try { this.socket.destroy() } catch { /* ignore */ } }, 300).unref?.()
    this.emit('close', code)
  }
}

/**
 * 挂到 http server 的 `upgrade` 上。
 * options.paths：接受的 pathname 前缀；options.authorize(req, url) 返回 false 会直接 401。
 */
export function attachWebSocket(server, options) {
  const paths = (options.paths ?? ['/ws']).map((item) => item.replace(/\/$/, ''))
  const connections = new Set()
  let total = 0

  const reject = (socket, code, text) => {
    try {
      socket.write(`HTTP/1.1 ${code} ${text}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`)
      socket.destroy()
    } catch { /* ignore */ }
  }

  const onUpgrade = (req, socket, head) => {
    let url
    try { url = new URL(req.url || '/', 'http://localhost') } catch { reject(socket, 400, 'Bad Request'); return }
    const pathname = url.pathname.replace(/\/$/, '')
    if (!paths.some((prefix) => pathname === prefix || pathname.startsWith(`${prefix}/`))) {
      reject(socket, 404, 'Not Found')
      return
    }
    const key = req.headers['sec-websocket-key']
    if (String(req.headers.upgrade ?? '').toLowerCase() !== 'websocket' || !key) {
      reject(socket, 400, 'Bad Request')
      return
    }
    if (options.authorize && !options.authorize(req, url)) {
      reject(socket, 401, 'Unauthorized')
      return
    }
    const accept = crypto.createHash('sha1').update(key + GUID).digest('base64')
    socket.write(
      'HTTP/1.1 101 Switching Protocols\r\n' +
      'Upgrade: websocket\r\n' +
      'Connection: Upgrade\r\n' +
      `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
    )
    const conn = new WsConnection(socket, req)
    connections.add(conn)
    total += 1
    conn.on('close', () => connections.delete(conn))
    conn.on('error', () => connections.delete(conn))
    try {
      options.onConnection(conn, req, url)
    } catch (error) {
      conn.close(1011, 'handler failed')
      console.error('[dsh-voice-channel][ws] connection handler failed:', error?.message ?? error)
    }
    conn.feed(head)
  }

  server.on('upgrade', onUpgrade)

  const timer = setInterval(() => {
    const now = Date.now()
    for (const conn of connections) {
      if (now - conn.lastSeen > IDLE_MS) { conn.close(1001, 'idle'); continue }
      conn.ping()
    }
  }, PING_MS)
  timer.unref?.()

  return {
    connections,
    stats: () => ({ live: connections.size, total }),
    close() {
      clearInterval(timer)
      server.off('upgrade', onUpgrade)
      for (const conn of [...connections]) conn.close(1001, 'server shutdown')
    },
  }
}
