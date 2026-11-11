#!/usr/bin/env bash
# Generate the Line 4 / Line 6 Xiamen PSD+APG resource sets for every version,
# by cloning the existing `_l3` files and swapping in the new artwork.
set -euo pipefail

ROOT="/d/MC-MTR-JS/mtr-headlight"
SRC="C:/Users/AD/Desktop/素材"
VERSIONS=(v1.18.2 v1.19.2 v1.19.4 v1.20.1)

gen_line() {
  local ver="$1" suf="$2" num="$3" cn="$4"
  local res="$ROOT/$ver/src/main/resources"
  local assets="$res/assets/mtrlte"
  local tex_src="$SRC/厦门地铁${num}屏蔽门"
  local item_src="$SRC/厦门地铁${num}号线物品贴图"

  echo "== $ver / _$suf (line $num) =="

  # 1. block textures: <name>.png -> <name>_<suf>.png
  local n=0
  while IFS= read -r f; do
    local base="${f%.png}"
    cp -f "$tex_src/$f" "$assets/textures/block/${base}_${suf}.png"
    n=$((n + 1))
  done < <(cd "$tex_src" && ls *.png)
  echo "   block textures: $n"

  # 2. item icons: amtr_3_x.png -> amtr_<num>_x.png
  n=0
  while IFS= read -r f; do
    local base="${f#amtr_3_}"
    cp -f "$item_src/$f" "$assets/textures/item/amtr_${num}_${base}"
    n=$((n + 1))
  done < <(cd "$item_src" && ls *.png)
  echo "   item icons: $n"

  # 3. blockstates / block models / loot tables: clone from _l3
  n=0
  while IFS= read -r f; do
    sed "s/_l3/_${suf}/g" "$f" > "${f%_l3.json}_${suf}.json"
    n=$((n + 1))
  done < <(ls "$assets/blockstates"/*_l3.json)
  echo "   blockstates: $n"

  n=0
  while IFS= read -r f; do
    sed "s/_l3/_${suf}/g" "$f" > "${f%_l3.json}_${suf}.json"
    n=$((n + 1))
  done < <(ls "$assets/models/block"/*_l3.json)
  echo "   block models: $n"

  n=0
  while IFS= read -r f; do
    sed -e "s/_l3/_${suf}/g" -e "s/amtr_3_/amtr_${num}_/g" "$f" > "${f%_l3.json}_${suf}.json"
    n=$((n + 1))
  done < <(ls "$assets/models/item"/*_l3.json)
  echo "   item models: $n"

  if [ -d "$res/data/mtrlte/loot_tables/blocks" ]; then
    n=0
    while IFS= read -r f; do
      sed "s/_l3/_${suf}/g" "$f" > "${f%_l3.json}_${suf}.json"
      n=$((n + 1))
    done < <(ls "$res/data/mtrlte/loot_tables/blocks"/*_l3.json)
    echo "   loot tables: $n"
  fi

  # 4. lang keys (zh_cn / en_us), inserted right after the _l3 block
  local zh="$assets/lang/zh_cn.json"
  local en="$assets/lang/en_us.json"
  local marker_zh='"block.mtrlte.apg_end_xiamen_l3"'
  local marker_en='"block.mtrlte.apg_end_xiamen_l3"'
  awk -v suf="$suf" -v num="$num" -v cn="$cn" -v marker="$marker_zh" '
    { print }
    index($0, marker) {
      printf "  \"block.mtrlte.psd_door_xiamen_%s\": \"厦门地铁%s号线全高屏蔽门（门扇）\",\n", suf, cn
      printf "  \"block.mtrlte.psd_glass_xiamen_%s\": \"厦门地铁%s号线全高屏蔽门（玻璃）\",\n", suf, cn
      printf "  \"block.mtrlte.psd_end_xiamen_%s\": \"厦门地铁%s号线全高屏蔽门（端部）\",\n", suf, cn
      printf "  \"block.mtrlte.psd_top_xiamen_%s\": \"厦门地铁%s号线全高屏蔽门（吊板）\",\n", suf, cn
      printf "  \"block.mtrlte.apg_door_xiamen_%s\": \"厦门地铁%s号线半高屏蔽门（门扇）\",\n", suf, cn
      printf "  \"block.mtrlte.apg_glass_xiamen_%s\": \"厦门地铁%s号线半高屏蔽门（玻璃）\",\n", suf, cn
      printf "  \"block.mtrlte.apg_end_xiamen_%s\": \"厦门地铁%s号线半高屏蔽门（端部）\",\n", suf, cn
    }' "$zh" > "$zh.tmp" && mv "$zh.tmp" "$zh"

  awk -v suf="$suf" -v num="$num" -v marker="$marker_en" '
    { print }
    index($0, marker) {
      printf "  \"block.mtrlte.psd_door_xiamen_%s\": \"Xiamen Line %s Full-Height PSD (Door)\",\n", suf, num
      printf "  \"block.mtrlte.psd_glass_xiamen_%s\": \"Xiamen Line %s Full-Height PSD (Glass)\",\n", suf, num
      printf "  \"block.mtrlte.psd_end_xiamen_%s\": \"Xiamen Line %s Full-Height PSD (End)\",\n", suf, num
      printf "  \"block.mtrlte.psd_top_xiamen_%s\": \"Xiamen Line %s Full-Height PSD (Header)\",\n", suf, num
      printf "  \"block.mtrlte.apg_door_xiamen_%s\": \"Xiamen Line %s Half-Height APG (Door)\",\n", suf, num
      printf "  \"block.mtrlte.apg_glass_xiamen_%s\": \"Xiamen Line %s Half-Height APG (Glass)\",\n", suf, num
      printf "  \"block.mtrlte.apg_end_xiamen_%s\": \"Xiamen Line %s Half-Height APG (End)\",\n", suf, num
    }' "$en" > "$en.tmp" && mv "$en.tmp" "$en"
  echo "   lang keys: 7 zh + 7 en"
}

for v in "${VERSIONS[@]}"; do
  gen_line "$v" "l4" "4" "四"
  gen_line "$v" "l6" "6" "六"
done

echo "done"
