#!/usr/bin/env python3
"""把 GeoJSON 快照打包成 App 内置的紧凑二进制地图资产（CNMAP2）。

输入: tools/geojson/*.json
输出:
  app/src/main/assets/china_map.bin   地图数据
  docs/preview/china_map.png          离线预览图（几何/投影肉眼校验）

CNMAP2 相比 CNMAP1 的变化：
  * 坐标用 int32 微度（1e-6 度 ≈ 0.1m），避免 float32 的米级量化；
  * 每个要素带 bbox（微度），供绘制端做视口裁剪；
  * 每个要素存两套 LOD：detail（保留源顶点）与 overview（抽稀，低缩放用）。

二进制格式（大端，兼容 Java DataInputStream）:
  magic  b"CNMAP2"
  uint32 feature_count
  float32 min_lon, min_lat, max_lon, max_lat
  repeat feature_count:
    uint8  level            0=省界 1=市界 2=九段线
    uint8  flags            保留
    int32  center_lon_ud, center_lat_ud   （INT32_MIN 表示无标注点）
    uint16 name_len + bytes name(utf8)
    int32  bbox_min_lon_ud, bbox_min_lat_ud, bbox_max_lon_ud, bbox_max_lat_ud
    uint16 ring_count
    # detail LOD
    repeat ring_count:
      uint32 point_count
      repeat point_count: int32 lon_ud, int32 lat_ud
    # overview LOD（ring 顺序与 detail 一致）
    repeat ring_count:
      uint32 point_count
      repeat point_count: int32 lon_ud, int32 lat_ud

用法:
  python3 tools/pack_map.py            # 生成资产 + 预览图
  python3 tools/pack_map.py --verify   # 仅校验收到的资产
"""
import json
import math
import os
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GEO = os.path.join(ROOT, "tools", "geojson")
ASSET = os.path.join(ROOT, "app", "src", "main", "assets", "china_map.bin")
PREVIEW = os.path.join(ROOT, "docs", "preview", "china_map.png")

DETAIL_EPS = 0.0002      # 度，约 20m：基本保留全部源顶点
OVERVIEW_EPS = 0.010     # 度，约 1km：低缩放用
JD_DETAIL_EPS = 0.0005
JD_OVERVIEW_EPS = 0.002

MAGIC = b"CNMAP2"
NO_CENTER = -(2 ** 31)


# ---------------------------------------------------------------- simplify
def rdp(points, eps):
    """Douglas–Peucker，points 为 [(lon, lat), ...]；保持首尾。"""
    if len(points) < 3 or eps <= 0:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        a, b = stack.pop()
        ax, ay = points[a]
        bx, by = points[b]
        dx, dy = bx - ax, by - ay
        denom = dx * dx + dy * dy
        best_i, best_d = -1, -1.0
        for i in range(a + 1, b):
            px, py = points[i]
            if denom == 0:
                d = (px - ax) ** 2 + (py - ay) ** 2
            else:
                t = ((px - ax) * dx + (py - ay) * dy) / denom
                t = max(0.0, min(1.0, t))
                d = (px - (ax + t * dx)) ** 2 + (py - (ay + t * dy)) ** 2
            if d > best_d:
                best_d, best_i = d, i
        if best_d > eps * eps and best_i > 0:
            keep[best_i] = True
            stack.append((a, best_i))
            stack.append((best_i, b))
    return [p for p, k in zip(points, keep) if k]


def simplify_ring(ring, eps, min_points=2):
    closed = len(ring) > 2 and ring[0] == ring[-1]
    pts = ring[:-1] if closed else list(ring)
    if len(pts) <= min_points:
        out = pts
    else:
        out = rdp(pts, eps)
    if len(out) < min_points:
        out = pts[:min_points]
    if closed and len(out) >= 2 and out[0] != out[-1]:
        out.append(out[0])
    return out


# ---------------------------------------------------------------- geometry
def feature_rings(geom):
    t = geom.get("type")
    if t == "Polygon":
        return geom["coordinates"]
    if t == "MultiPolygon":
        rings = []
        for poly in geom["coordinates"]:
            rings.extend(poly)
        return rings
    if t == "LineString":
        return [geom["coordinates"]]
    if t == "MultiLineString":
        return geom["coordinates"]
    return []


