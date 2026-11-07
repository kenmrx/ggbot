#!/bin/zsh
# 安装到手机：手机需开启「无线调试」或用 USB 连接
set -e
cd "$(dirname "$0")"
DEV=${1:-${GGBOT_ADB_TARGET:-}}

if [ -z "$DEV" ] && ! adb devices | grep -q "device$"; then
  echo "用法：./install.sh [设备序列号]  或设置 GGBOT_ADB_TARGET；也可先用 adb connect 连接设备"
  exit 1
fi

if ! adb devices | grep -q "device$"; then
  echo "== 尝试连接 $DEV =="
  adb connect "$DEV" >/dev/null 2>&1 || true
  sleep 2
fi

adb devices | tail -5
if ! adb devices | grep -q "device$"; then
  echo "没有可用设备：确认手机和电脑在同一 WiFi，且在开发者选项里打开了无线调试"
  exit 1
fi

echo "== 安装 =="
adb install -r -g GGBot.apk

echo "== 启动 =="
adb shell am start -n com.ggbot.app/.MainActivity
