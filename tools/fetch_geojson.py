#!/usr/bin/env python3
"""下载中国地图 GeoJSON 数据快照（阿里云 DataV.GeoAtlas）。

产物（已入库，构建不依赖实时网络）：
  tools/geojson/cn_100000_full.json      全国省级边界 + 南海诸岛（九段线）
  tools/geojson/prov_<adcode>_full.json  各省地级市边界
  tools/geojson/MANIFEST.json            adcode / 文件名 / sha256

用法:
  python3 tools/fetch_geojson.py [--force]
"""
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE = "https://geo.datav.aliyun.com/areas_v3/bound/{}.json"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "tools", "geojson")

UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) ChinaMapPack/1.0"


def fetch(url, dest):
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=45) as r:
                data = r.read()
            if not data.startswith(b"{"):
                raise ValueError("unexpected content: %r" % data[:80])
            json.loads(data)  # 校验 JSON 合法
            tmp = dest + ".part"
            with open(tmp, "wb") as f:
                f.write(data)
            os.replace(tmp, dest)
            return data
        except urllib.error.HTTPError as e:
            if e.code == 404:
                raise  # 该行政区没有下级数据，交给调用方跳过
            if attempt == 3:
                raise
            print("  重试 %d: %s (%s)" % (attempt + 1, url, e))
            time.sleep(1.5 * (attempt + 1))
        except Exception as e:  # noqa: BLE001
            if attempt == 3:
                raise
            print("  重试 %d: %s (%s)" % (attempt + 1, url, e))
            time.sleep(1.5 * (attempt + 1))


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()



def collect_cities(provinces):
    """从已下载的省级文件里收集地级市 adcode。"""
    cities = {}
    for adcode, _ in provinces:
        path = os.path.join(OUT, "prov_%s_full.json" % adcode)
        if not os.path.exists(path):
            continue
        try:
            data = json.load(open(path, encoding="utf-8"))
        except Exception:
            continue
        for f in data.get("features", []):
            q = f.get("properties") or {}
            code = str(q.get("adcode", ""))
            if q.get("level") == "city" and len(code) == 6 and code.isdigit():
                cities[code] = q.get("name", "")
    return sorted(cities.items())


def fetch_counties(cities, force=False):
    """下载每个地级市的下级（区县）边界，文件名 county_<adcode>_full.json。"""
    os.makedirs(OUT, exist_ok=True)
    todo = []
    for adcode, name in cities:
        dest = os.path.join(OUT, "county_%s_full.json" % adcode)
        if force or not os.path.exists(dest):
            todo.append((adcode, name))
    if not todo:
        print("区县数据已存在: %d 个市" % len(cities))
        return 0, 0

    print("下载区县边界: %d 个市 ..." % len(todo))
    ok = [0]
    skip = [0]
    lock = __import__("threading").Lock()

    def work(item):
        adcode, name = item
        dest = os.path.join(OUT, "county_%s_full.json" % adcode)
        try:
            fetch(BASE.format("%s_full" % adcode), dest)
            with lock:
                ok[0] += 1
        except urllib.error.HTTPError as e:
            if e.code == 404:
                with lock:
                    skip[0] += 1
                return
            raise
        except Exception as e:  # noqa: BLE001
            print("  失败 %s %s: %s" % (adcode, name, e))
            with lock:
                skip[0] += 1

    with ThreadPoolExecutor(max_workers=8) as ex:
        list(ex.map(work, todo))
    print("区县下载完成: 成功 %d, 跳过/失败 %d" % (ok[0], skip[0]))
    return ok[0], skip[0]


def main():
    force = "--force" in sys.argv
    os.makedirs(OUT, exist_ok=True)
    manifest_path = os.path.join(OUT, "MANIFEST.json")
    manifest = {}
    if os.path.exists(manifest_path) and not force:
        manifest = json.load(open(manifest_path, encoding="utf-8"))

    cn_path = os.path.join(OUT, "cn_100000_full.json")
    if force or not os.path.exists(cn_path):
        print("下载全国省级边界 ...")
        fetch(BASE.format("100000_full"), cn_path)

    cn = json.load(open(cn_path, encoding="utf-8"))
    provinces = []
    for f in cn["features"]:
        p = f.get("properties") or {}
        adcode = str(p.get("adcode", ""))
        if len(adcode) == 6 and adcode.isdigit() and adcode != "100000":
            provinces.append((adcode, p.get("name", "")))
    provinces.sort()

    skipped = []
    for adcode, name in provinces:
        dest = os.path.join(OUT, "prov_%s_full.json" % adcode)
        if force or not os.path.exists(dest):
            url = BASE.format("%s_full" % adcode)
            print("下载 %s %s ..." % (adcode, name))
            try:
                fetch(url, dest)
            except urllib.error.HTTPError as e:
                # 台湾省等个别行政区没有下级（地级市）边界数据
                if e.code == 404:
                    print("  跳过 %s %s: 数据源无下级边界" % (adcode, name))
                    skipped.append(adcode)
                    continue
                raise

    cities = collect_cities(provinces)
    fetch_counties(cities, force)

    files = ["cn_100000_full.json"] + [
        "prov_%s_full.json" % a for a, _ in provinces
        if os.path.exists(os.path.join(OUT, "prov_%s_full.json" % a))
    ] + [
        "county_%s_full.json" % a for a, _ in cities
        if os.path.exists(os.path.join(OUT, "county_%s_full.json" % a))
    ]
    manifest = {
        "source": "https://geo.datav.aliyun.com/areas_v3/bound/",
        "fetched_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "provinces": [{"adcode": a, "name": n} for a, n in provinces],
        "cities": [{"adcode": a, "name": n} for a, n in cities],
        "files": {f: {"sha256": sha256(os.path.join(OUT, f)),
                      "bytes": os.path.getsize(os.path.join(OUT, f))} for f in files},
    }
    with open(manifest_path, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, ensure_ascii=False, indent=2)
    total = sum(v["bytes"] for v in manifest["files"].values())
    print("完成: %d 个文件, %.1f MB" % (len(files), total / 1048576.0))


if __name__ == "__main__":
    main()
