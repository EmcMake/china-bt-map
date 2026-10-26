#!/usr/bin/env python3
"""下载 Natural Earth 世界地图源数据到 tools/world_source。

用法：
  python3 tools/fetch_world.py [--force]
然后：
  python3 tools/pack_world.py
"""
import argparse
import os
import shutil
import sys
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ROOT, "tools", "world_source")

LAYERS = [
    ("50m_cultural", "ne_50m_admin_0_countries"),
    ("10m_cultural", "ne_10m_admin_1_states_provinces"),
    ("10m_cultural", "ne_10m_populated_places"),
]


def download(url, dest, force=False):
    if os.path.exists(dest) and not force:
        print("已存在:", dest)
        return
    tmp = dest + ".part"
    req = urllib.request.Request(url, headers={"User-Agent": "china-bt-map/1.0"})
    print("下载:", url)
    with urllib.request.urlopen(req, timeout=180) as r, open(tmp, "wb") as f:
        shutil.copyfileobj(r, f, 1 << 16)
    os.replace(tmp, dest)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--force", action="store_true")
    args = ap.parse_args()
    os.makedirs(SOURCE, exist_ok=True)
    for folder, name in LAYERS:
        zip_path = os.path.join(SOURCE, name + ".zip")
        url = "https://naturalearth.s3.amazonaws.com/%s/%s.zip" % (folder, name)
        try:
            download(url, zip_path, args.force)
        except Exception as e:
            print("下载失败:", url, e, file=sys.stderr)
            return 1
        out_dir = os.path.join(SOURCE, name)
        if not os.path.isdir(out_dir) or args.force:
            os.makedirs(out_dir, exist_ok=True)
            with zipfile.ZipFile(zip_path) as z:
                z.extractall(out_dir)
            print("解压:", out_dir)
    print("完成。运行 python3 tools/pack_world.py 生成 world_map.bin")


if __name__ == "__main__":
    raise SystemExit(main())
