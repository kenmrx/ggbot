#!/bin/bash
# GGBot 多环境回归矩阵（模拟器侧）
# 用法: ./tools/matrix.sh [quick|full]
# 说明: 每一项都会改动系统状态，结束后统一复原；任何一项失败都会以非 0 退出。
set -u
ADB=${ADB:-$HOME/android-sdk/platform-tools/adb}
DEV=${DEV:-emulator-5554}
PKG=com.ggbot.app
MODE=${1:-quick}
PASS=0; FAIL=0; SKIP=0
say() { printf '%-46s %s\n' "$1" "$2"; }
ok() { PASS=$((PASS+1)); say "$1" "PASS"; }
bad() { FAIL=$((FAIL+1)); say "$1" "FAIL  $2"; }
skip() { SKIP=$((SKIP+1)); say "$1" "SKIP  $2"; }

wait_app() {
  local i=0
  while [ $i -lt 12 ]; do
    focused | grep -qE "$PKG/com\.ggbot\.app\.MainActivity" && return 0
    sleep 0.7; i=$((i+1))
  done
  return 1
}

sh_() { $ADB -s $DEV shell "$@"; }
dump() { sh_ uiautomator dump /sdcard/mx.xml >/dev/null 2>&1; sh_ cat /sdcard/mx.xml 2>/dev/null; }
has() { dump | grep -q "$1"; }
focused() { sh_ 'dumpsys window | grep -m1 mCurrentFocus'; }

restore() {
  sh_ settings put system font_scale 1.0
  sh_ wm density reset
  sh_ settings put system accelerometer_rotation 1
  sh_ settings put system user_rotation 0
  sh_ cmd connectivity airplane-mode disable >/dev/null 2>&1
  sh_ cmd deviceidle unforce >/dev/null 2>&1
  sh_ pm grant $PKG android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
  sh_ pm enable --user 0 io.github.sayach.dshmobile >/dev/null 2>&1
}
trap restore EXIT

# 模拟器上有个第三方应用会自己跳到前台（实测抢过两次焦点，导致 dump 到的是别的界面）
# 它不属于本次被测对象，测试前统一压住，免得把「测到别人」当成应用 bug。
NOISE=io.github.sayach.dshmobile   # 见 testify: 它会自己抢前台
sh_ pm disable-user --user 0 io.github.sayach.dshmobile >/dev/null 2>&1


# ---- 0. 前置：被测包必须是启用状态（上一轮误禁过一次，这里加保护）
sh_ pm enable com.ggbot.app >/dev/null 2>&1
sh_ pm grant com.ggbot.app android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
# ---- 0. 前置：应用在跑，首页可见
$ADB -s $DEV shell am force-stop $PKG >/dev/null 2>&1
sh_ am start -n $PKG/.MainActivity >/dev/null 2>&1
wait_app || sleep 3
focused | grep -qE "$PKG/com\\.ggbot\\.app\\.MainActivity" && ok "启动: 首页在前台" || bad "启动: 首页在前台" "$(focused)"

# ---- 1. 字体放大 1.3 / 2.0：首页与设置页不裁切、关键行仍可见
for FS in 1.3 2.0; do
  sh_ settings put system font_scale $FS; sleep 2
  sh_ am force-stop $PKG >/dev/null 2>&1; sh_ am start -n $PKG/.MainActivity >/dev/null 2>&1; wait_app || sleep 3
  if dump | grep -q "GGBot"; then ok "字体 $FS: 首页渲染"; else bad "字体 $FS: 首页渲染" "空白"; fi
  ./tools/tabtap.sh 设置 >/dev/null 2>&1; sleep 2
  if has "设置"; then ok "字体 $FS: 设置页可达"; else bad "字体 $FS: 设置页可达" "未进入"; fi
  sh_ input keyevent 4; sleep 1
done
sh_ settings put system font_scale 1.0; sleep 2

# ---- 2. 多分辨率（窄 320dpi / 宽 560dpi）：热区仍 >= 48dp，页面不崩
for D in 320 560; do
  sh_ wm density $D; sleep 2
  sh_ am force-stop $PKG >/dev/null 2>&1; sh_ am start -n $PKG/.MainActivity >/dev/null 2>&1; wait_app || sleep 3
  dump > /tmp/mx_d$D.xml 2>/dev/null
  if python3 tools/dpcheck.py /tmp/mx_d$D.xml $D 首页 --min 44; then
    ok "密度 $D: 底部标签热区 >= 44dp"
  else
    bad "密度 $D: 底部标签热区" "$(python3 tools/dpcheck.py /tmp/mx_d$D.xml $D 首页 --min 44 2>&1 | tail -1)"
  fi
done
sh_ wm density reset; sleep 2

