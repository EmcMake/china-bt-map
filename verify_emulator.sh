#!/usr/bin/env bash
# 模拟器端到端验证：创建 AVD → 安装 APK → 注入示例坐标 → 截取主界面/全国视图/
# 坐标系对话框/蓝牙诊断/最大缩放 → 检查崩溃
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
TC="$ROOT/toolchain"
export JAVA_HOME="$TC/jdk-17"
export ANDROID_HOME="$TC/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$ROOT/.android"
export ANDROID_AVD_HOME="$ROOT/.android/avd"
export HOME="$ROOT/.home"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
mkdir -p "$HOME" "$ANDROID_AVD_HOME" "$ROOT/docs/screenshots" "$ROOT/build"

PKG=com.btmap.china
AVD=chinamap_test
APK="$ROOT/dist/china-bt-map-1.2.2.apk"
KEEP="${KEEP:-0}"
[ -f "$APK" ] || { echo "缺少 $APK，请先 ./build.sh" >&2; exit 1; }

# 用 '/' 分隔，避免 adb shell 把分号当命令分隔符
DEMO="39.9042,116.4074/39.9150,116.4300/39.9250,116.4500/39.9200,116.4700/39.9000,116.4800/39.8850,116.4700/39.8800,116.4500/39.8900,116.4300/39.9042,116.4074"

if ! "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" list avd 2>/dev/null | grep -q "Name: $AVD"; then
  echo "==> 创建 AVD"
  echo no | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd \
    -n "$AVD" -k "system-images;android-34;google_apis;arm64-v8a" -d pixel_5 --force
fi

adb start-server >/dev/null 2>&1 || true
if adb devices 2>/dev/null | grep -q "emulator-.*device"; then
  echo "==> 复用已启动的模拟器"
else
  echo "==> 启动模拟器（无窗口）"
  nohup "$ANDROID_HOME/emulator/emulator" -avd "$AVD" \
    -no-window -no-audio -no-boot-anim -no-snapshot -gpu "${GPU:-swiftshader_indirect}" \
    > "$ROOT/build/emulator.log" 2>&1 &
  echo "emulator pid=$!"
  adb wait-for-device
fi

for i in $(seq 1 240); do
  boot=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  [ "$boot" = "1" ] && break
  sleep 1
  if [ "$i" = "240" ]; then
    echo "模拟器启动超时" >&2
    tail -40 "$ROOT/build/emulator.log" >&2
    exit 1
  fi
done
adb shell settings put global window_animation_scale 0 || true
adb shell input keyevent 82 >/dev/null 2>&1 || true

echo "==> 安装 APK（清空旧数据，验证首次启动默认 GCJ-02）"
adb install -r "$APK"
adb shell pm clear "$PKG" >/dev/null
adb shell pm grant "$PKG" android.permission.BLUETOOTH_SCAN || true
adb shell pm grant "$PKG" android.permission.BLUETOOTH_CONNECT || true

echo "==> 启动 App 并注入坐标"
adb logcat -c || true
adb shell am start -n "$PKG/.MainActivity" --es demo_points "$DEMO" >/dev/null
sleep 6
adb exec-out screencap -p > "$ROOT/docs/screenshots/app_main.png"

# 按文本找控件再点，避免写死分辨率
tap_text() {
  local want="$1"
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  adb exec-out cat /sdcard/ui.xml > "$ROOT/build/ui.xml" 2>/dev/null || true
  local xy
  xy=$(python3 - "$ROOT/build/ui.xml" "$want" <<'PY'
import os, re, sys
path, want = sys.argv[1], sys.argv[2]
xml = open(path, encoding='utf-8', errors='ignore').read() if os.path.exists(path) else ''
for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    if want in m.group(1):
        print((int(m.group(2)) + int(m.group(4))) // 2, (int(m.group(3)) + int(m.group(5))) // 2)
        break
PY
)
  if [ -n "$xy" ]; then
    echo "tap $xy ($want)"
    # shellcheck disable=SC2086
    adb shell input tap $xy >/dev/null
    return 0
  fi
  echo "未找到控件: $want" >&2
  return 1
}

echo "==> 全国视图"
tap_text "◎" && sleep 2 && adb exec-out screencap -p > "$ROOT/docs/screenshots/app_national.png"

echo "==> 坐标系对话框"
tap_text "GCJ-02" && sleep 2 && adb exec-out screencap -p > "$ROOT/docs/screenshots/datum_dialog.png"
adb shell input keyevent 4

echo "==> 蓝牙诊断"
tap_text "连接设备" && sleep 2
tap_text "诊断" && sleep 2 && adb exec-out screencap -p > "$ROOT/docs/screenshots/diagnostics.png"
adb shell input keyevent 4
adb shell input keyevent 4

echo "==> 最大缩放"
for i in $(seq 1 12); do
  tap_text "+" >/dev/null 2>&1 || true
  sleep 0.2
done
sleep 2
adb exec-out screencap -p > "$ROOT/docs/screenshots/app_max_zoom.png"

echo "==> 检查崩溃日志"
if adb logcat -d -s AndroidRuntime:E | grep -q "$PKG"; then
  adb logcat -d -s AndroidRuntime:E | tail -40
  echo "检测到崩溃" >&2
  exit 1
fi
echo "未发现崩溃"

echo
echo "完成。截图：$ROOT/docs/screenshots/"
ls -lh "$ROOT/docs/screenshots/"
if [ "$KEEP" != "1" ]; then
  echo "关闭模拟器（KEEP=1 可保留）"
  adb emu kill >/dev/null 2>&1 || true
fi
