// mp3 → PCM：edge-tts 的产物是 mp3，而 xiaozhi 设备与 AudioTrack 都要 PCM（再编码成 Opus）。
// 用 mpg123-decoder（wasm，MIT）；懒加载，解码器单例复用（编解码状态在 decode 内部重置）。

let decoderPromise = null

async function decoder() {
  if (!decoderPromise) {
    decoderPromise = import('mpg123-decoder')
      .then(async (mod) => {
        const instance = new mod.MPEGDecoder()
        await instance.ready
        return instance
      })
      .catch((error) => {
        decoderPromise = null
        throw new Error(`mpg123-decoder 不可用：${error?.message ?? error}`)
      })
  }
  return decoderPromise
}

export async function mp3Available() {
  try { await decoder(); return true } catch { return false }
}

/**
 * 整段 mp3 → Int16Array（单声道）+ 采样率。
 * edge-tts 固定 audio-24khz-48kbitrate-mono-mp3，解码器可能回报 2 声道（两路内容相同），这里只取第 0 路。
 */
export async function decodeMp3(buffer) {
  const instance = await decoder()
  const { channelData, samplesDecoded, sampleRate, errors } = instance.decode(
    new Uint8Array(buffer.buffer, buffer.byteOffset, buffer.byteLength),
  )
  if (!channelData?.length || !samplesDecoded) {
    throw new Error(`mp3 解码失败${errors?.length ? `：${errors[0]}` : ''}`)
  }
  const source = channelData[0]
  const pcm = new Int16Array(samplesDecoded)
  for (let i = 0; i < samplesDecoded; i += 1) {
    const value = source[i] * 32767
    pcm[i] = value > 32767 ? 32767 : value < -32768 ? -32768 : Math.round(value)
  }
  return { pcm, sampleRate }
}
