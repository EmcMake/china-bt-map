#!/usr/bin/env bash
# 一次性下载构建所需的 JDK / Android SDK 工具链（全部放在工作区 toolchain/ 内，不污染系统）
# 用法:
#   ./setup.sh              # 仅构建工具链（约 400MB）
#   ./setup.sh --emulator   # 额外安装模拟器 + ARM64 系统镜像（约 +1.5GB）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
TC="$ROOT/toolchain"
JDK_DIR="$TC/jdk-17"
SDK="$TC/android-sdk"
mkdir -p "$TC"

WANT_EMULATOR=0
for a in "$@"; do
  case "$a" in
    --emulator) WANT_EMULATOR=1 ;;
    *) echo "未知参数: $a" >&2; exit 2 ;;
  esac
done

if [ "$(uname -m)" != "arm64" ]; then
  echo "此脚本为 Apple Silicon (arm64) 编写；当前架构 $(uname -m)，请手动安装 JDK17 与 Android SDK 后修改 build.sh 中的路径。" >&2
  exit 1
fi

need() { command -v "$1" >/dev/null 2>&1 || { echo "缺少命令: $1" >&2; exit 1; }; }
need curl
need unzip
need tar

# ---------- JDK 17 ----------
if [ ! -x "$JDK_DIR/bin/javac" ]; then
  echo "==> 下载 JDK 17 (arm64)"
  rm -rf "$JDK_DIR.part"
  mkdir -p "$JDK_DIR.part"
  # Adoptium 走 GitHub，国内很慢；优先 AWS Corretto，失败再退 Azul
  JDK_URLS=(
    "https://corretto.aws/downloads/latest/amazon-corretto-17-aarch64-macos-jdk.tar.gz"
    "https://cdn.azul.com/zulu/bin/zulu17.56.15-ca-jdk17.0.14-macosx_aarch64.tar.gz"
  )
  ok=0
  for url in "${JDK_URLS[@]}"; do
    echo "    $url"
    if curl -fsSL --retry 2 --retry-delay 2 --connect-timeout 15 -o "$TC/jdk17.tar.gz" "$url"; then
      # 注意：不能把 tar 直接管到 head，否则 pipefail 下 SIGPIPE 会误判失败
      if tar -tzf "$TC/jdk17.tar.gz" > "$TC/jdk-list.txt" 2>/dev/null \
          && grep -q "/Contents/Home/" "$TC/jdk-list.txt"; then
        tar -xzf "$TC/jdk17.tar.gz" -C "$JDK_DIR.part" --strip-components=3
      else
        tar -xzf "$TC/jdk17.tar.gz" -C "$JDK_DIR.part" --strip-components=1
      fi
      rm -f "$TC/jdk-list.txt"
      if [ -x "$JDK_DIR.part/bin/javac" ]; then
        ok=1
        break
      fi
    fi
    rm -rf "$JDK_DIR.part"
    mkdir -p "$JDK_DIR.part"
  done
  if [ "$ok" != "1" ]; then
    echo "JDK 下载失败。请手动下载 JDK 17 (macOS arm64) 并解压到: $JDK_DIR" >&2
    exit 1
  fi
  rm -rf "$JDK_DIR"
  mv "$JDK_DIR.part" "$JDK_DIR"
  rm -f "$TC/jdk17.tar.gz"
else
  echo "==> JDK 17 已存在"
fi
export JAVA_HOME="$JDK_DIR"
export PATH="$JAVA_HOME/bin:$PATH"
java -version

# ---------- Android command line tools ----------
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  echo "==> 下载 Android command-line tools"
  curl -fsSL --retry 3 --retry-delay 2 -o "$TC/cmdline-tools.zip" \
    "https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip"
  rm -rf "$SDK/cmdline-tools"
  mkdir -p "$SDK/cmdline-tools"
  unzip -q "$TC/cmdline-tools.zip" -d "$SDK/cmdline-tools"
  mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -f "$TC/cmdline-tools.zip"
else
  echo "==> command-line tools 已存在"
fi

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
# 让 sdkmanager / emulator / adb 把配置写在工程内，避免依赖 $HOME 可写
export ANDROID_USER_HOME="$ROOT/.android"
export ANDROID_AVD_HOME="$ROOT/.android/avd"
mkdir -p "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME"

echo "==> 接受 SDK license"
yes 2>/dev/null | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null || true

PKGS=(
  "platform-tools"
  "platforms;android-34"
  "build-tools;34.0.0"
)
if [ "$WANT_EMULATOR" = "1" ]; then
  PKGS+=("emulator" "system-images;android-34;google_apis;arm64-v8a")
fi

echo "==> 安装 SDK 组件: ${PKGS[*]}"
"$SDKMANAGER" --sdk_root="$SDK" "${PKGS[@]}"

echo
echo "==> 完成，校验:"
"$SDK/build-tools/34.0.0/aapt2" version
"$SDK/build-tools/34.0.0/d8" --version
ls -1 "$SDK/platforms"
if [ "$WANT_EMULATOR" = "1" ]; then
  "$SDK/emulator/emulator" -version | head -3
fi
