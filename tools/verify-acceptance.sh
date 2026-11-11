#!/usr/bin/env bash
# Acceptance checks for the three legacy ports.
set -u
cd /d/MC-MTR-JS/mtr-headlight || exit 1
export JAVA_HOME="C:/Program Files/Java/jdk-21.0.10"
JAVAP="$JAVA_HOME/bin/javap"

for v in v1.18.2 v1.19.2 v1.19.4; do
  jar=$(ls "$PWD/$v/build/libs/"lte-1.0*.jar 2>/dev/null | grep -v sources | head -1)
  echo "=================================================================="
  echo "== $v  ($(basename "$jar"))"
  echo "=================================================================="
  echo "-- mixin config at resources root:"
  unzip -Z1 "$jar" | grep -E '^mtrlte\.fangsu\.mixins\.json$' || echo "   !! MISSING"
  echo "-- fabric.mod.json lists it:"
  unzip -p "$jar" fabric.mod.json | grep -n 'fangsu.mixins.json' || echo "   !! NOT LISTED"
  echo "-- classes in jar:"
  unzip -Z1 "$jar" | grep -E 'mixin/(fangsu/|FangsuMixinPlugin)|FangsuCompat\.class|signal/FangsuTrafficLightMode\.class' | sed 's/^/   /'
  echo "-- javap handlers:"
  rm -rf temp/acc && mkdir -p temp/acc
  (cd temp/acc && unzip -o -q "$jar" 'dev/js/mtrheadlight/mixin/*' 'dev/js/mtrheadlight/signal/FangsuTrafficLightMode*' 'dev/js/mtrheadlight/FangsuCompat.class')
  "$JAVAP" -p -classpath temp/acc 'dev.js.mtrheadlight.mixin.fangsu.FangsuTrafficLightRenderMixin' 2>&1 | grep -E 'class|lte\$|Mixin' | sed 's/^/   /'
  echo "   -- plugin:"
  "$JAVAP" -p -classpath temp/acc 'dev.js.mtrheadlight.mixin.FangsuMixinPlugin' 2>&1 | grep -E 'class |turnoutsPresent' | sed 's/^/   /'
  echo "   -- mode (must NOT be the old stub):"
  "$JAVAP" -p -classpath temp/acc 'dev.js.mtrheadlight.signal.FangsuTrafficLightMode' 2>&1 | sed 's/^/   /'
  echo "   -- compat:"
  "$JAVAP" -p -classpath temp/acc 'dev.js.mtrheadlight.FangsuCompat' 2>&1 | grep -E 'class |announce|present' | sed 's/^/   /'
  echo "-- assets inside jar:"
  echo -n "   glass key: "; unzip -p "$jar" assets/mtrlte/blockstates/apg_glass_xiamen.json | grep -o 'propagate_property' | head -1
  echo -n "   glass variants: "; unzip -p "$jar" assets/mtrlte/blockstates/apg_glass_xiamen.json | grep -c '"model"'
  echo -n "   psd_door blockstate == 1.20.1: "
  a=$(unzip -p "$jar" assets/mtrlte/blockstates/psd_door_xiamen.json | md5sum | cut -d' ' -f1)
  b=$(md5sum < v1.20.1/src/main/resources/assets/mtrlte/blockstates/psd_door_xiamen.json | cut -d' ' -f1)
  [ "$a" = "$b" ] && echo "yes ($a)" || echo "NO ($a vs $b)"
  echo -n "   apg_door blockstate == 1.20.1: "
  a=$(unzip -p "$jar" assets/mtrlte/blockstates/apg_door_xiamen.json | md5sum | cut -d' ' -f1)
  b=$(md5sum < v1.20.1/src/main/resources/assets/mtrlte/blockstates/apg_door_xiamen.json | cut -d' ' -f1)
  [ "$a" = "$b" ] && echo "yes ($a)" || echo "NO ($a vs $b)"
  echo
done