# ---- 3. 旋转：横屏不崩，界面仍可操作
sh_ am force-stop io.github.sayach.dshmobile >/dev/null 2>&1
sh_ settings put system accelerometer_rotation 0
sh_ settings put system user_rotation 1; sleep 3
sh_ am force-stop $PKG >/dev/null 2>&1; sh_ am start -n $PKG/.MainActivity >/dev/null 2>&1; wait_app || sleep 3
sh_ am force-stop $PKG >/dev/null 2>&1; sh_ am start -n $PKG/.MainActivity >/dev/null 2>&1; wait_app || sleep 3
focused | grep -q "$PKG" && ok "横屏: 应用存活" || bad "横屏: 应用存活" "$(focused)"
dump | grep -q "GGBot" && ok "横屏: 内容渲染" || bad "横屏: 内容渲染" "空白"
sh_ settings put system user_rotation 0; sleep 2

# ---- 4. 断网（飞行模式）：DNS 服务不崩，UI 有明确说法
sh_ am start-foreground-service -n $PKG/.DnsVpnService -a $PKG.action.START >/dev/null 2>&1
sleep 4
sh_ cmd connectivity airplane-mode enable >/dev/null 2>&1; sleep 4
sh_ dumpsys activity services $PKG | grep -q "DnsVpnService" && ok "断网: 服务仍在" || bad "断网: 服务仍在" "服务消失"
sh_ ping -c1 -W2 example.com >/dev/null 2>&1 && bad "断网: 外网确实不通" "仍可 ping" || ok "断网: 外网确实不通"
sh_ cmd connectivity airplane-mode disable >/dev/null 2>&1; sleep 3
sh_ ping -c1 -W2 example.com >/dev/null 2>&1 && ok "恢复: 网络已通" || skip "恢复: 网络已通" "模拟器无外网"

# ---- 5. Doze（浅度强制空闲）：前台服务不被杀、DNS 仍应答
if [ "$MODE" = "full" ]; then
  sh_ dumpsys deviceidle force-idle >/dev/null 2>&1; sleep 5
  sh_ dumpsys activity services $PKG | grep -q "DnsVpnService" && ok "Doze: 服务存活" || bad "Doze: 服务存活" "被停"
  sh_ cmd deviceidle unforce >/dev/null 2>&1; sleep 2
else
  skip "Doze: 服务存活" "quick 模式不跑"
fi

# ---- 6. 通知权限被撤回：只打扰一次，后续冷启动不再弹系统框
sh_ pm revoke $PKG android.permission.POST_NOTIFICATIONS >/dev/null 2>&1; sleep 1
sh_ am force-stop $PKG >/dev/null 2>&1; sh_ am start -n $PKG/.MainActivity >/dev/null 2>&1; wait_app || sleep 3
# 第一次：可以出现系统权限框，但不能崩
if focused | grep -qE "com\.google\.android\.permissioncontroller|$PKG/com\.ggbot\.app\.MainActivity"; then
  ok "无通知权限: 首次启动不崩"
else
  bad "无通知权限: 首次启动不崩" "$(focused)"
fi
# 第二次起：不应再弹
sh_ am force-stop $PKG >/dev/null 2>&1; sh_ am start -n $PKG/.MainActivity >/dev/null 2>&1; wait_app || sleep 3
if focused | grep -qE "$PKG/com\.ggbot\.app\.MainActivity"; then
  ok "无通知权限: 后续启动不再弹框"
else
  bad "无通知权限: 后续启动不再弹框" "$(focused)"
fi
sh_ pm grant $PKG android.permission.POST_NOTIFICATIONS >/dev/null 2>&1

# ---- 7. 旋转后的状态保持与可操作
sh_ am force-stop io.github.sayach.dshmobile >/dev/null 2>&1
sh_ settings put system accelerometer_rotation 0
sh_ settings put system user_rotation 1; sleep 3
sh_ am force-stop $PKG >/dev/null 2>&1; sh_ am start -n $PKG/.MainActivity >/dev/null 2>&1; wait_app || sleep 3
miss=""; tries=0
while [ $tries -lt 4 ]; do
  miss=""
  D=$(dump)
  for L in 首页 规则 日志 设置; do
    echo "$D" | grep -q "text="$L"" || miss="$miss $L"
  done
  [ -z "$miss" ] && break
  tries=$((tries+1)); sleep 2
done
# 同时把实际屏幕方向记下来，避免「没转成横屏却宣称测了横屏」
ORI=$(echo "$D" | grep -o 'bounds="\[0,0\]\[[0-9]*,[0-9]*\]"' | head -1)
echo "     (根节点 $ORI，重试 $tries 次)"
sh_ settings put system user_rotation 1 >/dev/null
[ -z "$miss" ] && ok "横屏: 四个标签完整" || bad "横屏: 四个标签完整" "缺:$miss"
./tools/tabtap.sh 日志 >/dev/null 2>&1
sleep 2
dump | grep -q '最近查询' && ok "横屏: 能进日志页" || bad "横屏: 能进日志页" "未切换"
sh_ settings put system user_rotation 0; sleep 3
dump | grep -q '最近查询' && ok "转回竖屏: 仍停在日志页" || bad "转回竖屏: 仍停在日志页" "状态丢失"
./tools/tabtap.sh 首页 >/dev/null 2>&1; sleep 1

echo "----"
echo "矩阵结果: PASS=$PASS FAIL=$FAIL SKIP=$SKIP"
[ "$FAIL" -eq 0 ] || exit 1