#!/usr/bin/env bash
# 生成屏蔽门四个方块 id 的 blockstate（属性组合多，手写容易漏 → 机器生成）
set -eu
cd "$(dirname "$0")/../src/main/resources/assets/mtrlte/blockstates"

rot() {
  case "$1" in
    west)  echo 0 ;;
    north) echo 90 ;;
    east)  echo 180 ;;
    south) echo 270 ;;
  esac
}

# ---------- 门扇：facing × half × side × open × end × unlocked，模型一律 air（BER 画）----------
{
  echo '{'
  echo '  "variants": {'
  first=1
  for f in west north east south; do
    for h in lower upper; do
      for s in left right; do
        for o in false true; do
          for e in false true; do
            for u in false true; do
              for p in false true; do
                [ $first -eq 1 ] || echo ','
                first=0
                printf '    "facing=%s,half=%s,side=%s,open=%s,end=%s,unlocked=%s,powered=%s": { "model": "mtrlte:block/xiamen_psd_door_empty" }' \
                  "$f" "$h" "$s" "$o" "$e" "$u" "$p"
              done
            done
          done
        done
      done
    done
  done
  echo
  echo '  }'
  echo '}'
} > xiamen_psd_door.json

# ---------- 玻璃：facing × half × side(LEFT/MIDDLE/RIGHT/SINGLE) ----------
{
  echo '{'
  echo '  "variants": {'
  first=1
  for f in west north east south; do
    for h in lower upper; do
      model="mtrlte:block/xiamen_psd_glass_bottom"
      [ "$h" = upper ] && model="mtrlte:block/xiamen_psd_glass_top"
      for s in left middle right single; do
        [ $first -eq 1 ] || echo ','
        first=0
        printf '    "facing=%s,half=%s,side=%s": { "model": "%s", "y": %s }' "$f" "$h" "$s" "$model" "$(rot $f)"
      done
    done
  done
  echo
  echo '  }'
  echo '}'
} > xiamen_psd_glass.json

# ---------- 端部：facing × half × touching_left × touching_right ----------
{
  echo '{'
  echo '  "variants": {'
  first=1
  for f in west north east south; do
    for h in lower upper; do
      for l in air door none; do
        for r in air door none; do
          [ $first -eq 1 ] || echo ','
          first=0
          printf '    "facing=%s,half=%s,touching_left=%s,touching_right=%s": { "model": "mtrlte:block/xiamen_psd_end", "y": %s }' \
            "$f" "$h" "$l" "$r" "$(rot $f)"
        done
      done
    done
  done
  echo
  echo '  }'
  echo '}'
} > xiamen_psd_end.json

# ---------- 吊板：facing ----------
{
  echo '{'
  echo '  "variants": {'
  first=1
  for f in west north east south; do
    [ $first -eq 1 ] || echo ','
    first=0
    printf '    "facing=%s": { "model": "mtrlte:block/xiamen_psd_top", "y": %s }' "$f" "$(rot $f)"
  done
  echo
  echo '  }'
  echo '}'
} > xiamen_psd_top.json

echo "generated:"; wc -l xiamen_psd_door.json xiamen_psd_glass.json xiamen_psd_end.json xiamen_psd_top.json
