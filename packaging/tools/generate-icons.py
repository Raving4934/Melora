#!/usr/bin/env python3
"""从正式 Web 图标源生成 fnOS 所需的固定尺寸 RGBA PNG。

唯一视觉源是 ``apps/web/src/assets/brand/app-icon.svg``。生成器直接读取 SVG 的
有效几何与颜色，避免 fnOS 图标继续维护另一套过时品牌常量。
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import xml.etree.ElementTree as ET
import zlib

REPO_ROOT = Path(__file__).resolve().parents[2]
FORMAL_ICON = REPO_ROOT / 'apps/web/src/assets/brand/app-icon.svg'

BLUE = (91, 137, 250, 255)
WHITE = (255, 255, 255, 255)
TRANSPARENT = (0, 0, 0, 0)
SAMPLES = 4
CANVAS = 64.0

_NUMBER = re.compile(r'-?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?')
_TOKEN = re.compile(r'[A-Za-z]|-?\d*\.?\d+(?:e-?\d+)?')
_RGB = re.compile(r'rgb\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)', re.IGNORECASE)


def _local_name(tag):
    return tag.rsplit('}', 1)[-1]


def _style_properties(element):
    properties = {}
    for item in element.attrib.get('style', '').split(';'):
        if ':' not in item:
            continue
        key, value = item.split(':', 1)
        properties[key.strip()] = value.strip()
    return properties


def _presentation_value(element, name):
    """读取 SVG 的有效 presentation value；inline style 优先于属性。"""
    return _style_properties(element).get(name, element.attrib.get(name))


def _parse_color(value):
    if value is None:
        raise ValueError('正式图标元素缺少 fill。')
    value = value.strip().lower()
    if value == 'white':
        return (255, 255, 255, 255)
    if value.startswith('#'):
        hex_value = value[1:]
        if len(hex_value) == 3:
            hex_value = ''.join(char * 2 for char in hex_value)
        if len(hex_value) != 6 or not re.fullmatch(r'[0-9a-f]{6}', hex_value):
            raise ValueError(f'不支持的 SVG 颜色：{value}')
        return (*bytes.fromhex(hex_value), 255)
    match = _RGB.fullmatch(value)
    if match:
        return (*map(int, match.groups()), 255)
    raise ValueError(f'不支持的 SVG 颜色：{value}')


def _parse_numbers(value, count, label):
    values = [float(item) for item in _NUMBER.findall(value or '')]
    if len(values) != count:
        raise ValueError(f'{label} 数量异常：{value}')
    return values


def _load_formal_icon(path=FORMAL_ICON):
    """从正式 SVG 读取几何与有效颜色，不复制第二套品牌图形常量。"""
    root = ET.parse(path).getroot()
    view_x, view_y, view_width, view_height = _parse_numbers(
        root.attrib.get('viewBox'), 4, 'viewBox')
    circles = [element for element in root.iter() if _local_name(element.tag) == 'circle']
    paths = [element for element in root.iter()
             if _local_name(element.tag) == 'path' and element.attrib.get('d')]
    if len(circles) != 1 or len(paths) != 2:
        raise ValueError('正式图标必须包含 1 个圆形底板和 2 个白色路径。')

    circle = circles[0]
    circle_color = _parse_color(_presentation_value(circle, 'fill'))
    path_colors = [_parse_color(_presentation_value(element, 'fill')) for element in paths]
    if circle_color != BLUE:
        raise ValueError(f'正式图标有效底色不是项目蓝色：{circle_color}')
    if path_colors != [WHITE, WHITE]:
        raise ValueError(f'正式图标前景不是白色：{path_colors}')

    return {
        'view': (view_x, view_y, view_width, view_height),
        'circle': tuple(_parse_numbers(circle.attrib.get(name), 1, f'circle.{name}')[0]
                        for name in ('cx', 'cy', 'r')),
        'paths': tuple(element.attrib['d'] for element in paths),
    }


_FORMAL_ICON = _load_formal_icon()
VIEW_X, VIEW_Y, VIEW_WIDTH, VIEW_HEIGHT = _FORMAL_ICON['view']
SCALE_X = CANVAS / VIEW_WIDTH
SCALE_Y = CANVAS / VIEW_HEIGHT
CIRCLE_CENTER_X, CIRCLE_CENTER_Y, CIRCLE_RADIUS = _FORMAL_ICON['circle']
WAVE, PLAY = _FORMAL_ICON['paths']


def flatten(path, steps=64):
    """展平正式 SVG 路径为多边形；当前品牌路径只使用 m/l/v/c/z。"""
    tokens = _TOKEN.findall(path)
    index = 0
    command = None
    x = y = 0.0
    start_x = start_y = 0.0
    points = []

    while index < len(tokens):
        if tokens[index].isalpha():
            command = tokens[index]
            index += 1
        relative = command.islower()
        kind = command.upper()

        if kind == 'M':
            nx, ny = float(tokens[index]), float(tokens[index + 1]); index += 2
            if relative:
                nx += x; ny += y
            x, y = nx, ny
            start_x, start_y = x, y
            points.append((x, y))
            command = 'l' if relative else 'L'
        elif kind == 'L':
            nx, ny = float(tokens[index]), float(tokens[index + 1]); index += 2
            if relative:
                nx += x; ny += y
            x, y = nx, ny
            points.append((x, y))
        elif kind == 'V':
            ny = float(tokens[index]); index += 1
            if relative:
                ny += y
            y = ny
            points.append((x, y))
        elif kind == 'H':
            nx = float(tokens[index]); index += 1
            if relative:
                nx += x
            x = nx
            points.append((x, y))
        elif kind == 'C':
            x1, y1, x2, y2, x3, y3 = (
                float(value) for value in tokens[index:index + 6]
            ); index += 6
            if relative:
                x1 += x; y1 += y; x2 += x; y2 += y; x3 += x; y3 += y
            x0, y0 = x, y
            for step in range(1, steps + 1):
                t = step / steps
                mt = 1 - t
                points.append((
                    mt ** 3 * x0 + 3 * mt * mt * t * x1 + 3 * mt * t * t * x2 + t ** 3 * x3,
                    mt ** 3 * y0 + 3 * mt * mt * t * y1 + 3 * mt * t * t * y2 + t ** 3 * y3,
                ))
            x, y = x3, y3
        elif kind == 'Z':
            x, y = start_x, start_y
        else:
            raise ValueError(f'不支持的路径命令：{command}')

    return points


def inside(px, py, polygon):
    result = False
    j = len(polygon) - 1
    for i in range(len(polygon)):
        xi, yi = polygon[i]
        xj, yj = polygon[j]
        if (yi > py) != (yj > py) and px < (xj - xi) * (py - yi) / (yj - yi) + xi:
            result = not result
        j = i
    return result


WAVE_POLYGON = flatten(WAVE)
PLAY_POLYGON = flatten(PLAY)


def sample(px, py):
    sx = VIEW_X + px / SCALE_X
    sy = VIEW_Y + py / SCALE_Y
    dx = sx - CIRCLE_CENTER_X
    dy = sy - CIRCLE_CENTER_Y
    if dx * dx + dy * dy > CIRCLE_RADIUS * CIRCLE_RADIUS:
        return TRANSPARENT
    if inside(sx, sy, WAVE_POLYGON) or inside(sx, sy, PLAY_POLYGON):
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
    """4 倍超采样；坐标直接遵循正式 SVG viewBox。"""
    pixels = bytearray()
    for y in range(size):
        pixels.append(0)
        for x in range(size):
            samples = [sample((x + (sx + 0.5) / SAMPLES) * CANVAS / size,
                              (y + (sy + 0.5) / SAMPLES) * CANVAS / size)
                       for sy in range(SAMPLES) for sx in range(SAMPLES)]
            pixels.extend(pixel(samples))

    def chunk(kind, data):
        return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data))

    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!2I5B', size, size, 8, 6, 0, 0, 0))
            + chunk(b'sRGB', b'\0') + chunk(b'IDAT', zlib.compress(bytes(pixels), 9)) + chunk(b'IEND', b''))


def generate(root):
    images = root / 'app/ui/images'
    images.mkdir(parents=True, exist_ok=True)
    for size, name in [(64, 'ICON.PNG'), (256, 'ICON_256.PNG')]:
        data = render(size)
        (root / name).write_bytes(data)
        (images / f'icon_{size}.png').write_bytes(data)
    names = ('ICON.PNG', 'ICON_256.PNG', 'app/ui/images/icon_64.png', 'app/ui/images/icon_256.png')
    contract = {
        'source': 'apps/web/src/assets/brand/app-icon.svg',
        'sourceSha256': hashlib.sha256(FORMAL_ICON.read_bytes()).hexdigest(),
        'icons': {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in names},
    }
    (root / 'icon-contract.json').write_text(json.dumps(contract, indent=2, sort_keys=True) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', nargs='?', type=Path, default=Path(__file__).resolve().parents[1] / 'fpk')
    generate(parser.parse_args().root)
