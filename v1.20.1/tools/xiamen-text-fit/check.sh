#!/usr/bin/env bash
# 长站名字号反解 + 「已到站」的离线断言（不开游戏、不进 Gradle）。
#
#   ./tools/xiamen-text-fit/check.sh                    # 断言 v1.20.1
#   ./tools/xiamen-text-fit/check.sh /path/to/v1.20.1   # 断言别的副本
#
# 为什么能离线跑：TextFit / ArrivalCountdown / PsdLcdPainter / PidsXiamenLayout 全是纯 Java
# （AWT 只用来量字），不 import 任何 net.minecraft / mtr 类；屏上画的就是这几个函数。
# PidsXiamenLayout 的签名里有一个 PidsXiamenBlock.Kind，所以编译时把那个枚举用一个桩补上
# （stub/ 下那一个文件，只参与这次 javac）。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
MOD="${1:-$(cd "$HERE/../.." && pwd)}"   # …/v1.20.1
OUT="${2:-$MOD/temp}"
JAVA_HOME="${JAVA_HOME:-C:/Program Files/Java/jdk-21.0.10}"
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"

# javac / java 是原生 Windows 程序：MSYS 风格路径（/d/...）不会被转换，必须给 C:/ 形式
win() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
MODW="$(win "$MOD")"
OUTW="$(win "$OUT")"
BUILDW="$(win "$OUT/xiamen-text-fit-classes")"

mkdir -p "$OUT/xiamen-text-fit-classes"
rm -rf "$OUT/xiamen-text-fit-classes"/*

# 桩必须在源码之前？javac 按内容解析，顺序无关；显式列出即可。
"$JAVAC" -encoding UTF-8 -d "$BUILDW" \
    "$(win "$HERE/stub/dev/js/mtrheadlight/xiamen/pids/PidsXiamenBlock.java")" \
    "$MODW/src/main/java/dev/js/mtrheadlight/xiamen/XiamenStripPainter.java" \
    "$MODW/src/main/java/dev/js/mtrheadlight/xiamen/pids/PsdLcdTypeface.java" \
    "$MODW/src/main/java/dev/js/mtrheadlight/xiamen/pids/PsdLcdLayout.java" \
    "$MODW/src/main/java/dev/js/mtrheadlight/xiamen/pids/PidsXiamenLayout.java" \
    "$MODW/src/main/java/dev/js/mtrheadlight/xiamen/pids/TextFit.java" \
    "$MODW/src/main/java/dev/js/mtrheadlight/xiamen/pids/PidsXiamenColumns.java" \
    "$MODW/src/main/java/dev/js/mtrheadlight/xiamen/pids/ArrivalCountdown.java" \
    "$MODW/src/main/java/dev/js/mtrheadlight/xiamen/pids/PsdLcdPainter.java" \
    "$MODW/tools/xiamen-text-fit/FitCheck.java"

"$JAVA" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Djava.awt.headless=true -cp "$BUILDW" \
    dev.js.mtrheadlight.xiamen.pids.FitCheck "$MODW" "$OUTW"
