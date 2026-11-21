#!/usr/bin/env bash
# 厦门 PIDS 到站行的列几何：离线断言 + 中英双语整屏预览（不开游戏、不进 Gradle）。
#
#   ./tools/pids-columns/check.sh              # 断言 + 预览，输出到 v1.20.1/temp/
#   ./tools/pids-columns/check.sh D:/out       # 输出到指定目录
#
# 为什么能离线跑：PidsXiamenLayout / PidsXiamenColumns / TextFit 全是纯 Java（零 MC 依赖），
# 断言量的就是游戏里那套常量与算法，所以「这里过了」=「实机画出来也是这样」。
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
BUILDW="$(win "$OUT/pids-columns-classes")"

mkdir -p "$OUT/pids-columns-classes"
"$JAVAC" -encoding UTF-8 -d "$BUILDW" \
    "$MODW/src/main/java/dev/js/mtrheadlight/xiamen/XiamenStripPainter.java" \
    "$MODW/src/main/java/dev/js/mtrheadlight/xiamen/pids/PsdLcdTypeface.java" \
    "$MODW/src/main/java/dev/js/mtrheadlight/xiamen/pids/TextFit.java" \
    "$MODW/src/main/java/dev/js/mtrheadlight/xiamen/pids/PidsXiamenColumns.java" \
    "$MODW/src/main/java/dev/js/mtrheadlight/xiamen/pids/PidsXiamenLayout.java" \
    "$MODW/tools/pids-columns/PidsColumnsCheck.java" \
    "$MODW/tools/pids-columns/PidsColumnsPreview.java"

"$JAVA" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
    -Djava.awt.headless=true -cp "$BUILDW" \
    dev.js.mtrheadlight.xiamen.pids.PidsColumnsCheck | tee "$OUT/pids-columns-report.txt"

"$JAVA" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
    -Djava.awt.headless=true -cp "$BUILDW" \
    dev.js.mtrheadlight.xiamen.pids.PidsColumnsPreview "$MODW" "$OUTW" \
    | tee -a "$OUT/pids-columns-report.txt"
