#!/usr/bin/env bash
# 模拟器端到端验证：创建 AVD → 安装 APK → 注入示例坐标 → 截图 → 检查崩溃
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
APK="$ROOT/dist/china-bt-map-1.0.apk"
KEEP="${KEEP:-0}"
[ -f "$APK" ] || { echo "缺少 $APK，请先 ./build.sh" >&2; exit 1; }

# 演示轨迹用 '/' 分隔，避免 adb shell 把分号当命令分隔符
DEMO="39.9042,116.4074/39.9142,116.4200/39.9250,116.4340/39.9330,116.4520/39.9350,116.4700/39.9280,116.4880/39.9150,116.5000/39.9000,116.5070/39.8850,116.5040/39.8720,116.4930/39.8630,116.4780/39.8580,116.4620/39.8590,116.4440/39.8660,116.4300/39.8760,116.4180/39.8880,116.4100/39.8990,116.4060/39.9042,116.4074"

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
  adb start-server >/dev/null 2>&1 || true
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

echo "==> 安装 APK"
adb install -r "$APK"

echo "==> 授予蓝牙权限（模拟器无真实蓝牙，只验证界面与解析链路）"
adb shell pm grant "$PKG" android.permission.BLUETOOTH_SCAN || true
adb shell pm grant "$PKG" android.permission.BLUETOOTH_CONNECT || true

echo "==> 启动 App 并注入坐标"
adb logcat -c || true
adb shell am start -n "$PKG/.MainActivity" --es demo_points "$DEMO" >/dev/null
sleep 10
adb exec-out screencap -p > "$ROOT/docs/screenshots/app_main.png"

echo "==> 双击放大后截图"
adb shell "input tap 540 900; input tap 540 900" >/dev/null
sleep 2
adb exec-out screencap -p > "$ROOT/docs/screenshots/app_zoom.png"

# 用 uiautomator 找到控件位置再点，避免写死分辨率坐标
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

if tap_text "连接设备"; then
  sleep 3
  adb exec-out screencap -p > "$ROOT/docs/screenshots/device_dialog.png"
fi

echo "==> 检查崩溃日志"
if adb logcat -d -s AndroidRuntime:E | grep -q "$PKG"; then
  adb logcat -d -s AndroidRuntime:E | tail -40
  echo "检测到崩溃" >&2
  exit 1
fi
echo "未发现崩溃"
adb shell dumpsys activity activities 2>/dev/null | grep -E "ResumedActivity" | head -2 || true

echo
echo "完成。截图：$ROOT/docs/screenshots/"
ls -lh "$ROOT/docs/screenshots/"
if [ "$KEEP" != "1" ]; then
  echo "关闭模拟器（KEEP=1 可保留）"
  adb emu kill >/dev/null 2>&1 || true
fi
