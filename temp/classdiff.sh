#!/usr/bin/env bash
# Compare class sets: what v1.20.1 ships that v1.19.4 does not (and vice versa).
set -u
cd /d/MC-MTR-JS/mtr-headlight || exit 1
unzip -l v1.20.1/build/libs/lte-1.0.33.jar | grep 'dev/js/mtrheadlight/.*\.class' | awk '{print $4}' | sort -u > temp/c120.txt
unzip -l v1.19.4/build/libs/lte-1.0.0.jar | grep 'dev/js/mtrheadlight/.*\.class' | awk '{print $4}' | sort -u > temp/c119.txt
echo "1.20.1 classes: $(wc -l < temp/c120.txt)   1.19.4 classes: $(wc -l < temp/c119.txt)"
echo "=== in 1.20.1 but NOT in 1.19.4 (top-level only) ==="
comm -23 temp/c120.txt temp/c119.txt | grep -v '\$' | sed 's|dev/js/mtrheadlight/||'
echo "=== in 1.19.4 but NOT in 1.20.1 ==="
comm -13 temp/c120.txt temp/c119.txt | grep -v '\$' | sed 's|dev/js/mtrheadlight/||'
