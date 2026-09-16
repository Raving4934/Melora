#!/usr/bin/env python3
"""用 Python 标准库生成固定尺寸 PNG；图形源为蓝底白色均衡器声柱。"""
import argparse
from pathlib import Path
import struct
import zlib


BLUE = (53, 103, 232, 255)
WHITE = (255, 255, 255, 255)
TRANSPARENT = (0, 0, 0, 0)
SAMPLES = 4
BACKGROUND = (0, 0, 64, 64, 17)
BARS = ((15, 28, 36), (24, 20, 44), (33, 14, 50), (42, 21, 43), (51, 28, 36))


def rounded(x, y, x0, y0, x1, y1, radius):
    cx = min(max(x, x0 + radius), x1 - radius)
    cy = min(max(y, y0 + radius), y1 - radius)
    return (x - cx) ** 2 + (y - cy) ** 2 <= radius**2


def sample(px, py):
    if not rounded(px, py, *BACKGROUND):
        return TRANSPARENT
    for center, start, end in BARS:
        # 端点是线段中心点；圆头因此在两端各外扩 2.5 个坐标单位。
        if rounded(px, py, center - 2.5, start - 2.5, center + 2.5, end + 2.5, 2.5):
            return WHITE
    return BLUE


def pixel(samples):
    """合并超采样点，保持透明边缘的 RGB 不被透明黑色污染。"""
    alpha = sum(color[3] for color in samples)
    if not alpha:
        return TRANSPARENT
    rgb = tuple(round(sum(color[channel] * color[3] for color in samples) / alpha)
                for channel in range(3))
    return (*rgb, round(alpha / len(samples)))


def render(size):
    """4 倍超采样；坐标统一使用 64px 画布，无字体、网络或图形库依赖。"""
    pixels = bytearray()
    for y in range(size):
        pixels.append(0)
        for x in range(size):
            samples = [sample((x + (sx + 0.5) / SAMPLES) * 64 / size,
                              (y + (sy + 0.5) / SAMPLES) * 64 / size)
                       for sy in range(SAMPLES) for sx in range(SAMPLES)]
            pixels.extend(pixel(samples))

    def chunk(kind, data):
        return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data))

    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!2I5B', size, size, 8, 6, 0, 0, 0))
            + chunk(b'sRGB', b'\0') + chunk(b'IDAT', zlib.compress(bytes(pixels), 9)) + chunk(b'IEND', b''))


def generate(root):
    for size, name in [(64, 'ICON.PNG'), (256, 'ICON_256.PNG')]:
        data = render(size)
        (root / name).write_bytes(data)
        images = root / 'app/ui/images'
        images.mkdir(parents=True, exist_ok=True)
        (images / f'icon_{size}.png').write_bytes(data)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', nargs='?', type=Path, default=Path(__file__).resolve().parents[1] / 'fpk')
    generate(parser.parse_args().root)