def load_provinces():
    data = json.load(open(os.path.join(GEO, "cn_100000_full.json"), encoding="utf-8"))
    provs, jd = [], None
    for f in data["features"]:
        p = f.get("properties") or {}
        adcode = str(p.get("adcode", ""))
        name = (p.get("name") or "").strip()
        geom = f.get("geometry") or {}
        if adcode.endswith("_JD") or name == "":
            jd = geom
            continue
        center = p.get("centroid") or p.get("center")
        if not center:
            continue
        provs.append({
            "level": 0,
            "name": name,
            "center": (float(center[0]), float(center[1])),
            "rings": [[(float(x), float(y)) for x, y in r] for r in feature_rings(geom)],
            "detail_eps": DETAIL_EPS,
            "overview_eps": OVERVIEW_EPS,
        })
    return provs, jd


def load_cities():
    cities = []
    for fn in sorted(os.listdir(GEO)):
        if not (fn.startswith("prov_") and fn.endswith("_full.json")):
            continue
        data = json.load(open(os.path.join(GEO, fn), encoding="utf-8"))
        for f in data["features"]:
            p = f.get("properties") or {}
            name = (p.get("name") or "").strip()
            center = p.get("center") or p.get("centroid")
            geom = f.get("geometry") or {}
            if not name or not center:
                continue
            cities.append({
                "level": 1,
                "name": name,
                "center": (float(center[0]), float(center[1])),
                "rings": [[(float(x), float(y)) for x, y in r] for r in feature_rings(geom)],
                "detail_eps": DETAIL_EPS,
                "overview_eps": OVERVIEW_EPS,
            })
    return cities


# ---------------------------------------------------------------- writer
def ud(v):
    """度 -> 微度 int32。"""
    return int(round(v * 1_000_000.0))


def write_ring(f, ring):
    f.write(struct.pack(">I", len(ring)))
    f.write(struct.pack(">%di" % (len(ring) * 2),
                        *[v for x, y in ring for v in (ud(x), ud(y))]))


def pack(features, bbox):
    os.makedirs(os.path.dirname(ASSET), exist_ok=True)
    tmp = ASSET + ".part"
    n_detail = n_overview = 0
    written = 0
    with open(tmp, "wb") as f:
        f.write(MAGIC)
        f.write(struct.pack(">I", len(features)))
        f.write(struct.pack(">4f", *bbox))
        for ft in features:
            rings = [r for r in ft["rings"] if len(r) >= 2]
            if not rings:
                continue
            detail = [simplify_ring(r, ft["detail_eps"]) for r in rings]
            overview = [simplify_ring(r, ft["overview_eps"]) for r in rings]
            name = ft["name"].encode("utf-8")
            lons = [x for r in detail for x, _ in r]
            lats = [y for r in detail for _, y in r]
            cx, cy = ft["center"] if ft.get("center") else (None, None)

            f.write(struct.pack(">B", ft["level"]))
            f.write(struct.pack(">B", 0))
            f.write(struct.pack(">2i", ud(cx) if cx is not None else NO_CENTER,
                                ud(cy) if cy is not None else NO_CENTER))
            f.write(struct.pack(">H", len(name)))
            f.write(name)
            f.write(struct.pack(">4i", ud(min(lons)), ud(min(lats)),
                                ud(max(lons)), ud(max(lats))))
            f.write(struct.pack(">H", len(detail)))
            for r in detail:
                write_ring(f, r)
                n_detail += len(r)
            for r in overview:
                write_ring(f, r)
                n_overview += len(r)
            written += 1
        # 写回真实要素数（跳过空几何的要素）
        if written != len(features):
            f.seek(len(MAGIC))
            f.write(struct.pack(">I", written))
    os.replace(tmp, ASSET)
    return written, n_detail, n_overview


