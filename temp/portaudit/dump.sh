JAVAP="/c/Program Files/Java/jdk-21.0.10/bin/javap"
cd "D:/MC-MTR-JS/mtr-headlight"
declare -A JARS=(
 [118]="libs/MTR-fabric-1.18.2-3.2.2-hotfix-1.jar"
 [1192]="libs/MTR-fabric-1.19.2-3.2.2-hotfix-1.jar"
 [1194]="libs/MTR-fabric-1.19.4-3.2.2-hotfix-1.jar"
 [1201]="libs/MTR-fabric-1.20.1-3.6.4.2-named.jar"
)
CLASSES="mtr.data.Train mtr.data.TrainClient mtr.client.ClientData mtr.client.DynamicTrainModel mtr.render.JonModelTrainRenderer mtr.model.ModelTrainBase mtr.block.BlockPSDAPGDoorBase mtr.block.BlockPSDTop mtr.block.BlockAPGGlass mtr.block.BlockAPGGlassEnd mtr.block.BlockAPGDoor mtr.block.BlockPSDAPGBase mtr.block.BlockPSDAPGGlassBase mtr.block.BlockPSDAPGGlassEndBase mtr.block.IBlock mtr.render.RenderSignalBase mtr.client.TrainClientRegistry mtr.client.TrainProperties mtr.client.ClientCache mtr.client.DoorAnimationType mtr.render.RenderPSDAPGDoor mtr.render.RenderAPGGlass mtr.render.RenderPSDTop mtr.render.RenderPIDS mtr.data.DataCache mtr.data.RailwayData mtr.data.Platform mtr.data.Station mtr.data.Depot mtr.data.Siding mtr.data.Route mtr.data.Rail mtr.data.RailType mtr.path.PathData mtr.data.TrainServer mtr.data.TransportMode mtr.block.BlockNode mtr.block.BlockSignalLightBase mtr.block.BlockSignalSemaphoreBase mtr.mappings.BlockEntityMapper mtr.client.RouteMapGenerator mtr.client.ScrollingText"
for v in 118 1192 1194 1201; do
  out="temp/portaudit/$v.txt"
  : > "$out"
  for c in $CLASSES; do
    echo "### $c" >> "$out"
    "$JAVAP" -classpath "${JARS[$v]}" "$c" 2>/dev/null | grep -vE '^(Compiled from|Warning)' >> "$out" || echo "MISSING" >> "$out"
  done
done
