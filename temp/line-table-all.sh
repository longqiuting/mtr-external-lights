#!/usr/bin/env bash
cd /d/MC-MTR-JS/mtr-headlight || exit 1
JAVA="C:/Program Files/Java/jdk-21.0.10/bin/java"
for v in v1.20.1 v1.19.4 v1.19.2 v1.18.2; do
  jar=$(ls "$v"/build/libs/lte-*.jar | grep -v sources | head -1)
  winpath="D:/MC-MTR-JS/mtr-headlight/$jar"
  echo "=== $v ($(basename "$jar")) ==="
  ( cd "$LOCALAPPDATA/Temp" && "$JAVA" -cp "$winpath" "D:/MC-MTR-JS/mtr-headlight/temp/LineTable.java" 2>&1 | tail -5 )
done
