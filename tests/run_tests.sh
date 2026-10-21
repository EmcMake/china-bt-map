#!/usr/bin/env bash
# 纯 JVM 单测：坐标解析 / 墨卡托投影 / GCJ 转换 / 轨迹存储 / 地图二进制读取
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TC="$ROOT/toolchain"
export JAVA_HOME="$TC/jdk-17"
export PATH="$JAVA_HOME/bin:$PATH"

OUT="$ROOT/build/tests"
rm -rf "$OUT"
mkdir -p "$OUT/classes"

SRC=$(cd "$ROOT" && find app/src/main/java -name '*.java' | grep -E \
  '/(map/Mercator|map/MapData|map/TileMath|map/TileSource|track/CoordParser|track/CoordTransform|track/CoordSystems|track/TrackStore)\.java$')

# shellcheck disable=SC2086
javac --release 11 -encoding UTF-8 -d "$OUT/classes" $SRC tests/CoreTest.java
java -cp "$OUT/classes" CoreTest "$ROOT"
