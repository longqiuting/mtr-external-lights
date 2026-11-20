#!/usr/bin/env bash
# 厦门 PIDS 编组条叠加层的离线预览（不开游戏、不进 Gradle）。
#
#   ./tools/pids-xiamen/preview.sh            # 输出到 v1.20.1/temp/
#   ./tools/pids-xiamen/preview.sh /tmp/out   # 输出到指定目录
#
# 为什么能离线跑：叠加层是 PidsXiamenConsist（纯 Java2D，只依赖 XiamenStripPainter 的字体
# 入口，不 import 任何 net.minecraft / mtr），游戏里渲染器调的就是同一个 paint()。
# 对得上就等于游戏里对得上。
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
BUILDW="$(win "$OUT/pids-consist-classes")"

mkdir -p "$OUT/pids-consist-classes"
"$JAVAC" -encoding UTF-8 -d "$BUILDW" \
    "$MODW/src/main/java/dev/js/mtrheadlight/xiamen/pids/PidsXiamenConsist.java" \
    "$MODW/tools/pids-xiamen/XiamenConsistPreview.java"

"$JAVA" -Dfile.encoding=UTF-8 -Djava.awt.headless=true -cp "$BUILDW" \
    dev.js.mtrheadlight.xiamen.pids.XiamenConsistPreview "$MODW" "$OUTW"