# ---------------------------------------------------------------- verify
def verify(verbose=True):
    data = open(ASSET, "rb").read()
    if not data.startswith(MAGIC):
        raise SystemExit("资产 magic 错误")
    off = len(MAGIC)
    count = struct.unpack_from(">I", data, off)[0]
    off += 4
    bbox = struct.unpack_from(">4f", data, off)
    off += 16
    levels = {0: 0, 1: 0, 2: 0}
    names = []
    detail_pts = overview_pts = 0
    for _ in range(count):
        level = data[off]
        off += 2
        cx, cy = struct.unpack_from(">2i", data, off)
        off += 8
        nl = struct.unpack_from(">H", data, off)[0]
        off += 2
        name = data[off:off + nl].decode("utf-8")
        off += nl
        off += 16  # bbox
        rc = struct.unpack_from(">H", data, off)[0]
        off += 2
        levels[level] = levels.get(level, 0) + 1
        names.append(name)
        for _ in range(rc):
            n = struct.unpack_from(">I", data, off)[0]
            off += 4 + n * 8
            detail_pts += n
        for _ in range(rc):
            n = struct.unpack_from(">I", data, off)[0]
            off += 4 + n * 8
            overview_pts += n
    if off != len(data):
        raise SystemExit("资产长度不一致: %d != %d" % (off, len(data)))
    if verbose:
        print("校验通过: features=%d 省=%d 市=%d 九段线=%d detail=%d overview=%d 大小=%.2fMB"
              % (count, levels.get(0, 0), levels.get(1, 0), levels.get(2, 0),
                 detail_pts, overview_pts, len(data) / 1048576.0))
        print("bbox: %.3f, %.3f, %.3f, %.3f" % bbox)
        print("示例名称:", "、".join(names[:6]))
    for n in ("北京市", "上海市", "广东省", "新疆维吾尔自治区", "台湾省", "香港特别行政区"):
        if n not in names:
            raise SystemExit("缺少要素: " + n)
    if levels.get(0, 0) < 34:
        raise SystemExit("省级要素不足")
    return count, detail_pts, overview_pts


# ---------------------------------------------------------------- preview
def preview(provs, cities, jd):
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        print("未安装 Pillow，跳过预览图")
        return
    min_lon, min_lat, max_lon, max_lat = 73.0, 2.5, 135.5, 54.5
    kx = math.cos(math.radians(35.0))
    W = 1600
    scale = W / ((max_lon - min_lon) * kx)
    H = int((max_lat - min_lat) * scale)
    img = Image.new("RGB", (W, H), (214, 232, 246))
    d = ImageDraw.Draw(img)

    def px(lon, lat):
        return ((lon - min_lon) * kx * scale, (max_lat - lat) * scale)

    for ft in provs:
        for ring in ft["rings"]:
            d.polygon([px(x, y) for x, y in ring], fill=(246, 241, 227))
    for ft in cities:
        for ring in ft["rings"]:
            if len(ring) > 1:
                d.line([px(x, y) for x, y in ring], fill=(205, 195, 174), width=1)
    for ft in provs:
        for ring in ft["rings"]:
            d.line([px(x, y) for x, y in ring], fill=(160, 145, 118), width=2)
    if jd:
        for ring in feature_rings(jd):
            d.line([px(x, y) for x, y in ring], fill=(120, 120, 120), width=3)
    os.makedirs(os.path.dirname(PREVIEW), exist_ok=True)
    img.save(PREVIEW)
    print("预览图:", PREVIEW)


# ---------------------------------------------------------------- main
def main():
    if "--verify" in sys.argv:
        verify()
        return
    if not os.path.exists(os.path.join(GEO, "cn_100000_full.json")):
        raise SystemExit("缺少 GeoJSON，请先运行 tools/fetch_geojson.py")
    provs, jd_geom = load_provinces()
    cities = load_cities()
    features = list(provs) + list(cities)
    if jd_geom:
        features.append({
            "level": 2,
            "name": "南海诸岛",
            "center": None,
            "rings": [[(float(x), float(y)) for x, y in r] for r in feature_rings(jd_geom)],
            "detail_eps": JD_DETAIL_EPS,
            "overview_eps": JD_OVERVIEW_EPS,
        })
    lons = [x for ft in provs for r in ft["rings"] for x, _ in r]
    lats = [y for ft in provs for r in ft["rings"] for _, y in r]
    bbox = (min(lons), min(lats), max(lons), max(lats))
    written, nd, no = pack(features, bbox)
    print("打包完成: features=%d detail_points=%d overview_points=%d size=%.2fMB"
          % (written, nd, no, os.path.getsize(ASSET) / 1048576.0))
    verify()
    preview(provs, cities, jd_geom)


if __name__ == "__main__":
    main()
