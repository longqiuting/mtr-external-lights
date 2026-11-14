// 方速厦门屏蔽门的玻璃板渲染器：一块方块画「左半 + 右半」两组几何，按 facing 绕 Y 转一次。
package dev.js.mtrheadlight.xiamen.fangsu;

import java.util.List;
import java.util.Map;

import dev.js.mtrheadlight.rawmodel.RawMesh;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.World;

/**
 * 玻璃板（{@code fangsu_psd_screendoor_high_glass}）的方块实体渲染器。
 *
 * <h2>画什么：两组，左右各一</h2>
 * <p>和门扇完全同一套管线（{@link FangsuPsdBlocks#meshes} → {@code o} 组 → 按材质拆开的
 * {@link RawMesh}），区别有两条：
 * <ol>
 *   <li><b>一块方块画两半</b>：{@code glass_left_<形态>} 与 {@code glass_right_<形态>} 各画一遍
 *       （对方速就是 {@code whenRendering()} 里 {@code drawModel(dhmLeft)} + {@code drawModel(dhmRight)}）。
 *       两半各 0.5 格，合起来才是完整一格 —— 不是「两个方块各画一半」；</li>
 *   <li><b>两半的形态各自独立</b>：左半边取 {@link FangsuPsdGlassBlock#FORM_LEFT}，右半边取
 *       {@link FangsuPsdGlassBlock#FORM_RIGHT}（{@code glass.left[]} / {@code glass.right[]} 两份列表），
 *       所以「靠门那半是 {@code door}、另一半是纯玻璃」这种摆法画得出来。
 *       这两条属性里存的是<b>已求好的形态</b> —— 自动档（{@link FangsuPsdGlassBlock#AUTO_LEFT} /
 *       {@link FangsuPsdGlassBlock#AUTO_RIGHT}）的那一半由 {@code glass.auto[]} 规则算出结果后写进去，
 *       所以渲染器不用知道「自动还是手动」，直接按形态画就行（和碰撞盒同一份数据）。</li>
 * </ol>
 * 没有任何门扇变换 —— 玻璃板是不动的。
 *
 * <h2>朝向 / 渲染层</h2>
 * <p>和 {@link FangsuPsdDraw} 逐字一致：绕方块中心 {@code (0.5, ·, 0.5)} 转
 * {@code NEGATIVE_Y × facing 角度}（blockstate 的 {@code y} 是俯视顺时针；这等价于方速自己的
 * {@code rotateY(-toRadians(facing.getHorizontal()) + PI)}），
 * {@code RenderLayer.getEntityCutout(贴图)} 直绑贴图（不进方块图集），alpha &lt; 0.1 裁掉；
 * 贴图名以 {@code ltg.png} 结尾的那份（灯带）在注册期被标成 {@code fullBright}，画出来是亮的。
 *
 * <p>没有 {@code HALF} 判断：玻璃板是<b>单格方块</b>，一块就把 3 格高的板画完（碰撞也是 3 格高，
 * 见 {@link FangsuPsdGlassBlock}）。
 */
public class FangsuPsdGlassRenderer implements BlockEntityRenderer<FangsuPsdGlassBlock.FangsuPsdGlassEntity> {

    public FangsuPsdGlassRenderer(final BlockEntityRendererFactory.Context context) {
    }

    @Override
    public void render(final FangsuPsdGlassBlock.FangsuPsdGlassEntity entity, final float tickDelta,
                       final MatrixStack matrices, final VertexConsumerProvider vertexConsumers,
                       final int light, final int overlay) {
        final World world = entity.getWorld();
        final BlockState state = world != null ? world.getBlockState(entity.getPos()) : entity.getCachedState();
        if (!(state.getBlock() instanceof FangsuPsdGlassBlock block)) {
            return;
        }
        final FangsuPsdGlassDef def = block.def();
        final Map<String, List<RawMesh>> meshes = FangsuPsdBlocks.meshes(def);
        // 左右两半各一组几何：一格 = 左半（0.5 格）+ 右半（0.5 格）。
        final List<RawMesh> left = meshes.get(def.leftForm(block.leftFormOf(state)).group());
        final List<RawMesh> right = meshes.get(def.rightForm(block.rightFormOf(state)).group());
        if (left == null && right == null) {
            return;
        }

        final Direction facing = FangsuPsdDraw.facingOf(state);
        matrices.push();
        try {
            if (facing != Direction.NORTH) {
                matrices.translate(0.5F, 0.0F, 0.5F);
                matrices.multiply(RotationAxis.NEGATIVE_Y.rotationDegrees(degreesOf(facing)));
                matrices.translate(-0.5F, 0.0F, -0.5F);
            }
            FangsuPsdDraw.drawGroup(matrices, vertexConsumers, left, light, overlay);
            FangsuPsdDraw.drawGroup(matrices, vertexConsumers, right, light, overlay);
        } finally {
            matrices.pop();
        }
    }

    /** facing → 俯视顺时针角度（{@code north=0 / east=90 / south=180 / west=270}）。 */
    private static float degreesOf(final Direction facing) {
        return switch (facing) {
            case EAST -> 90.0F;
            case SOUTH -> 180.0F;
            case WEST -> 270.0F;
            default -> 0.0F;
        };
    }
}
