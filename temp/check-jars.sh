#!/usr/bin/env bash
cd /d/MC-MTR-JS/mtr-headlight || exit 1
for v in v1.18.2 v1.19.2 v1.19.4 v1.20.1; do
  jar=$(ls "$v"/build/libs/lte-*.jar | grep -v sources | head -1)
  size=$(stat -c%s "$jar")
  md5=$(md5sum "$jar" | cut -c1-12)
  unzip -l "$jar" > /tmp/j.txt
  l4=$(grep -c '_l4' /tmp/j.txt)
  l6=$(grep -c '_l6' /tmp/j.txt)
  t4=$(grep -c 'textures/block/.*_l4.png' /tmp/j.txt)
  i4=$(grep -c 'textures/item/amtr_4_' /tmp/j.txt)
  lt=$(grep -c 'loot_tables/blocks/.*_l4.json' /tmp/j.txt)
  echo "$v | $(basename "$jar") | ${size} B | md5=${md5} | entries l4=${l4} l6=${l6} | block_png l4=${t4} | item_icon 4=${i4} | loot l4=${lt}"
done
