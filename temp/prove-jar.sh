#!/usr/bin/env bash
# javap the shipped classes out of the 1.20.1 jar to prove the new line constants
# and the block->colour entry point actually made it into the build.
set -euo pipefail
cd /d/MC-MTR-JS/mtr-headlight
JAVA_HOME="C:/Program Files/Java/jdk-21.0.10"
JAR=$(ls v1.20.1/build/libs/lte-*.jar | grep -v sources | head -1)
echo "jar: $JAR"
echo "--- XiamenLine ---"
"$JAVA_HOME/bin/javap" -p -cp "$JAR" dev.js.mtrheadlight.xiamen.XiamenLine
echo "--- XiamenDoorLights (public API) ---"
"$JAVA_HOME/bin/javap" -p -cp "$JAR" dev.js.mtrheadlight.xiamen.XiamenDoorLights | grep -i "colorFor\|lineNumberAt"
echo "--- RenderPsdDoor#render bytecode: which colourFor overload is called ---"
"$JAVA_HOME/bin/javap" -p -c -cp "$JAR" dev.js.mtrheadlight.xiamen.RenderPsdDoor | grep -n "XiamenDoorLights" | head