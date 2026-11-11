#!/usr/bin/env bash
# Numbers for the port report.
set -u
cd /d/MC-MTR-JS/mtr-headlight || exit 1
echo "== common (new files) =="
for f in RainHush VehicleLightPacks mtr/BuiltInTrains bind/LteMtrLookup server/MtrServerData collision/CarGeometry collision/RailBed collision/Trackbed collision/TrainTickCapture net/LteChannel net/ServerAuthority server/ServerConfig signal/SignalSourceCapture MtrCompat; do
    printf '%-34s %s\n' "$f" "$(wc -l < common/src/main/java/dev/js/mtrheadlight/$f.java)"
done
echo "== totals =="
echo "common files=$(find common/src/main/java -name '*.java' | wc -l) lines=$(find common/src/main/java -name '*.java' -exec cat {} + | wc -l)"
echo "v1.19.4 files=$(find v1.19.4/src/main/java -name '*.java' | wc -l) lines=$(find v1.19.4/src/main/java -name '*.java' -exec cat {} + | wc -l)"
echo "v1.20.1 files=$(find v1.20.1/src/main/java -name '*.java' | wc -l) lines=$(find v1.20.1/src/main/java -name '*.java' -exec cat {} + | wc -l)"
ls -l v1.19.4/build/libs/lte-1.0.0.jar
