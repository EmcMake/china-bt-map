#!/usr/bin/env bash
# 把本工程推到 GitHub 组织（默认 EmcMake），并把 APK 作为 Release 附件发布。
# 前置：gh auth login -h github.com  （或设置 GH_TOKEN）
# 用法：./push_to_github.sh [ORG] [REPO] [public|private]
set -euo pipefail
cd "$(dirname "$0")"

ORG="${1:-EmcMake}"
REPO="${2:-china-bt-map}"
VIS="${3:-public}"
VERSION="${4:-1.3.4}"
APK="dist/china-bt-map-$VERSION.apk"

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "当前目录不是 git 仓库" >&2
  exit 1
fi

if ! gh auth status -h github.com >/dev/null 2>&1; then
  cat >&2 <<'EOF'
GitHub 未登录或 token 已失效，请先执行（浏览器授权）：

    gh auth login -h github.com

如果你没有权限在 ORG 下建仓库，也可以先在网页上建一个空仓库，然后只推送：

    git remote add origin git@github.com:ORG/REPO.git
    git push -u origin main
EOF
  exit 1
fi

if gh repo view "$ORG/$REPO" >/dev/null 2>&1; then
  echo "==> 仓库已存在，直接推送"
  if ! git remote get-url origin >/dev/null 2>&1; then
    git remote add origin "git@github.com:$ORG/$REPO.git"
  fi
  git push -u origin main
else
  echo "==> 创建 $ORG/$REPO ($VIS) 并推送"
  gh repo create "$ORG/$REPO" "--$VIS" --source=. --remote=origin --push
fi

if [ -f "$APK" ]; then
  if ! gh release view "v$VERSION" -R "$ORG/$REPO" >/dev/null 2>&1; then
    echo "==> 创建 Release v$VERSION 并上传 APK"
    gh release create "v$VERSION" "$APK#china-bt-map-$VERSION.apk" -R "$ORG/$REPO" \
      --title "v$VERSION 中国地图蓝牙轨迹" \
      --notes "离线中国地图 + 蓝牙坐标轨迹。下载下方 APK 直接安装（Android 7.0+），无需修改任何东西。"
  else
    echo "==> Release v$VERSION 已存在，跳过"
  fi
fi

echo
echo "仓库: https://github.com/$ORG/$REPO"
[ -f "$APK" ] && echo "APK : https://github.com/$ORG/$REPO/releases/download/v$VERSION/china-bt-map-$VERSION.apk"
