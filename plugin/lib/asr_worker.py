"""常驻 ASR worker：stdin 读长度前缀的 PCM，stdout 写 JSON 行。

协议（小端）：
  request  = <uint32 id><uint32 nbytes><pcm s16le 16 kHz mono>
  response = {"id":..,"ok":true,"text":"..","ms":123}

模型只在启动时加载一次（PC 侧，约 300 MB 内存）；每条请求用独立的
KaldiRecognizer，避免上一句的上下文串到下一句。
"""

import json
import os
import struct
import sys
import time

SAMPLE_RATE = 16000
CHUNK = 3200  # 100 ms，偶数长度，满足 s16 对齐


def is_cjk(ch):
    return "\u4e00" <= ch <= "\u9fff" or "\u3400" <= ch <= "\u4dbf"


def normalize(text):
    """Vosk 中文输出带词间空格（"今 天 天 气"），拼回可读文本，保留英文词间空格。"""
    out = []
    pending = False
    for ch in text.strip():
        if ch == " ":
            pending = True
            continue
        if pending and out and not (is_cjk(out[-1]) and is_cjk(ch)):
            out.append(" ")
        pending = False
        out.append(ch)
    return "".join(out)


def read_exact(stream, size):
    buf = b""
    while len(buf) < size:
        chunk = stream.read(size - len(buf))
        if not chunk:
            return None
        buf += chunk
    return buf


def main():
    model_path = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("DSH_ASR_MODEL", "")
    if not model_path or not os.path.isdir(model_path):
        print(json.dumps({"ready": False, "error": "model not found: %s" % model_path}), flush=True)
        return 2

    from vosk import Model, KaldiRecognizer, SetLogLevel

    SetLogLevel(-1)
    started = time.time()
    model = Model(model_path)
    print(
        json.dumps(
            {"ready": True, "model": model_path, "loadMs": int((time.time() - started) * 1000)}
        ),
        flush=True,
    )

    stdin = sys.stdin.buffer
    stdout = sys.stdout.buffer

    # 复用同一个 recognizer：构造约 150 ms，且首次解码要额外热一次（约 1 s）。
    # 预热后单条解码约 30–60 ms/s 音频，远快于实时。
    recognizer = KaldiRecognizer(model, SAMPLE_RATE)
    for _ in range(3):
        recognizer.AcceptWaveform(bytes(SAMPLE_RATE))
    recognizer.FinalResult()

    while True:
        head = read_exact(stdin, 8)
        if head is None:
            break
        req_id, size = struct.unpack("<II", head)
        if size % 2:
            size -= 1
        pcm = read_exact(stdin, size) if size else b""
        if pcm is None:
            break

        began = time.time()
        try:
            recognizer.Reset()
        except Exception:
            recognizer = KaldiRecognizer(model, SAMPLE_RATE)
        text = ""
        if pcm:
            for offset in range(0, len(pcm), CHUNK):
                recognizer.AcceptWaveform(pcm[offset : offset + CHUNK])
            final = json.loads(recognizer.FinalResult() or "{}")
            text = normalize(final.get("text", ""))
        payload = {
            "id": req_id,
            "ok": True,
            "text": text,
            "ms": int((time.time() - began) * 1000),
        }
        stdout.write((json.dumps(payload, ensure_ascii=False) + "\n").encode("utf-8"))
        stdout.flush()

    return 0


if __name__ == "__main__":
    sys.exit(main())
