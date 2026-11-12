// 极简 WS 客户端（验收用）：握手 + masked 帧发送 + 服务端帧解析。
import net from 'node:net'
import crypto from 'node:crypto'

const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'

export class TestClient {
  constructor(socket) {
    this.socket = socket
    this.buffer = Buffer.alloc(0)
    this.inbox = []
    this.waiter = null
    this.closed = false
    socket.on('data', (chunk) => this.onData(chunk))
    socket.on('close', () => { this.closed = true; this.wake() })
    socket.on('error', () => { this.closed = true; this.wake() })
  }

  static open(url, headers = {}) {
    const target = new URL(url)
    const key = crypto.randomBytes(16).toString('base64')
    const lines = [
      `GET ${target.pathname}${target.search || ''} HTTP/1.1`,
      `Host: ${target.host}`,
      'Upgrade: websocket',
      'Connection: Upgrade',
      `Sec-WebSocket-Key: ${key}`,
      'Sec-WebSocket-Version: 13',
      ...Object.entries(headers).map(([k, v]) => `${k}: ${v}`),
      '', '',
    ].join('\r\n')
    return new Promise((resolve, reject) => {
      const socket = net.connect(Number(target.port || 80), target.hostname)
      let raw = Buffer.alloc(0)
      const onData = (chunk) => {
        raw = Buffer.concat([raw, chunk])
        const end = raw.indexOf('\r\n\r\n')
        if (end < 0) return
        socket.off('data', onData)
        const head = raw.subarray(0, end).toString('latin1')
        if (!/101/.test(head.split('\r\n')[0])) {
          socket.destroy()
          reject(new Error(`handshake failed: ${head.split('\r\n')[0]}`))
          return
        }
        const accept = crypto.createHash('sha1').update(key + GUID).digest('base64')
        if (!head.includes(accept)) {
          socket.destroy()
          reject(new Error('bad Sec-WebSocket-Accept'))
          return
        }
        const client = new TestClient(socket)
        const rest = raw.subarray(end + 4)
        if (rest.length) client.onData(rest)
        resolve(client)
      }
      socket.on('data', onData)
      socket.on('error', reject)
      socket.write(lines)
    })
  }

  wake() {
    const waiter = this.waiter
    this.waiter = null
    waiter?.()
  }

  onData(chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk])
    for (;;) {
      if (this.buffer.length < 2) return
      const b0 = this.buffer[0]
      const b1 = this.buffer[1]
      const opcode = b0 & 0x0f
      let length = b1 & 0x7f
      let offset = 2
      if (length === 126) {
        if (this.buffer.length < 4) return
        length = this.buffer.readUInt16BE(2)
        offset = 4
      } else if (length === 127) {
        if (this.buffer.length < 10) return
        length = Number(this.buffer.readBigUInt64BE(2))
        offset = 10
      }
      if (this.buffer.length < offset + length) return
      const payload = this.buffer.subarray(offset, offset + length)
      this.buffer = this.buffer.subarray(offset + length)
      if (opcode === 0x8) { this.closed = true; this.wake(); continue }
      if (opcode === 0x9) { this.frame(0xa, payload); continue }
      if (opcode === 0x1) this.inbox.push({ text: payload.toString('utf8'), at: Date.now() })
      else if (opcode === 0x2) this.inbox.push({ binary: Buffer.from(payload), at: Date.now() })
    }
  }

  frame(opcode, payload = Buffer.alloc(0)) {
    const mask = crypto.randomBytes(4)
    const length = payload.length
    let header
    if (length < 126) { header = Buffer.allocUnsafe(2); header[1] = 0x80 | length }
    else if (length < 65536) { header = Buffer.allocUnsafe(4); header[1] = 0x80 | 126; header.writeUInt16BE(length, 2) }
    else { header = Buffer.allocUnsafe(10); header[1] = 0x80 | 127; header.writeBigUInt64BE(BigInt(length), 2) }
    header[0] = 0x80 | opcode
    const masked = Buffer.allocUnsafe(length)
    for (let i = 0; i < length; i += 1) masked[i] = payload[i] ^ mask[i & 3]
    this.socket.write(Buffer.concat([header, mask, masked]))
  }

  sendJson(value) {
    this.frame(0x1, Buffer.from(JSON.stringify(value), 'utf8'))
  }

  sendBinary(buffer) {
    this.frame(0x2, buffer)
  }

  close() {
    try { this.frame(0x8, Buffer.from([0x03, 0xe8])) } catch { /* ignore */ }
    this.socket.destroy()
  }

  /** 等下一帧；超时抛错。 */
  async next(timeoutMs = 5000) {
    const deadline = Date.now() + timeoutMs
    for (;;) {
      if (this.inbox.length) return this.inbox.shift()
      if (this.closed) throw new Error('connection closed')
      const left = deadline - Date.now()
      if (left <= 0) throw new Error(`timeout waiting frame (${timeoutMs}ms)`)
      await new Promise((resolve) => {
        this.waiter = resolve
        setTimeout(resolve, Math.min(left, 50))
      })
      if (Date.now() > deadline) throw new Error(`timeout waiting frame (${timeoutMs}ms)`)
    }
  }

  /** 收集直到 predicate 命中（含该帧），返回收到的所有帧。 */
  async until(predicate, timeoutMs = 8000) {
    const frames = []
    const deadline = Date.now() + timeoutMs
    for (;;) {
      const frame = await this.next(Math.max(1, deadline - Date.now()))
      frames.push(frame)
      if (frame.text && predicate(frame.text)) return frames
    }
  }
}
