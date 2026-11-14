#!/bin/bash
# dump selected members of 1.18.2 yarn classes: sig1182b.sh <class> <grep-pattern>
JAR="C:/Users/AD/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.18.2-net.fabricmc.yarn.1_18_2.1.18.2+build.4-v2/minecraft-merged-1.18.2-net.fabricmc.yarn.1_18_2.1.18.2+build.4-v2.jar"
JAVAP="C:/Program Files/Java/jdk-21.0.10/bin/javap"
cls="$1"
pat="$2"
echo "==================== $cls  ~$pat"
"$JAVAP" -cp "$JAR" "$cls" 2>&1 | grep -E "$pat"
