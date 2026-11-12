// Opus 编解码：xiaozhi 设备的上下行都是裸 Opus 帧，Node 侧没有内置实现，
// 用 opusscript（纯 JS，MIT，无 native 依赖）。懒加载：没装也不影响 HTTP 路由。
//
// 约定：上行 16 kHz（Vosk 与 xiaozhi 设备默认），下行 24 kHz（edge-tts 的 mp3 就是 24 kHz，不用重采样）。

let modulePromise = null

async function loadOpusScript() {
  if (!modulePromise) {
    modulePromise = import('opusscript')
      .then((mod) => mod.default ?? mod)
      .catch((error) => {
        modulePromise = null
        throw new Error(`opusscript 不可用：${error?.message ?? error}`)
      })
  }
  return modulePromise
}

export async function opusAvailable() {
  try { await loadOpusScript(); return true } catch { return false }
}

/**
 * 一个采样率的编解码器。
 * encode(Int16Array) → Buffer（一帧）
 * decode(Buffer, frameSamples) → Int16Array
 */
export async function createOpusCodec(sampleRate, channels = 1) {
  const OpusScript = await loadOpusScript()
  const application = OpusScript.Application?.AUDIO ?? 2049
  const encoder = new OpusScript(sampleRate, channels, application)
  const decoder = new OpusScript(sampleRate, channels, application)
  return {
    sampleRate,
    channels,
    encode(pcm16) {
      const frame = pcm16.length / channels
      return Buffer.from(encoder.encode(Buffer.from(pcm16.buffer, pcm16.byteOffset, pcm16.byteLength), frame))
    },
    decode(packet, frameSamples) {
      const out = decoder.decode(packet, frameSamples)
      return toInt16(Buffer.isBuffer(out) ? out : Buffer.from(out))
    },
    close() {
      try { encoder.delete?.() } catch { /* ignore */ }
      try { decoder.delete?.() } catch { /* ignore */ }
    },
  }
}

/** s16le Buffer → Int16Array（复制，避免对齐问题）。 */
export function toInt16(buffer) {
  const length = Math.floor(buffer.length / 2)
  const view = new Int16Array(length)
  for (let i = 0; i < length; i += 1) view[i] = buffer.readInt16LE(i * 2)
  return view
}

/** Int16Array → s16le Buffer。 */
export function toBuffer(samples) {
  const out = Buffer.allocUnsafe(samples.length * 2)
  for (let i = 0; i < samples.length; i += 1) out.writeInt16LE(samples[i], i * 2)
  return out
}
