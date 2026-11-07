#!/bin/zsh
# GGBot 回归测试脚本：编译 src+test（含生成的 R.java）后依次跑 9 套纯 JVM 自检。
# 不依赖 Android 设备：所有套件都是 main() 自检，失败以非 0 退出码暴露。
# 用法：./test.sh [套件名...]   不带参数=全跑
set -u
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"$HOME/android-sdk"}}"
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar
OUT=build/test
cd "$(dirname "$0")"

SUITES=(CoreTest DnsBlockTest RouteTest TcpTest EngineTest HttpTest FeedTest CertTest AdvTest)
[ $# -gt 0 ] && SUITES=("$@")

echo "== 1/3 生成 R.java =="
mkdir -p build/gen
if [ ! -f build/gen/com/ggbot/app/R.java ]; then
  $BT/aapt2 compile --dir res -o build/res.zip || exit 1
  $BT/aapt2 link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml \
    --auto-add-overlay build/res.zip -A assets --java build/gen \
    --min-sdk-version 24 --target-sdk-version 34 || exit 1
fi

echo "== 2/3 编译 src + test =="
rm -rf "$OUT" && mkdir -p "$OUT"
javac -nowarn --release 8 -classpath "$JAR" -d "$OUT" \
  $(find src test build/gen -name "*.java") || { echo "编译失败"; exit 1; }

echo "== 3/3 运行套件 =="
fail=0
for t in $SUITES; do
  # CoreTest 默认读 assets/blocklist.txt（相对 cwd），所以统一在项目根跑
  out=$(java -cp "$OUT" "$t" 2>&1)
  code=$?
  summary=$(echo "$out" | grep -E "通过|结果|PASS|失败" | tail -1 | sed 's/^ *//')
  if [ $code -eq 0 ]; then
    printf "  %-14s OK   %s\n" "$t" "${summary:0:70}"
  else
    printf "  %-14s FAIL %s\n" "$t" "${summary:0:70}"
    echo "$out" | tail -12 | sed 's/^/      /'
    fail=$((fail + 1))
  fi
done

echo
if [ $fail -eq 0 ]; then
  echo "全部套件通过"
else
  echo "有 $fail 个套件失败"
fi
exit $fail
