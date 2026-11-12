// 回复文本处理：情绪判定、朗读前的清洗、流式断句。
// 断句是流式 TTS 的关键——一句话合成完就下行，不必等整段回复。

// 一句合成一次 edge-tts（在线，实测单次 2~17 s）。太碎的句子（「好的。」）会让请求数翻倍、
// 播放追不上合成，所以把短句并到下一段一起合成；字幕仍按播放头逐字走，不受影响。
const MIN_SENTENCE = 16

/** 规则判定情绪；未知回退 neutral（与 docs 的情绪契约一致）。 */
export function detectEmotion(text) {
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

/**
 * 朗读前洗成纯正文：换行会被念成长停顿，Markdown 符号与 emoji 会被念成奇怪的词。
 * （原来在 Android 侧做，TTS 上移到插件后跟着搬过来，ESP32 才有同样的听感。）
 */
export function cleanForSpeech(text) {
  return String(text ?? '')
    .replace(/```[\s\S]*?```/g, '，代码略，')
    .replace(/`+/g, '')
    .replace(/[*_#>|~[\]]+/g, '')
    .replace(/[\uD800-\uDFFF]/g, '')
    .replace(/\uFE0F/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}

const BOUNDARY = /[。！？!?；;…\n]/

/** 增量断句：push 任意文本块，返回这次可以合成的完整句子。 */
export class SentenceSplitter {
  constructor({ minLength = MIN_SENTENCE } = {}) {
    this.minLength = minLength
    this.buffer = ''
  }

  push(chunk) {
    this.buffer += String(chunk ?? '')
    const out = []
    let from = 0
    for (;;) {
      const index = this.buffer.slice(from).search(BOUNDARY)
      if (index < 0) break
      const end = from + index + 1
      const clean = cleanForSpeech(this.buffer.slice(0, end))
      // 太短的片段（「好的。」）先攒着，避免一句一个音频包。
      if (clean.length >= this.minLength) {
        out.push(clean)
        this.buffer = this.buffer.slice(end)
        from = 0
      } else {
        from = end
      }
    }
    return out
  }

  /** 收尾：把剩下的半句也交出去。 */
  flush() {
    const rest = cleanForSpeech(this.buffer)
    this.buffer = ''
    return rest ? [rest] : []
  }
}
