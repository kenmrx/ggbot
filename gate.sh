#!/bin/zsh
# 门闸：编译全部源码 → 跑完所有桌面测试类 → 全绿才允许出包。
#
# 为什么要单独一道门闸：这些测试里有一半是在防「静默失效」——
# 判定顺序错了、TTL 少夹一次、索引漏一条规则，App 都照常运行、界面也正常，
# 只是每天多放过去一批广告。跑一遍只要几秒，比事后到手机上猜便宜太多。
set -e
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"$HOME/android-sdk"}}"
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar
LIBS=$(find libs -name "*.jar" 2>/dev/null | tr '\n' ':')
cd "$(dirname "$0")"

if [ ! -f build/gen/com/ggbot/app/R.java ]; then
  echo "== 生成 R.java =="
  mkdir -p build/gen build/gate-res
  $BT/aapt2 compile --dir res -o build/gate-res/res.zip
  $BT/aapt2 link -o build/gate-res/base.apk \
    -I "$JAR" --manifest AndroidManifest.xml --auto-add-overlay \
    build/gate-res/res.zip -A assets --java build/gen \
    --min-sdk-version 24 --target-sdk-version 34 \
    --version-code 1 --version-name 1.0.0
fi

rm -rf build/gate && mkdir -p build/gate/app build/gate/test
find src -name "*.java" > build/gate/sources.txt
echo build/gen/com/ggbot/app/R.java >> build/gate/sources.txt

echo "== 编译源码 =="
javac -nowarn --release 8 -classpath "$JAR:${LIBS}" -d build/gate/app @build/gate/sources.txt

echo "== 编译测试 =="
javac -nowarn --release 8 -classpath "$JAR:${LIBS}build/gate/app" -d build/gate/test test/*.java

fail=0
total=0
for t in CoreTest TcpTest TcpStateTest HttpTest EngineTest FeedTest AdvTest RouteTest DnsBlockTest QueryLogTest RewriteTest HardTest RespGateFeedTest RespGateEdgeTest DoHTest TunGuardTest A11yTest OpsTest IntegrityTest SkipMatcherTest; do
  echo
  echo "──── $t ────"
  if java -classpath "$JAR:${LIBS}build/gate/app:build/gate/test" "$t" > build/gate/$t.log 2>&1; then
    tail -1 build/gate/$t.log
  else
    echo "!! $t 失败，明细："
    grep -E "FAIL|Exception|错误" build/gate/$t.log | head -20
    fail=1
  fi
  n=$(grep -cE "PASS|ok  " build/gate/$t.log || true)
  # 有的测试类只在末尾打「通过 N 项」，没有逐条 PASS 行，按末行计数兜底
  if [ "$n" -eq 0 ]; then
    m=$(grep -oE "通过 [0-9]+ 项" build/gate/$t.log | grep -oE "[0-9]+" | head -1)
    [ -n "$m" ] && n=$m
  fi
  total=$((total + n))
done

echo
if [ $fail -ne 0 ]; then
  echo "门闸未通过，不出包。"
  exit 1
fi
echo "门闸通过：20 个测试类共 $total 项断言全绿。"
