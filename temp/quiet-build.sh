#!/usr/bin/env bash
# Wait until no other JVM is running (the repo is being built by another process),
# then build all four version trees serially and verify each jar.
set -uo pipefail
cd /d/MC-MTR-JS/mtr-headlight || exit 1
export JAVA_HOME="C:/Program Files/Java/jdk-21.0.10"
GRADLE="/d/MC-MTR-JS/temp/gradle/gradle-8.14/bin/gradle"

count_java() { tasklist 2>/dev/null | grep -ci "java.exe" || true; }

echo "waiting for a quiet window (no java.exe)..."
quiet=0
for i in $(seq 1 120); do
  n=$(count_java)
  if [ "$n" -eq 0 ]; then quiet=$((quiet+1)); else quiet=0; fi
  if [ "$quiet" -ge 3 ]; then echo "quiet after ${i}s"; break; fi
  sleep 5
done
echo "java procs before build: $(count_java)"

overall=0
for v in v1.20.1 v1.19.4 v1.19.2 v1.18.2; do
  echo "=== $v ==="
  start=$(date +%s)
  "$GRADLE" -p "$v" clean build --no-daemon -q > "/tmp/quiet-$v.log" 2>&1
  rc=$?
  echo "  gradle exit=$rc ($(( $(date +%s) - start ))s)"
  if [ $rc -ne 0 ]; then
    overall=$rc
    grep -n "What went wrong" -A4 "/tmp/quiet-$v.log" | head -12
    grep -nE "错误:|error:" "/tmp/quiet-$v.log" | head -10
  fi
  jar=$(ls "$v"/build/libs/lte-*.jar 2>/dev/null | grep -v sources | head -1)
  if [ -n "$jar" ]; then
    unzip -l "$jar" > /tmp/jq.txt
    echo "  jar=$(basename "$jar") size=$(stat -c%s "$jar") l4=$(grep -c '_l4' /tmp/jq.txt) l6=$(grep -c '_l6' /tmp/jq.txt)"
  else
    echo "  !! no jar"; overall=1
  fi
done
echo "OVERALL=$overall"
