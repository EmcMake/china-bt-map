#!/usr/bin/env python3
"""生成 App 启动图标 PNG（mipmap-*dpi）以及自适应图标前景矢量图。"""
import math
import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
BG = (13, 71, 161)
PIN = (255, 255, 255)
DOT = (229, 57, 53)


def draw_icon(size):
    scale = 8
    s = size * scale
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    r = s * 0.22
    d.rounded_rectangle([0, 0, s - 1, s - 1], radius=r, fill=BG)
    # 虚线轨迹
    pts = [(0.18, 0.78), (0.30, 0.66), (0.40, 0.72), (0.52, 0.58)]
    for i in range(len(pts) - 1):
        x0, y0 = pts[i][0] * s, pts[i][1] * s
        x1, y1 = pts[i + 1][0] * s, pts[i + 1][1] * s
        steps = 6
        for k in range(steps):
            if k % 2:
                continue
            t0, t1 = k / steps, (k + 0.6) / steps
            d.line([x0 + (x1 - x0) * t0, y0 + (y1 - y0) * t0,
                    x0 + (x1 - x0) * t1, y0 + (y1 - y0) * t1],
                   fill=(255, 255, 255, 170), width=int(s * 0.035))
    # 定位针
    cx, cy = s * 0.58, s * 0.40
    R = s * 0.20
    d.ellipse([cx - R, cy - R, cx + R, cy + R], fill=PIN)
    d.polygon([(cx - R * 0.62, cy + R * 0.62), (cx + R * 0.62, cy + R * 0.62),
               (cx, cy + R * 1.85)], fill=PIN)
    d.ellipse([cx - R * 0.42, cy - R * 0.42, cx + R * 0.42, cy + R * 0.42], fill=DOT)
    return img.resize((size, size), Image.LANCZOS)


for folder, px in SIZES.items():
    out = os.path.join(RES, folder)
    os.makedirs(out, exist_ok=True)
    draw_icon(px).save(os.path.join(out, "ic_launcher.png"))
print("图标已生成:", ", ".join("%s/%d" % (k, v) for k, v in SIZES.items()))
