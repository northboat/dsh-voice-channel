"""常驻 TTS worker：stdin 读长度前缀 JSON，stdout 写 JSON 行头 + 音频字节。

协议（小端）：
  request  = <uint32 nbytes><json utf-8>
             {"id":1,"op":"speak","text":"你好","voice":"zh-CN-XiaoxiaoNeural","rate":"+0%","pitch":"+0Hz","volume":"+0%"}
             {"id":2,"op":"voices"}
  response = {"id":1,"ok":true,"bytes":31248,"ms":1670,"codec":"audio/mpeg"}\n<mp3 bytes>
           | {"id":2,"ok":true,"voices":[{"name":..,"gender":..,"locale":..,"tags":{..}}]}\n
           | {"id":1,"ok":false,"error":"..."}\n

引擎是 Edge 在线神经语音（edge-tts，零本地模型、零显存，代价是必须联网）。
要换成本地引擎（piper / sherpa-onnx / CosyVoice）只需保持同一协议，HTTP 层不用改。
"""

import asyncio
import json
import struct
import sys
import time

STDIN = sys.stdin.buffer
STDOUT = sys.stdout.buffer
DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"
MAX_TEXT = 2000

write_lock = asyncio.Lock()


def read_exact(stream, size):
    buf = b""
    while len(buf) < size:
        chunk = stream.read(size - len(buf))
        if not chunk:
            return None
        buf += chunk
    return buf


async def send(payload, audio=None):
    head = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    async with write_lock:
        STDOUT.write(head + b"\n")
        if audio:
            STDOUT.write(audio)
        STDOUT.flush()


async def synthesize(text, voice, req):
    import edge_tts

    communicate = edge_tts.Communicate(
        text,
        voice,
        rate=req.get("rate") or "+0%",
        pitch=req.get("pitch") or "+0Hz",
        volume=req.get("volume") or "+0%",
    )
    chunks = []
    async for chunk in communicate.stream():
        if chunk["type"] == "audio":
            chunks.append(chunk["data"])
    return b"".join(chunks)


async def retry_once(factory):
    """在线服务偶发断连 / 握手失败：只对网络类异常重试一次，音色非法不重试。"""
    try:
        return await factory()
    except Exception as error:
        if type(error).__name__ in ("NoAudioReceived", "ValueError", "KeyError"):
            raise
        await asyncio.sleep(0.4)
        return await factory()


async def handle(req):
    rid = req.get("id", 0)
    began = time.perf_counter()
    try:
        if req.get("op") == "voices":
            import edge_tts

            voices = await retry_once(edge_tts.list_voices)
            rows = [
                {
                    "name": voice.get("ShortName"),
                    "gender": voice.get("Gender"),
                    "locale": voice.get("Locale"),
                    "tags": voice.get("VoiceTag") or {},
                }
                for voice in voices
            ]
            await send({"id": rid, "ok": True, "voices": rows, "ms": int((time.perf_counter() - began) * 1000)})
            return
        text = str(req.get("text") or "").strip()
        if not text:
            raise ValueError("text 为空")
        if len(text) > MAX_TEXT:
            raise ValueError("text 超过 %d 字" % MAX_TEXT)
        audio = await retry_once(lambda: synthesize(text, str(req.get("voice") or DEFAULT_VOICE), req))
        if not audio:
            raise ValueError("edge 未返回音频（音色或网络异常）")
        await send(
            {
                "id": rid,
                "ok": True,
                "bytes": len(audio),
                "ms": int((time.perf_counter() - began) * 1000),
                "codec": "audio/mpeg",
            },
            audio,
        )
    except Exception as error:  # 单条失败不拖垮常驻进程
        await send({"id": rid, "ok": False, "error": "%s: %s" % (type(error).__name__, error)})


async def main():
    loop = asyncio.get_running_loop()
    try:
        import edge_tts  # noqa: F401  启动即预热 import，避免首条请求多等 0.4 s
        print(json.dumps({"ready": True}), flush=True)
    except Exception as error:
        print(json.dumps({"ready": False, "error": "edge-tts 未安装：%s" % error}), flush=True)
        return 2

    tasks = set()
    while True:
        head = await loop.run_in_executor(None, read_exact, STDIN, 4)
        if head is None or len(head) < 4:
            break
        (size,) = struct.unpack("<I", head)
        body = await loop.run_in_executor(None, read_exact, STDIN, size) if size else b""
        if body is None:
            break
        try:
            req = json.loads(body.decode("utf-8") or "{}")
        except Exception as error:
            await send({"id": 0, "ok": False, "error": "bad json: %s" % error})
            continue
        task = loop.create_task(handle(req))
        tasks.add(task)
        task.add_done_callback(tasks.discard)
    if tasks:
        await asyncio.gather(*tasks, return_exceptions=True)
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
