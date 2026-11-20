#!/usr/bin/env bash
# 厦门 PIDS 版式预览：离线把「地铁 6 节 / 地铁 2 节 / BRT」画成 PNG（不开游戏、不进 Gradle）。
#
#   ./tools/pids-xiamen/preview.sh            # 输出到 v1.20.1/temp/
#   ./tools/pids-xiamen/preview.sh /tmp/out   # 输出到指定目录
#
# 为什么能离线跑：整屏现在是 PidsXiamenPainter（纯 Java2D，不 import 任何 net.minecraft / mtr），
# 和游戏里渲染器调用的是**同一份**代码 —— 预览对得上就等于游戏里对得上。
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
BUILDW="$(win "$OUT/pids-preview-classes")"

mkdir -p "$OUT/pids-preview-classes"
"$JAVAC" -encoding UTF-8 -d "$BUILDW" \
    "$MODW/src/main/java/dev/js/mtrheadlight/xiamen/XiamenStripPainter.java" \
    "$MODW/src/main/java/dev/js/mtrheadlight/xiamen/pids/PidsXiamenPainter.java" \
    "$MODW/tools/pids-xiamen/XiamenPidsPreview.java"

"$JAVA" -Dfile.encoding=UTF-8 -Djava.awt.headless=true -cp "$BUILDW" \
    dev.js.mtrheadlight.xiamen.pids.XiamenPidsPreview "$MODW" "$OUTW"

echo
echo "预览："
ls -1 "$OUT"/pids_xiamen_*.png
