#!/usr/bin/env bash
# Rebuild libs/MTR-fabric-1.20.1-3.6.4.2-named.jar — MTR remapped from intermediary to yarn names.
#
# Why: the shipped MTR jar is intermediary-named (net.minecraft.class_2248 …), so
# `class X extends mtr.block.BlockPSDAPGDoorBase` cannot compile against it: the compiler sees
# `Block` (named) and `class_2248` (intermediary) as two unrelated types. MTR itself ships as an
# Architectury *dev* jar that Loom's own remap refuses ("unfixable conflicts").
#
# Steps:
#   1. strip mtr/screen/WidgetShorterSlider — it declares both `method_48579` and `renderButton`
#      for the same descriptor, which is the only remap conflict in the jar and irrelevant to us;
#   2. tiny-remapper intermediary -> named with the *intermediary* MC jar as classpath, so the
#      hierarchy resolves and member mappings propagate into MTR's own subclasses (otherwise
#      overrides keep names like `method_9559` and can't be @Override'n).
#
# Usage: tools/remap-mtr-named.sh [mtr.jar] [out.jar] [mcver]
#   mcver defaults to 1.20.1 (the original hardcoded target). Pass e.g. 1.19.4 to produce the
#   1.19.4 named jar the v1.19.4 source set needs for `extends mtr.block.BlockPSDAPGDoorBase`.
set -euo pipefail

JAVA="${JAVA_HOME:-C:/Program Files/Java/jdk-21.0.10}/bin/java.exe"
JAR="${JAVA_HOME:-C:/Program Files/Java/jdk-21.0.10}/bin/jar.exe"
GRADLE_MODS="C:/Users/AD/.gradle/caches/modules-2/files-2.1"
LOOM="C:/Users/AD/.gradle/caches/fabric-loom"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -W 2>/dev/null || pwd)"
MCVER="${3:-1.20.1}"

# Per-version Loom artifacts: the yarn build suffix and merged-intermediary path both carry the
# yarn version, so derive them from the Loom cache rather than hardcoding one MC version.
case "$MCVER" in
    1.18.2) YARN="1.18.2+build.4"; YARN_DIR="1_18_2" ;;
    1.19.2) YARN="1.19.2+build.28"; YARN_DIR="1_19_2" ;;
    1.19.4) YARN="1.19.4+build.2"; YARN_DIR="1_19_4" ;;
    1.20.1) YARN="1.20.1+build.10"; YARN_DIR="1_20_1" ;;
    *) echo "unknown mcver $MCVER" >&2; exit 1 ;;
esac
MCJAR="$MCVER-net.fabricmc.yarn.${YARN_DIR}.${YARN}-v2"

SRC="${1:-$ROOT/libs/MTR-fabric-1.20.1-3.6.4.2.jar}"
OUT="${2:-$ROOT/libs/MTR-fabric-1.20.1-3.6.4.2-named.jar}"

TR="$GRADLE_MODS/net.fabricmc/tiny-remapper/0.11.1/a3eb511096a6f9af615809f03c3569a1a56c381/tiny-remapper-0.11.1.jar"
ASM="$GRADLE_MODS/org.ow2.asm/asm/9.8/dc19ecb3f7889b7860697215cae99c0f9b6f6b4b/asm-9.8.jar"
ASM_A="$GRADLE_MODS/org.ow2.asm/asm-analysis/9.8/b9747a320844b6cb1eacd90d8ecfd260a16c01d3/asm-analysis-9.8.jar"
ASM_C="$GRADLE_MODS/org.ow2.asm/asm-commons/9.8/36e4d212970388e5bd2c5180292012502df461bb/asm-commons-9.8.jar"
ASM_T="$GRADLE_MODS/org.ow2.asm/asm-tree/9.8/18419ca5b77a2f81097c741e7872e6ab8d2f40d/asm-tree-9.8.jar"
ASM_U="$GRADLE_MODS/org.ow2.asm/asm-util/9.8/395f1c1f035258511f27bc9b2583d76e4b143f59/asm-util-9.8.jar"
MAPIO="$GRADLE_MODS/net.fabricmc/mapping-io/0.7.1/e96e1dda50d631c89f83ad5c2c951c3f2298e7c2/mapping-io-0.7.1.jar"
SLF4J="$GRADLE_MODS/org.slf4j/slf4j-api/2.0.1/f48d81adce2abf5ad3cfe463df517952749e03bc/slf4j-api-2.0.1.jar"
MCI="$LOOM/minecraftMaven/net/minecraft/minecraft-merged-intermediary/$MCJAR/minecraft-merged-intermediary-$MCJAR.jar"
MAP="$LOOM/$MCVER/net.fabricmc.yarn.${YARN_DIR}.${YARN}-v2/mappings.tiny"

WORK="$ROOT/temp/remap-work"
rm -rf "$WORK"
mkdir -p "$WORK"
trap 'rm -rf "$WORK"' EXIT

unzip -q "$SRC" -d "$WORK/x"
rm -f "$WORK"/x/mtr/screen/WidgetShorterSlider*
(cd "$WORK/x" && "$JAR" cf "$WORK/filtered.jar" .)

CP="$TR;$ASM;$ASM_A;$ASM_C;$ASM_T;$ASM_U;$MAPIO;$SLF4J"
"$JAVA" -cp "$CP" net.fabricmc.tinyremapper.Main "$WORK/filtered.jar" "$OUT" "$MAP" intermediary named "$MCI"
echo "wrote $OUT"
