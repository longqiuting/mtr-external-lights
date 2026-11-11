#!/usr/bin/env bash
# Compile every version and report the error count (0 = that version still builds).
set -u
cd /d/MC-MTR-JS/mtr-headlight || exit 1
G="/d/MC-MTR-JS/temp/gradle/gradle-8.14/bin/gradle"
export JAVA_HOME="C:/Program Files/Java/jdk-21.0.10"
for v in "$@"; do
    "$G" -p "$v" compileJava --no-daemon -q > "temp/b_$v.log" 2>&1
    rc=$?
    echo "$v exit=$rc errors=$(grep -c '错误:' "temp/b_$v.log")"
done
