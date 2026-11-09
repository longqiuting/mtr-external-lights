#!/usr/bin/env bash
# Static dependency scan for the dedicated-server entry point.
#
# Starts at dev.js.mtrheadlight.server.* and walks every dev.js.mtrheadlight class it
# references (transitively, via the constant pool of each class), collecting all
# foreign class names. Fails if anything client-only shows up.
#
# usage: scan-server-deps.sh <classes-dir> [-v]     (-v prints the reference edges)
set -u
ROOT=${1:?classes dir}
VERBOSE=${2:-}
JAVAP="${JAVAP:-C:/Program Files/Java/jdk-21.0.10/bin/javap}"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

seeds=$(cd "$ROOT" && find dev/js/mtrheadlight/server -name '*.class' | sed 's|/|.|g; s|\.class$||')
if [ -z "$seeds" ]; then
  echo "!! no classes under $ROOT/dev/js/mtrheadlight/server"
  exit 2
fi

seen="$work/seen"; : > "$seen"
queue="$work/queue"; printf '%s\n' $seeds > "$queue"
foreign="$work/foreign"; : > "$foreign"

while [ -s "$queue" ]; do
  cls=$(head -1 "$queue")
  sed -i '1d' "$queue"
  grep -qxF "$cls" "$seen" && continue
  printf '%s\n' "$cls" >> "$seen"
  refs=$("$JAVAP" -v -p -classpath "$ROOT" "$cls" 2>/dev/null \
          | sed -n 's/.*= Class *[^/]*\/\/ *\([^ ]*\).*/\1/p' | sort -u)
  while IFS= read -r ref; do
    [ -z "$ref" ] && continue
    case "$ref" in
      dev/js/mtrheadlight/*)
        [ -n "$VERBOSE" ] && echo "  edge: $cls -> $ref" >&2
        printf '%s\n' "$(printf '%s' "$ref" | tr '/' '.')" >> "$queue"
        ;;
      *)
        printf '%s\n' "$ref" >> "$foreign"
        ;;
    esac
  done <<< "$refs"
done

echo "=== reachable from dev.js.mtrheadlight.server.* (transitive, ours) ==="
sort "$seen" | sed 's/^/  our: /'
echo
echo "=== foreign classes referenced (deduped) ==="
sort -u "$foreign" | sed 's/^/  ref: /'
echo
echo "=== verdict ==="
bad=$(sort -u "$foreign" | grep -E '^net/minecraft/client/|^mtr/client/|^mtr/render/|^mtr/sound/' || true)
bad2=$(sort "$seen" | grep -E '^dev\.js\.mtrheadlight\.(MtrHeadlightClient|DepartureState|Ldl[0-9]|Ldl4Backend|LightBackend|LightController|LightBackends|ConeVolumeRenderer|ConeVolumeBeam|BeamGlow|BeamRelight|BeamBlockLight|ProtoBeamCone|RainHush|RainSpray|RainTest|RenderHooks|ConfigScreen|ParameterScreen|Siding.*|ColorPickerScreen|ResetConfirmScreen|ShaderPackProbe|RailChunkBuildBoost|RailRelight.*|RailLightInject|RailDynamicLight|RailWeather|RailChunk|ModMenuIntegration|KeyHooks|Commands|MtrHeadlightDynamicLights|ConeTest|BeamLightTest|Fangsu.*|GlowCompat)' || true)
if [ -n "$bad" ] || [ -n "$bad2" ]; then
  echo "  FAIL - client-only references reachable from the server entry point:"
  [ -n "$bad" ] && printf '%s\n' "$bad" | sed 's/^/    foreign: /'
  [ -n "$bad2" ] && printf '%s\n' "$bad2" | sed 's/^/    ours:    /'
  exit 1
fi
echo "  OK - no net.minecraft.client.* / mtr.client.* / rendering class reachable"
