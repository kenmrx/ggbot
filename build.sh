#!/bin/zsh
# GGBot 构建脚本：aapt2 -> javac(--release 8) -> R8 D8 -> zip -> zipalign -> apksigner
set -e
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"$HOME/android-sdk"}}"
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar
# 第三方依赖放 libs（目前只有 Shizuku API，见 libs/README）
LIBS=$(find libs -name "*.jar" 2>/dev/null | tr '\n' ':')
OUT=GGBot.apk
cd "$(dirname "$0")"

rm -rf build && mkdir -p build/gen build/classes build/dex

echo "== 1/6 编译资源 =="
$BT/aapt2 compile --dir res -o build/res.zip

echo "== 2/6 链接资源 + R.java =="
$BT/aapt2 link -o build/base.apk \
  -I "$JAR" \
  --manifest AndroidManifest.xml \
  --auto-add-overlay \
  build/res.zip \
  -A assets \
  --java build/gen \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 44 --version-name 1.9.3

echo "== 3/6 javac =="
find src build/gen -name "*.java" > build/sources.txt
javac -nowarn --release 8 -classpath "$JAR:${LIBS}" -d build/classes @build/sources.txt

echo "== 4/6 dex (R8 9.5.28) =="
# Shizuku 的 classes.jar 要一起进 dex，否则运行时 NoClassDefFound
java -cp tools/r8.jar com.android.tools.r8.D8 --lib "$JAR" --min-api 24 --output build/dex \
  $(find build/classes -name "*.class") \
  $(find libs -name "*.jar" 2>/dev/null)
test -f build/dex/classes.dex || { echo "dex 生成失败"; exit 1; }

echo "== 5/6 打包 =="
cd build/dex && zip -q ../base.apk classes.dex && cd ../..
$BT/zipalign -f -p 4 build/base.apk build/aligned.apk

echo "== 6/6 签名 =="
# 口令必须通过环境变量 GGBOT_KS_PASS 提供，仓库内不落任何默认口令。
# 无 keystore 时要求显式传入；生产密钥与口令一律不进仓库。
if [ -z "${GGBOT_KS_PASS:-}" ]; then
  echo "错误：请通过环境变量 GGBOT_KS_PASS 提供签名口令" >&2
  exit 1
fi
KS_PASS="$GGBOT_KS_PASS"
if [ ! -f ggbot.keystore ]; then
  echo "未找到 ggbot.keystore，自动生成调试密钥（仅限本地调试，勿用于发布）"
  keytool -genkeypair -keystore ggbot.keystore -alias ggbot \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" -dname "CN=GGBot,O=Local,C=CN" 2>/dev/null
fi
$BT/apksigner sign --ks ggbot.keystore --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "$OUT" build/aligned.apk
$BT/apksigner verify --print-certs "$OUT" | head -3

echo "== 产物 =="
ls -lh "$OUT"
