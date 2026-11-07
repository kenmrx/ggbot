#!/bin/bash
# 按文本点击：先从 uiautomator dump 找节点中心，找不到就滚动再找。
# 为什么不用固定坐标：密度、字体、横竖屏都会改坐标，写死坐标的"通过"是假通过。
set -u
ADB=${ADB:-$HOME/android-sdk/platform-tools/adb}
DEV=${DEV:-emulator-5554}
WANT=${1:?用法: tap.sh <文本>}
find_xy() {
  $ADB -s $DEV shell uiautomator dump /sdcard/tp.xml >/dev/null 2>&1
  $ADB -s $DEV shell cat /sdcard/tp.xml 2>/dev/null | WANT="$1" python3 -c '
import os,re,sys
want=os.environ["WANT"]
raw=sys.stdin.read()
best=None
for m in re.finditer(r"text=\"([^\"]*)\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", raw):
    t=m.group(1)
    if want not in t: continue
    x1,y1,x2,y2=map(int,m.groups()[1:])
    if best is None or (y2-y1)>(best[3]-best[1]): best=(x1,y1,x2,y2)
print("NOTFOUND" if best is None else "%d %d"%(((best[0]+best[2])//2),((best[1]+best[3])//2)))
'
}
for i in 0 1 2 3 4 5; do
  XY=$(find_xy "$WANT")
  if [ "$XY" != "NOTFOUND" ] && [ -n "$XY" ]; then
    set -- $XY
    $ADB -s $DEV shell input tap $1 $2
    exit 0
  fi
  $ADB -s $DEV shell input swipe 540 1800 540 700 250 >/dev/null 2>&1
  sleep 0.3
done
echo "tap.sh: 找不到 $WANT"
exit 2
