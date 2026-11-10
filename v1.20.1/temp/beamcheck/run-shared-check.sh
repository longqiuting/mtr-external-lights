#!/usr/bin/env bash
# 离线跑 `_shared.json` 的作用域检查：不启动游戏，直接把一个资源包文件夹喂给 BeamPackScope。
# 用法：  bash v1.20.1/temp/beamcheck/run-shared-check.sh [包文件夹]
set -euo pipefail
w() { cygpath -m "$1"; }          # 原生程序（java/javac）只认 D:/... 这种路径
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJ="$(cd "$HERE/../.." && pwd)"
JDK="C:/Program Files/Java/jdk-21.0.10"
C="$(w "$HOME")/.gradle/caches"

MC="$C/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.20.1-net.fabricmc.yarn.1_20_1.1.20.1+build.10-v2/minecraft-merged-1.20.1-net.fabricmc.yarn.1_20_1.1.20.1+build.10-v2.jar"
GS="$(ls "$C"/modules-2/files-2.1/com.google.code.gson/gson/2.10.1/*/gson-2.10.1.jar | head -1)"
SL="$(ls "$C"/modules-2/files-2.1/org.slf4j/slf4j-api/2.0.1/*/slf4j-api-2.0.1.jar | head -1)"
LG="$(ls "$C"/modules-2/files-2.1/com.mojang/logging/1.1.1/*/logging-1.1.1.jar | head -1)"
GV="$(ls "$C"/modules-2/files-2.1/com.google.guava/guava/31.1-jre/*/guava-31.1-jre.jar | head -1)"
FA="$(ls "$C"/modules-2/files-2.1/com.google.guava/failureaccess/1.0.1/*/failureaccess-1.0.1.jar | head -1)"
FL="$(ls "$C"/modules-2/files-2.1/net.fabricmc/fabric-loader/0.16.9/*/fabric-loader-0.16.9.jar | head -1)"

CP="$(w "$PROJ")/build/classes/java/main;$MC;$GS;$SL;$LG;$GV;$FA;$FL"

mkdir -p "$HERE/out"
"$JDK/bin/javac.exe" -encoding UTF-8 -cp "$CP" -d "$(w "$HERE/out")" \
    "$(w "$HERE/dev/js/mtrheadlight/SharedCheck.java")"
"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 -cp "$(w "$HERE/out");$CP" \
    dev.js.mtrheadlight.SharedCheck "$@"
