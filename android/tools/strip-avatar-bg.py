"""把形象素材的卡片底板抠成透明，只留角色 / 文字 / 图标。

用法（需要 Pillow，建议放临时 venv）：
    python -m venv .venv-tools && .venv-tools/Scripts/pip install pillow
    .venv-tools/Scripts/python android/tools/strip-avatar-bg.py <素材目录> <输出目录>

默认处理 deepy 的四个动画（notification / happy / sleeping / typing），
输出同名 .webp（带 alpha 的动画 WebP，Android 的 ImageDecoder 能直接播）。
卡片颜色（#0D1117 外圈、#181D29 内底、#171C28 描边）是按 dsh-Go 桌宠素材量出来的；
换别的素材先改 CARD_COLORS 再跑。
"""
import sys
from pathlib import Path
from PIL import Image, ImageSequence

CARD_COLORS = [(13, 17, 23), (24, 29, 41), (23, 28, 40), (14, 18, 24), (24, 29, 40)]
THRESHOLD_SQ = 30 * 30
NAMES = ['notification', 'happy', 'sleeping', 'typing']


def dist_sq(a, b):
    return (a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2 + (a[2] - b[2]) ** 2


def is_card_pixel(px):
    return any(dist_sq(px, c) <= THRESHOLD_SQ for c in CARD_COLORS)


def strip(path: Path, out: Path):
    im = Image.open(path)
    frames, durations = [], []
    cleared = total = 0
    for frame in ImageSequence.Iterator(im):
        durations.append(max(40, int(frame.info.get('duration', 80))))
        rgba = frame.convert('RGBA')
        data = []
        for px in rgba.getdata():
            if is_card_pixel(px):
                data.append((0, 0, 0, 0))
                cleared += 1
            else:
                data.append(px)
            total += 1
        rgba.putdata(data)
        frames.append(rgba)
    frames[0].save(
        out,
        save_all=True,
        append_images=frames[1:],
        duration=durations,
        loop=0,
        format='WEBP',
        lossless=True,
        quality=100,
        method=6,
    )
    print(f'{path.name} -> {out.name}: frames={len(frames)} cleared={cleared * 100.0 / max(total, 1):.1f}% size={out.stat().st_size}')


def source_of(src: Path, name: str) -> Path:
    """兼容两种来源：dsh-Go 的 pet-deepy-<name>-zh-dark.gif 与自备的 <name>.gif。"""
    for candidate in (f'pet-deepy-{name}-zh-dark.gif', f'{name}.gif'):
        path = src / candidate
        if path.exists():
            return path
    raise FileNotFoundError(f'{src} 下找不到 {name}.gif（或 pet-deepy-{name}-zh-dark.gif）')


if __name__ == '__main__':
    src = Path(sys.argv[1])
    out_dir = Path(sys.argv[2])
    out_dir.mkdir(parents=True, exist_ok=True)
    names = sys.argv[3:] or NAMES
    for name in names:
        strip(source_of(src, name), out_dir / f'{name}.webp')
