#!/usr/bin/env bash
# Cross-check every class named in the mixin configs against the built jar, and print the
# entrypoints from fabric.mod.json with their presence. (1.18.2 instance)
set -u
cd /d/MC-MTR-JS/mtr-headlight || exit 1
VER=v1.18.2
JAR=$VER/build/libs/lte-1.0.0.jar
unzip -l "$JAR" | grep '\.class$' | awk '{print $4}' | sed 's|/|.|g; s|\.class$||' > temp/jarclasses-1182.txt
echo "== mixin config targets =="
for cfg in $VER/src/main/resources/mtrlte.*.mixins.json; do
    pkg=$(grep -o '"package"[^,]*' "$cfg" | sed 's/.*: *"//; s/"//')
    echo "-- $(basename "$cfg") package=$pkg"
    for cls in $(grep -o '"[A-Z][A-Za-z0-9]*"' "$cfg" | tr -d '"'); do
        found=$(grep -cx "$pkg.$cls" temp/jarclasses-1182.txt)
        [ "$found" = "1" ] || echo "   MISSING $pkg.$cls"
    done
done
echo "== fabric.mod.json refmap + entrypoints =="
unzip -l "$JAR" | grep -E "refmap|lte\.refmap" | head -3
grep -E '"[a-z.]*": \[' -A6 $VER/src/main/resources/fabric.mod.json | grep 'dev.js' | tr -d ' ",' | while read -r e; do
    n=$(grep -cx "$e" temp/jarclasses-1182.txt)
    printf '   %-60s %s\n' "$e" "$n"
done
