#!/bin/bash
# Dump javap signatures for 1.18.2 yarn classes (stage 1 port reference).
JAR="C:/Users/AD/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.18.2-net.fabricmc.yarn.1_18_2.1.18.2+build.4-v2/minecraft-merged-1.18.2-net.fabricmc.yarn.1_18_2.1.18.2+build.4-v2.jar"
JAVAP="C:/Program Files/Java/jdk-21.0.10/bin/javap"
for c in "$@"; do
  echo "==================== $c"
  "$JAVAP" -cp "$JAR" "$c" 2>&1 | head -80
done
