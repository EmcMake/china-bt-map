#!/usr/bin/env python3
"""Pack Natural Earth country / admin-1 / populated places into WRLD1.

Source shapefiles (Natural Earth, public domain) are expected in:
  tools/world_source/ne_50m_admin_0_countries/
  tools/world_source/ne_10m_admin_1_states_provinces/
  tools/world_source/ne_10m_populated_places/
or under /tmp/ne0_50, /tmp/ne1, /tmp/nep for local development.

No pyshp/GDAL dependency: a minimal SHP/DBF reader is included.
"""
import glob
import math
import os
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSET = os.path.join(ROOT, "app/src/main/assets/world_map.bin")

WORLD_SOURCE = os.path.join(ROOT, "tools", "world_source")


def source_dir(name, fallback):
    p = os.path.join(WORLD_SOURCE, name)
    return p if os.path.isdir(p) else fallback


COUNTRY_DIR = os.environ.get("NE_COUNTRY_DIR",
                             source_dir("ne_50m_admin_0_countries", "/tmp/ne0_50"))
ADMIN1_DIR = os.environ.get("NE_ADMIN1_DIR",
                            source_dir("ne_10m_admin_1_states_provinces", "/tmp/ne1"))
PLACES_DIR = os.environ.get("NE_PLACES_DIR",
                            source_dir("ne_10m_populated_places", "/tmp/nep"))

MAGIC = b"WRLD1"
COUNTRY_DETAIL_EPS = 0.01
COUNTRY_OVERVIEW_EPS = 0.10
ADMIN1_DETAIL_EPS = 0.01
ADMIN1_OVERVIEW_EPS = 0.10


def log(*args):
    print(*args, flush=True)


# ---------------------------------------------------------------- DBF / SHP
def read_dbf(path):
    b = open(path, "rb").read()
    n = struct.unpack("<I", b[4:8])[0]
    hlen = struct.unpack("<H", b[8:10])[0]
    rlen = struct.unpack("<H", b[10:12])[0]
    fields = []
    off = 32
    while off < hlen - 1:
        if b[off] == 0x0D:
            break
        name = b[off:off + 11].split(b"\0")[0].decode("ascii", "ignore")
        typ = chr(b[off + 11])
        length = b[off + 16]
        fields.append((name, typ, length))
        off += 32
    out = []
    pos = hlen
    for i in range(n):
        rec = b[pos:pos + rlen]
        pos += rlen
        q = 1
        vals = {}
        for name, typ, length in fields:
            raw = rec[q:q + length]
            q += length
            txt = raw.decode("utf-8", "ignore").strip("\x00 ")
            vals[name] = txt
        out.append(vals)
    return out


def read_shp_records(path):
    b = open(path, "rb").read()
    pos = 100
    out = []
    while pos + 8 <= len(b):
        _num, clen = struct.unpack(">2i", b[pos:pos + 8])
        pos += 8
        end = pos + clen * 2
        if end > len(b):
            break
        st = struct.unpack("<i", b[pos:pos + 4])[0]
        rec = None
        if st == 1:
            x, y = struct.unpack("<2d", b[pos + 4:pos + 20])
            rec = {"type": "point", "lon": x, "lat": y}
        elif st == 5:
            nparts, npoints = struct.unpack("<2i", b[pos + 36:pos + 44])
            parts = list(struct.unpack("<%di" % nparts,
                                       b[pos + 44:pos + 44 + 4 * nparts]))
            pts_off = pos + 44 + 4 * nparts
            pts = struct.unpack("<%dd" % (npoints * 2),
                                b[pts_off:pts_off + 16 * npoints])
            rings = []
            for i in range(nparts):
                s = parts[i]
                e = parts[i + 1] if i + 1 < nparts else npoints
                ring = [(pts[2 * j], pts[2 * j + 1]) for j in range(s, e)]
                if len(ring) >= 2:
                    if ring[0] != ring[-1]:
                        ring.append(ring[0])
                    rings.append(ring)
            if rings:
                rec = {"type": "rings", "rings": rings}
        if rec is not None:
            out.append(rec)
        pos = end
    return out


