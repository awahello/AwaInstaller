#!/usr/bin/env bash
# Awa的安装助手 —— 编译流水线
#
#   javac(源码) -> d8(class -> classes.dex) -> build_apk.py(装 APK + v1 签名)
#
# 依赖：
#   · JDK 17（javac + java 跑 d8）
#   · r8.jar（含 com.android.tools.r8.D8）
#   · android-all-22.jar（Android 5.1 编译期库）
#   · python3 + cryptography（打包签名）
#
# 前三样都不在仓库里（太大），装好把路径填到下面即可。
set -e

cd "$(dirname "$0")"

# ---- 环境路径，按你的机器改 ----
JDK="${JDK:-$HOME/jdk-17.0.2}"                 # JDK 根目录
ANDROID_JAR="${ANDROID_JAR:-$HOME/android-all-22.jar}"
R8_JAR="${R8_JAR:-$HOME/r8.jar}"
PY="${PYTHON:-python3}"

JAVAC="$JDK/bin/javac"
JAVA="$JDK/bin/java"

[ -x "$JAVAC" ] || { echo "找不到 javac：$JAVAC"; exit 1; }
[ -f "$ANDROID_JAR" ] || { echo "找不到 $ANDROID_JAR"; exit 1; }
[ -f "$R8_JAR" ] || { echo "找不到 $R8_JAR"; exit 1; }

echo "=== 1/3 javac ==="
rm -rf out out_dex
mkdir -p out out_dex

# 注意：必须用 -classpath，不能用 -bootclasspath。
# android-all-22.jar 里只有 android/* 和部分 java/security/*，没有 java/lang/*，
# 用 -bootclasspath 会直接报 "Unable to find package java.lang"。
"$JAVAC" -nowarn -Xlint:all \
    -source 8 -target 8 \
    -classpath "$ANDROID_JAR" \
    -encoding UTF-8 \
    -d out \
    src/com/awa/apkinstaller/*.java

echo "class 文件数: $(find out -name '*.class' | wc -l)"

echo "=== 2/3 d8 ==="
find out -name '*.class' > /tmp/awa_classes.txt
# d8 那句 "Type java.lang.Runnable was not found" 是警告不是错误 ——
# --lib 只给了 android.jar，没有 JDK 的 java.lang，desugar 用不上。
"$JAVA" -cp "$R8_JAR" com.android.tools.r8.D8 \
    --lib "$ANDROID_JAR" --min-api 22 --release \
    --output out_dex \
    $(cat /tmp/awa_classes.txt | tr '\n' ' ') 2>&1 | grep -vi "runnable" || true

echo "classes.dex: $(stat -c%s out_dex/classes.dex) 字节"

echo "=== 3/3 打包签名 ==="
"$PY" build_apk.py

echo
echo "=== 完成 ==="
ls -l dist/
