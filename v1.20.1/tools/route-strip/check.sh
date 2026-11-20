#!/usr/bin/env bash
# 终点站站名锚点回归（多换乘回归）：离线跑，不开游戏、不进 Gradle。
#
#   bash tools/route-strip/check.sh              # 输出到 v1.20.1/temp/route-strip-terminal
#   bash tools/route-strip/check.sh D:/out       # 输出到指定目录
#
# 为什么能离线跑：XiamenStripPainter 是纯 AWT（不 import net.minecraft / mtr），
# 游戏里 PSD 吊板渲染器调的就是同一个 paint()。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
MOD="$(cd "$HERE/../.." && pwd)"          # …/v1.20.1
OUT="${1:-$MOD/temp/route-strip-terminal}"
JAVA_HOME="${JAVA_HOME:-C:/Program Files/Java/jdk-21.0.10}"
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"

# javac / java 是原生 Windows 程序：MSYS 风格路径（/d/...）不会被转换，必须给 C:/ 形式
win() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
MODW="$(win "$MOD")"
OUTW="$(win "$OUT")"
BUILDW="$(win "$OUT/classes")"

mkdir -p "$OUT/classes"
"$JAVAC" -encoding UTF-8 -d "$BUILDW" \
    "$MODW/src/main/java/dev/js/mtrheadlight/xiamen/XiamenStripPainter.java" \
    "$MODW/tools/route-strip/TerminalNameCheck.java"
"$JAVA" -Dfile.encoding=UTF-8 -cp "$BUILDW" dev.js.mtrheadlight.xiamen.TerminalNameCheck "$OUTW"
