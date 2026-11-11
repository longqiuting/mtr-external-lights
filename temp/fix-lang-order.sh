#!/usr/bin/env bash
# Re-order the Line 4 / Line 6 lang keys so they read L3 -> L4 -> L6 (they were
# inserted L6-first because both awk passes targeted the same _l3 marker line).
set -euo pipefail

ROOT="/d/MC-MTR-JS/mtr-headlight"
VERSIONS=(v1.18.2 v1.19.2 v1.19.4 v1.20.1)

for v in "${VERSIONS[@]}"; do
  for pair in "zh_cn:四:六" "en_us:4:6"; do
    lang="${pair%%:*}"
    rest="${pair#*:}"
    cn4="${rest%%:*}"
    cn6="${rest#*:}"
    f="$ROOT/$v/src/main/resources/assets/mtrlte/lang/$lang.json"

    grep -v '_xiamen_l4"\|_xiamen_l6"' "$f" > "$f.clean"

    if [ "$lang" = "zh_cn" ]; then
      awk -v c4="$cn4" -v c6="$cn6" '
        { print }
        index($0, "\"block.mtrlte.apg_end_xiamen_l3\"") {
          printf "  \"block.mtrlte.psd_door_xiamen_l4\": \"厦门地铁%s号线全高屏蔽门（门扇）\",\n", c4
          printf "  \"block.mtrlte.psd_glass_xiamen_l4\": \"厦门地铁%s号线全高屏蔽门（玻璃）\",\n", c4
          printf "  \"block.mtrlte.psd_end_xiamen_l4\": \"厦门地铁%s号线全高屏蔽门（端部）\",\n", c4
          printf "  \"block.mtrlte.psd_top_xiamen_l4\": \"厦门地铁%s号线全高屏蔽门（吊板）\",\n", c4
          printf "  \"block.mtrlte.apg_door_xiamen_l4\": \"厦门地铁%s号线半高屏蔽门（门扇）\",\n", c4
          printf "  \"block.mtrlte.apg_glass_xiamen_l4\": \"厦门地铁%s号线半高屏蔽门（玻璃）\",\n", c4
          printf "  \"block.mtrlte.apg_end_xiamen_l4\": \"厦门地铁%s号线半高屏蔽门（端部）\",\n", c4
          printf "  \"block.mtrlte.psd_door_xiamen_l6\": \"厦门地铁%s号线全高屏蔽门（门扇）\",\n", c6
          printf "  \"block.mtrlte.psd_glass_xiamen_l6\": \"厦门地铁%s号线全高屏蔽门（玻璃）\",\n", c6
          printf "  \"block.mtrlte.psd_end_xiamen_l6\": \"厦门地铁%s号线全高屏蔽门（端部）\",\n", c6
          printf "  \"block.mtrlte.psd_top_xiamen_l6\": \"厦门地铁%s号线全高屏蔽门（吊板）\",\n", c6
          printf "  \"block.mtrlte.apg_door_xiamen_l6\": \"厦门地铁%s号线半高屏蔽门（门扇）\",\n", c6
          printf "  \"block.mtrlte.apg_glass_xiamen_l6\": \"厦门地铁%s号线半高屏蔽门（玻璃）\",\n", c6
          printf "  \"block.mtrlte.apg_end_xiamen_l6\": \"厦门地铁%s号线半高屏蔽门（端部）\",\n", c6
        }' "$f.clean" > "$f"
    else
      awk -v n4="$cn4" -v n6="$cn6" '
        { print }
        index($0, "\"block.mtrlte.apg_end_xiamen_l3\"") {
          printf "  \"block.mtrlte.psd_door_xiamen_l4\": \"Xiamen Line %s Full-Height PSD (Door)\",\n", n4
          printf "  \"block.mtrlte.psd_glass_xiamen_l4\": \"Xiamen Line %s Full-Height PSD (Glass)\",\n", n4
          printf "  \"block.mtrlte.psd_end_xiamen_l4\": \"Xiamen Line %s Full-Height PSD (End)\",\n", n4
          printf "  \"block.mtrlte.psd_top_xiamen_l4\": \"Xiamen Line %s Full-Height PSD (Header)\",\n", n4
          printf "  \"block.mtrlte.apg_door_xiamen_l4\": \"Xiamen Line %s Half-Height APG (Door)\",\n", n4
          printf "  \"block.mtrlte.apg_glass_xiamen_l4\": \"Xiamen Line %s Half-Height APG (Glass)\",\n", n4
          printf "  \"block.mtrlte.apg_end_xiamen_l4\": \"Xiamen Line %s Half-Height APG (End)\",\n", n4
          printf "  \"block.mtrlte.psd_door_xiamen_l6\": \"Xiamen Line %s Full-Height PSD (Door)\",\n", n6
          printf "  \"block.mtrlte.psd_glass_xiamen_l6\": \"Xiamen Line %s Full-Height PSD (Glass)\",\n", n6
          printf "  \"block.mtrlte.psd_end_xiamen_l6\": \"Xiamen Line %s Full-Height PSD (End)\",\n", n6
          printf "  \"block.mtrlte.psd_top_xiamen_l6\": \"Xiamen Line %s Full-Height PSD (Header)\",\n", n6
          printf "  \"block.mtrlte.apg_door_xiamen_l6\": \"Xiamen Line %s Half-Height APG (Door)\",\n", n6
          printf "  \"block.mtrlte.apg_glass_xiamen_l6\": \"Xiamen Line %s Half-Height APG (Glass)\",\n", n6
          printf "  \"block.mtrlte.apg_end_xiamen_l6\": \"Xiamen Line %s Half-Height APG (End)\",\n", n6
        }' "$f.clean" > "$f"
    fi
    rm -f "$f.clean"
    echo "$v $lang: $(grep -c '_xiamen_l4"\|_xiamen_l6"' "$f") keys"
  done
done
