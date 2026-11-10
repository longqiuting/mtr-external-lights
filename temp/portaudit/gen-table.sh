#!/usr/bin/env bash
# Generates the per-file markdown table for docs/port-audit-1.18-1.19.md
cd "D:/MC-MTR-JS/mtr-headlight" || exit 1
declare -A SUM
while IFS=$'\t' read -r cat path reason; do
  case "$path" in
    common/*) real="common/src/main/java/dev/js/mtrheadlight/${path#common/}" ;;
    v1.20.1/*) real="v1.20.1/src/main/java/dev/js/mtrheadlight/${path#v1.20.1/}" ;;
  esac
  if [ -f "$real" ]; then loc=$(wc -l < "$real" | tr -d ' '); else loc="?"; fi
  SUM[$cat]=$(( ${SUM[$cat]:-0} + 10#${loc/?/0} ))
  printf '| `%s` | %s | %s | %s |\n' "$path" "$loc" "$cat" "$reason"
done < temp/portaudit/classify.tsv
echo
echo "TOTALS"
for k in A B C D; do echo "$k ${SUM[$k]}"; done
