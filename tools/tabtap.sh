#!/bin/bash
# 点底部标签：只在当前屏幕里找（不滚动），并取最靠下的匹配。
# 与 tap.sh 分开，是因为标签永远在屏幕底部，一滚动就可能把内容带跑、把测试弄假。
set -u
ADB=${ADB:-$HOME/android-sdk/platform-tools/adb}
DEV=${DEV:-emulator-5554}
WANT=${1:?用法: tabtap.sh <标签文本>}
$ADB -s $DEV shell uiautomator dump /sdcard/tb.xml >/dev/null 2>&1
XY=$($ADB -s $DEV shell cat /sdcard/tb.xml 2>/dev/null | WANT="$WANT" python3 -c '
import os,re,sys
want=os.environ["WANT"]; raw=sys.stdin.read(); best=None
for m in re.finditer(r"text=\"([^\"]*)\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", raw):
    t=m.group(1); x1,y1,x2,y2=map(int,m.groups()[1:])
    if t.strip()!=want: continue
    if best is None or y2>best[3]: best=(x1,y1,x2,y2)
print("NOTFOUND" if best is None else "%d %d"%(((best[0]+best[2])//2),((best[1]+best[3])//2)))
')
if [ "$XY" = "NOTFOUND" ] || [ -z "$XY" ]; then echo "tabtap: 当前屏没有 $WANT"; exit 2; fi
set -- $XY
$ADB -s $DEV shell input tap $1 $2