def load_layer(directory):
    dbf = glob.glob(os.path.join(directory, "*.dbf"))
    shp = glob.glob(os.path.join(directory, "*.shp"))
    if not dbf or not shp:
        raise SystemExit("缺少 shapefile: %s" % directory)
    records = read_dbf(dbf[0])
    shapes = read_shp_records(shp[0])
    n = min(len(records), len(shapes))
    return records[:n], shapes[:n]


# ---------------------------------------------------------------- geometry
def rdp(points, eps):
    if len(points) < 3:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        a, b = stack.pop()
        ax, ay = points[a]
        bx, by = points[b]
        dx, dy = bx - ax, by - ay
        den = dx * dx + dy * dy
        best = -1
        best_d = -1.0
        for i in range(a + 1, b):
            px, py = points[i]
            if den == 0:
                d = (px - ax) ** 2 + (py - ay) ** 2
            else:
                t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / den))
                d = (px - (ax + t * dx)) ** 2 + (py - (ay + t * dy)) ** 2
            if d > best_d:
                best_d = d
                best = i
        if best_d > eps * eps and best > 0:
            keep[best] = True
            stack.append((a, best))
            stack.append((best, b))
    return [p for p, k in zip(points, keep) if k]


def simplify(ring, eps):
    closed = len(ring) > 2 and ring[0] == ring[-1]
    pts = ring[:-1] if closed else list(ring)
    out = rdp(pts, eps) if len(pts) > 2 else pts
    if len(out) < 2:
        out = pts[:2]
    if closed and out[0] != out[-1]:
        out.append(out[0])
    return out


# ---------------------------------------------------------------- features
def num(v, default=0):
    try:
        return int(float(v))
    except (TypeError, ValueError):
        return default


def fnum(v, default=0.0):
    try:
        return float(v)
    except (TypeError, ValueError):
        return default


def country_features():
    records, shapes = load_layer(COUNTRY_DIR)
    out = []
    for rec, shp in zip(records, shapes):
        if shp.get("type") != "rings":
            continue
        name_en = rec.get("NAME") or rec.get("NAME_EN") or rec.get("ADMIN") or ""
        name_zh = rec.get("NAME_ZH") or name_en
        cx = fnum(rec.get("LABEL_X"), 0)
        cy = fnum(rec.get("LABEL_Y"), 0)
        rings = shp["rings"]
        out.append({
            "level": 0,
            "name_en": name_en.strip(),
            "name_zh": name_zh.strip(),
            "center": (cy, cx),
            "pop": num(rec.get("POP_EST")),
            "label_rank": num(rec.get("LABELRANK"), 5),
            "rings": rings,
            "detail_eps": COUNTRY_DETAIL_EPS,
            "overview_eps": COUNTRY_OVERVIEW_EPS,
            "iso": rec.get("ISO_A2", ""),
            "parent": rec.get("NAME", ""),
        })
    return out


def admin1_features():
    records, shapes = load_layer(ADMIN1_DIR)
    out = []
    for rec, shp in zip(records, shapes):
        if shp.get("type") != "rings":
            continue
        name_en = rec.get("name_en") or rec.get("name") or ""
        name_zh = rec.get("name_zh") or name_en
        cx = fnum(rec.get("longitude"), 0)
        cy = fnum(rec.get("latitude"), 0)
        if cx == 0 and cy == 0:
            pts = [p for r in shp["rings"] for p in r]
            if pts:
                cx = sum(p[0] for p in pts) / len(pts)
                cy = sum(p[1] for p in pts) / len(pts)
        out.append({
            "level": 1,
            "name_en": name_en.strip(),
            "name_zh": name_zh.strip(),
            "center": (cy, cx),
            "pop": 0,
            "label_rank": num(rec.get("labelrank"), 5),
            "rings": shp["rings"],
            "detail_eps": ADMIN1_DETAIL_EPS,
            "overview_eps": ADMIN1_OVERVIEW_EPS,
            "admin": rec.get("admin", ""),
            "iso": rec.get("iso_a2", ""),
            "parent": rec.get("admin", ""),
        })
    return out


