#!/usr/bin/env bash
# PSD-LCD「按实际段数」的离线验证：1 / 2 / 3 段 × 4 / 6 节，共 6 张图 + 逐条断言。
#
#   ./tools/psd-lcd/preview-segments.sh            # 输出到 v1.20.1/temp/
#   ./tools/psd-lcd/preview-segments.sh D:/out     # 输出到指定目录
#
# 纯 AWT（PsdLcdPainter + PsdLcdLayout + TextFit），不 import 任何 net.minecraft / mtr 类，
# 所以不开游戏、不进 Gradle 就能验证「编组不重叠 / 广告不出区」。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
MOD="$(cd "$HERE/../.." && pwd)"          # …/v1.20.1
OUT="${1:-$MOD/temp}"
JAVA_HOME="${JAVA_HOME:-C:/Program Files/Java/jdk-21.0.10}"
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"

# javac / java 是原生 Windows 程序：MSYS 风格路径（/d/...）不会被转换，必须给 C:/ 形式
win() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
MODW="$(win "$MOD")"
OUTW="$(win "$OUT")"
BUILDW="$(win "$OUT/psd-lcd-seg-classes")"

mkdir -p "$OUT/psd-lcd-seg-classes"
"$JAVAC" -encoding UTF-8 -d "$BUILDW" \
    "$MODW/src/main/java/dev/js/mtrheadlight/xiamen/XiamenStripPainter.java" \
    "$MODW/src/main/java/dev/js/mtrheadlight/xiamen/pids/PsdLcdTypeface.java" \
    "$MODW/src/main/java/dev/js/mtrheadlight/xiamen/pids/TextFit.java" \
    "$MODW/src/main/java/dev/js/mtrheadlight/xiamen/pids/PidsXiamenColumns.java" \
    "$MODW/src/main/java/dev/js/mtrheadlight/xiamen/pids/PsdLcdLayout.java" \
    "$MODW/src/main/java/dev/js/mtrheadlight/xiamen/pids/PsdLcdPainter.java" \
    "$MODW/tools/psd-lcd/PsdLcdSegments.java"

"$JAVA" -Dfile.encoding=UTF-8 -Djava.awt.headless=true -cp "$BUILDW" \
    dev.js.mtrheadlight.xiamen.pids.PsdLcdSegments "$MODW" "$OUTW"
