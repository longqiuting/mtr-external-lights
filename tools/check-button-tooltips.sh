#!/usr/bin/env bash
# Self-check: every screen button must have a non-null tooltip supplier on the versions whose
# ButtonWidget dereferences it unconditionally.
#
# Why this exists (real crash, 1.19.2):
#   NullPointerException: Cannot invoke "net.minecraft.class_4185$class_5316.onTooltip(...)"
#     because the return value of ... is null
#     at class_4185.method_25352 (ButtonWidget.render)
# 1.19.2 / 1.18.2 ButtonWidget.renderTooltip() is
#     return this.tooltipSupplier.onTooltip(this, matrices, mouseX, mouseY);
# i.e. no null check -> the first hover on a button built with a null supplier kills the screen.
# ButtonWidget.EMPTY is a public, non-null TooltipSupplier that draws nothing: the right value.
#
# 1.20.1 / 1.19.4 are different: their raw constructor is protected (builder only) and
# ClickableWidget.applyTooltip() starts with `if (this.tooltip == null) return;`, so the builder
# default (no tooltip set) is safe. A non-null Tooltip.of(Text.empty()) there would paint an empty
# tooltip box on every hover, so "no tooltip" must stay "never set one".
#
# Usage: bash tools/check-button-tooltips.sh   (exit 0 = all good)
set -u
ROOT=${ROOT:-/d/MC-MTR-JS/mtr-headlight}
fail=0

# Strip comment lines so prose about onTooltip/null tooltips is not mistaken for code.
code_of() {
  grep -vE '^[[:space:]]*(\*|//|/\*)' "$1"
}

for dir in v1.20.1 v1.19.4 v1.19.2 v1.18.2; do
  echo "=== $dir"
  for f in "$ROOT/$dir"/src/main/java/dev/js/mtrheadlight/*.java; do
    [ -e "$f" ] || continue
    name="$dir/$(basename "$f")"

    # 1. Any button constructor / builder call, with its line number.
    awk -v fname="$(basename "$f")" '/new ButtonWidget\(|ButtonWidget\.builder\(/ {
           line = $0; sub(/^[[:space:]]+/, "", line);
           if (line ~ /^(\*|\/\/|\/\*)/) next;
           printf "  %-16s L%-4d %s\n", fname, NR, line;
         }' "$f"

    # 2. Never a literal null supplier.
    if code_of "$f" | grep -qE 'new ButtonWidget\([^;]*,[[:space:]]*null[[:space:]]*\)'; then
      echo "  !! $name: new ButtonWidget(..., null) -> hover NPE on 1.19.2/1.18.2"; fail=1
    fi

    # 3. Raw constructor is only allowed where it exists, and must pass ButtonWidget.EMPTY.
    if code_of "$f" | grep -q 'new ButtonWidget('; then
      if [ "$dir" = "v1.20.1" ] || [ "$dir" = "v1.19.4" ]; then
        echo "  !! $name: 该版本构造器是 protected，只能用 builder"; fail=1
      fi
      # -z so the check spans lines: the call is often wrapped with EMPTY on the next line.
      if ! code_of "$f" | grep -qzE 'new ButtonWidget\([^;]*ButtonWidget\.EMPTY'; then
        echo "  !! $name: 构造器没有传 ButtonWidget.EMPTY"; fail=1
      fi
    fi

    # 4. No builder in the versions that do not have one, and never an explicit null tooltip.
    if [ "$dir" = "v1.19.2" ] || [ "$dir" = "v1.18.2" ]; then
      if code_of "$f" | grep -q 'ButtonWidget\.builder('; then
        echo "  !! $name: 该版本没有 ButtonWidget.builder"; fail=1
      fi
    fi
    if code_of "$f" | grep -qE '\.tooltip\([[:space:]]*null[[:space:]]*\)'; then
      echo "  !! $name: .tooltip(null)"; fail=1
    fi

    # 5. Nothing may call the supplier by hand (that NPEs on 1.19.2 just the same).
    if code_of "$f" | grep -qE '\.onTooltip\(|\.renderTooltip\('; then
      echo "  !! $name: 代码自己调用了 onTooltip/renderTooltip"; fail=1
    fi
  done
done

echo
if [ "$fail" -eq 0 ]; then
  echo "OK: 所有按钮的 tooltip 供应器都非 null（1.19.2/1.18.2 = ButtonWidget.EMPTY；"
  echo "    1.20.1/1.19.4 = 不设置，ClickableWidget.applyTooltip() 自带 null 检查）。"
else
  echo "FAILED: 见上面的 !! 行"
fi
exit "$fail"