def place_features():
    records, shapes = load_layer(PLACES_DIR)
    out = []
    for rec, shp in zip(records, shapes):
        if shp.get("type") != "point":
            continue
        name_en = rec.get("NAME_EN") or rec.get("NAME") or ""
        name_zh = rec.get("NAME_ZH") or name_en
        out.append({
            "level": 2,
            "name_en": name_en.strip(),
            "name_zh": name_zh.strip(),
            "center": (shp["lat"], shp["lon"]),
            "pop": num(rec.get("POP_MAX")),
            "label_rank": num(rec.get("SCALERANK"), 5),
            "rings": [],
            "detail_eps": 0,
            "overview_eps": 0,
            "admin": rec.get("ADM0NAME", ""),
            "parent": rec.get("ADM0NAME", ""),
        })
    return out


# ---------------------------------------------------------------- writer
def ud(v):
    return int(round(v * 1_000_000.0))


def write_rings(f, rings, eps):
    for ring in rings:
        r = simplify(ring, eps) if eps > 0 else ring
        f.write(struct.pack(">I", len(r)))
        f.write(struct.pack(">%di" % (len(r) * 2),
                            *[v for x, y in r for v in (ud(x), ud(y))]))


def pack(features):
    os.makedirs(os.path.dirname(ASSET), exist_ok=True)
    lons = [p[0] for ft in features for r in ft["rings"] for p in r]
    lats = [p[1] for ft in features for r in ft["rings"] for p in r]
    bbox = (min(lons), min(lats), max(lons), max(lats))
    tmp = ASSET + ".part"
    detail_pts = overview_pts = 0
    written = 0
    with open(tmp, "wb") as f:
        f.write(MAGIC)
        f.write(struct.pack(">I", len(features)))
        f.write(struct.pack(">4f", *bbox))
        for ft in features:
            rings = [r for r in ft["rings"] if len(r) >= 2]
            detail = [simplify(r, ft["detail_eps"]) for r in rings] if rings else []
            overview = [simplify(r, ft["overview_eps"]) for r in rings] if rings else []
            detail_pts += sum(len(r) for r in detail)
            overview_pts += sum(len(r) for r in overview)
            ne = ft["name_en"].encode("utf-8")[:65535]
            nz = ft["name_zh"].encode("utf-8")[:65535]
            parent = ft.get("parent", "").encode("utf-8")[:65535]
            cy, cx = ft["center"]
            if rings:
                xs = [p[0] for r in detail for p in r]
                ys = [p[1] for r in detail for p in r]
                b = (min(xs), min(ys), max(xs), max(ys))
            else:
                b = (cx, cy, cx, cy)
            f.write(struct.pack(">BBHHH", ft["level"],
                                 max(0, min(255, ft["label_rank"])),
                                 len(ne), len(nz), len(parent)))
            f.write(ne)
            f.write(nz)
            f.write(parent)
            f.write(struct.pack(">2i", ud(cx), ud(cy)))
            f.write(struct.pack(">4i", ud(b[0]), ud(b[1]), ud(b[2]), ud(b[3])))
            f.write(struct.pack(">i", max(0, ft["pop"])))
            f.write(struct.pack(">HH", len(detail), len(overview)))
            write_rings(f, detail, 0)
            write_rings(f, overview, 0)
            written += 1
    os.replace(tmp, ASSET)
    return written, detail_pts, overview_pts, bbox


def main():
    log("读取 Natural Earth ...")
    countries = country_features()
    admin1 = admin1_features()
    places = place_features()
    log("国家", len(countries), "省州", len(admin1), "城市", len(places))
    features = countries + admin1 + places
    written, detail, overview, bbox = pack(features)
    size = os.path.getsize(ASSET)
    log("打包完成: features=%d detail=%d overview=%d size=%.2fMB"
        % (written, detail, overview, size / 1048576.0))
    log("bbox", bbox)
    if written < 7000:
        raise SystemExit("要素数量异常")
    for name, group in (("国家", countries), ("省州", admin1), ("城市", places)):
        zh = sum(1 for f in group if f["name_zh"])
        en = sum(1 for f in group if f["name_en"])
        log("%s 双名: zh=%d/%d en=%d/%d" % (name, zh, len(group), en, len(group)))


if __name__ == "__main__":
    main()
