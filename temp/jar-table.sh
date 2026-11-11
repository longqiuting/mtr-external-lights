#!/usr/bin/env bash
cd /d/MC-MTR-JS/mtr-headlight || exit 1
for v in v1.20.1 v1.19.4 v1.19.2 v1.18.2; do
  jar=$(ls "$v"/build/libs/lte-*.jar | grep -v sources | head -1)
  size=$(stat -c%s "$jar")
  md5=$(md5sum "$jar" | cut -d' ' -f1)
  mt=$(stat -c%y "$jar" | cut -d. -f1)
  unzip -l "$jar" > /tmp/z.txt
  echo "| \`$v\` | \`$(basename "$jar")\` | $size B | \`$md5\` | $mt | $(grep -c '_l4' /tmp/z.txt) / $(grep -c '_l6' /tmp/z.txt) | $(grep -c 'xiamen/XiamenLine.class' /tmp/z.txt) |"
done
