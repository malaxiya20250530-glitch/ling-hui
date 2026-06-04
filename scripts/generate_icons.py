"""灵绘图标生成器 — 纯 Python 标准库，生成各密度 PNG 图标

设计：紫色渐变背景 + 白色 AI 精灵果冻体 + 大眼睛 + 微笑
"""

import struct
import zlib
import os


def create_png(width: int, height: int) -> bytearray:
    """创建带 Alpha 通道的 RGBA 像素数组"""
    return bytearray(width * height * 4)


def set_pixel(pixels: bytearray, w: int, h: int, x: int, y: int,
              r: int, g: int, b: int, a: int = 255):
    """设置单个像素"""
    if 0 <= x < w and 0 <= y < h:
        idx = (y * w + x) * 4
        pixels[idx] = r
        pixels[idx + 1] = g
        pixels[idx + 2] = b
        pixels[idx + 3] = a


def fill_rect(pixels: bytearray, w: int, h: int,
              x0: int, y0: int, x1: int, y1: int,
              r: int, g: int, b: int, a: int = 255):
    """填充矩形"""
    for y in range(max(0, y0), min(h, y1)):
        for x in range(max(0, x0), min(w, x1)):
            set_pixel(pixels, w, h, x, y, r, g, b, a)


def fill_circle(pixels: bytearray, w: int, h: int,
                cx: int, cy: int, radius: int,
                r: int, g: int, b: int, a: int = 255):
    """填充圆形"""
    for y in range(max(0, cy - radius), min(h, cy + radius + 1)):
        for x in range(max(0, cx - radius), min(w, cx + radius + 1)):
            if (x - cx) ** 2 + (y - cy) ** 2 <= radius ** 2:
                set_pixel(pixels, w, h, x, y, r, g, b, a)


def fill_ellipse(pixels: bytearray, w: int, h: int,
                 cx: int, cy: int, rx: int, ry: int,
                 r: int, g: int, b: int, a: int = 255):
    """填充椭圆"""
    for y in range(max(0, cy - ry), min(h, cy + ry + 1)):
        for x in range(max(0, cx - rx), min(w, cx + rx + 1)):
            if ((x - cx) ** 2) / (rx ** 2) + ((y - cy) ** 2) / (ry ** 2) <= 1:
                set_pixel(pixels, w, h, x, y, r, g, b, a)


def fill_gradient(pixels: bytearray, w: int, h: int,
                  color_top: tuple, color_bot: tuple):
    """垂直渐变填充"""
    r1, g1, b1 = color_top
    r2, g2, b2 = color_bot
    for y in range(h):
        t = y / h
        r = int(r1 + (r2 - r1) * t)
        g = int(g1 + (g2 - g1) * t)
        b = int(b1 + (b2 - b1) * t)
        for x in range(w):
            set_pixel(pixels, w, h, x, y, r, g, b)


def encode_png(pixels: bytearray, w: int, h: int) -> bytes:
    """编码 RGBA 像素数组为 PNG 字节"""

    def chunk(chunk_type: str, data: bytes) -> bytes:
        c = chunk_type.encode() + data
        crc = struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)
        return struct.pack(">I", len(data)) + c + crc

    # PNG 签名
    signature = b'\x89PNG\r\n\x1a\n'

    # IHDR
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)  # 8-bit RGBA
    encoded = signature + chunk("IHDR", ihdr)

    # IDAT — 逐行压缩
    raw = b''
    for y in range(h):
        raw += b'\x00'  # filter byte
        raw += bytes(pixels[y * w * 4:(y + 1) * w * 4])

    encoded += chunk("IDAT", zlib.compress(raw))

    # IEND
    encoded += chunk("IEND", b'')
    return encoded


def draw_character(pixels: bytearray, w: int, h: int):
    """绘制灵绘精灵图标"""

    # 1. 紫色渐变背景
    fill_gradient(pixels, w, h,
                  (124, 58, 237),   # #7C3AED 紫
                  (168, 85, 247))    # #A855F7 浅紫

    # 坐标系统：将 108x108 视口映射到实际尺寸
    s = w / 108
    sc = lambda v: int(v * s)

    cx, cy = sc(54), sc(52)

    # 2. 身体：白色果冻气泡
    body_rx, body_ry = sc(28), sc(30)
    fill_ellipse(pixels, w, h, cx, cy, body_rx, body_ry, 255, 255, 255)

    # 3. 光环/天线
    fill_ellipse(pixels, w, h, cx, sc(22), sc(6), sc(4), 167, 139, 250)  # #A78BFA

    # 4. 左眼 — 紫色椭圆
    eye_y = sc(46)
    eye_rx, eye_ry = sc(7), sc(8)
    fill_ellipse(pixels, w, h, sc(42), eye_y, eye_rx, eye_ry, 124, 58, 237)
    # 左眼高光
    fill_circle(pixels, w, h, sc(44), sc(44), sc(3), 255, 255, 255)

    # 5. 右眼
    fill_ellipse(pixels, w, h, sc(66), eye_y, eye_rx, eye_ry, 124, 58, 237)
    fill_circle(pixels, w, h, sc(68), sc(44), sc(3), 255, 255, 255)

    # 6. 腮红（左）
    fill_ellipse(pixels, w, h, sc(34), sc(56), sc(6), sc(4), 245, 208, 254, 180)

    # 7. 腮红（右）
    fill_ellipse(pixels, w, h, sc(74), sc(56), sc(6), sc(4), 245, 208, 254, 180)

    # 8. 微笑弧线
    smile_y = sc(60)
    for x in range(sc(44), sc(64)):
        dx = (x - sc(54)) / sc(18)
        y_offset = int((dx ** 2) * sc(12))
        set_pixel(pixels, w, h, x, smile_y + y_offset, 124, 58, 237)
        set_pixel(pixels, w, h, x, smile_y + y_offset + 1, 124, 58, 237)


# ── 生成各密度图标 ──────────────────────────────────

DENSITIES = {
    "mdpi":    (48, 48),
    "hdpi":    (72, 72),
    "xhdpi":   (96, 96),
    "xxhdpi":  (144, 144),
    "xxxhdpi": (192, 192),
}

RES_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       "android_app", "app", "src", "main", "res")

for density, (w, h) in DENSITIES.items():
    # 方形图标
    pixels = create_png(w, h)
    draw_character(pixels, w, h)
    png_data = encode_png(pixels, w, h)

    mip_dir = os.path.join(RES_DIR, f"mipmap-{density}")
    os.makedirs(mip_dir, exist_ok=True)
    path = os.path.join(mip_dir, "ic_launcher.png")
    with open(path, "wb") as f:
        f.write(png_data)
    print(f"  ✓ {path} ({w}x{h})")

    # 圆形图标（同样内容，可用于圆形遮罩）
    path_round = os.path.join(mip_dir, "ic_launcher_round.png")
    with open(path_round, "wb") as f:
        f.write(png_data)
    print(f"  ✓ {path_round} ({w}x{h})")

print("\n✅ 10 个 PNG 图标已生成（5 密度 × 2 形状）")
print("   API 26+ 使用矢量 Adaptive Icon")
print("   API < 26 回退到 PNG 图标")
