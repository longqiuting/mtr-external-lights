#!/usr/bin/env bash
# Quick jar inventory for the v1.19.4 build: size, class count, key classes present?
set -u
cd /d/MC-MTR-JS/mtr-headlight || exit 1
JAR=v1.19.4/build/libs/lte-1.0.0.jar
ls -la "$JAR"
echo "classes=$(unzip -l "$JAR" | grep -c '\.class$')"
for c in BeamGlow ProtoBeamCone BeamGeom RainSpray SprayParticle SprayType collision/CollisionSystem collision/CollisionImpact signal/SignalLinkItem xiamen/XiamenPsdBlocks xiamen/PsdAPGItem BeamEditor3DScreen ParameterScreen ConfigScreen CustomPack TrainNames SignalLinkStore BeamPackWriter PackImportScreen TrainPreview server/ServerCollision mixin/collision/MixinEntityMove; do
    n=$(unzip -l "$JAR" | grep -c "mtrheadlight/$c\.class")
    printf '%-34s %s\n' "$c" "$n"
done
