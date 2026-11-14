// 饮料售卖机的渲染器：按方块状态选 top / bottom_closed / bottom_opened 三份原始模型。
package dev.js.mtrheadlight.xiamen.drinks;

import dev.js.mtrheadlight.rawmodel.RawMesh;
import dev.js.mtrheadlight.rawmodel.RawModelJson;
import dev.js.mtrheadlight.rawmodel.RawModelRenderer;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.World;

/**
 * 饮料售卖机（{@code mtrlte:drinks_machine_top} / {@code _bottom}）的方块实体渲染器。
 *
 * <h2>三份模型，看状态挑</h2>
 * <ul>
 *   <li>上半 → {@code rawmodels/drinks_machine_top.json}（永远同一份）；</li>
 *   <li>下半 + {@code open=false} → {@code drinks_machine_bottom_closed.json}；</li>
 *   <li>下半 + {@code open=true} → {@code drinks_machine_bottom_opened.json}（取货口翻板放下来）。</li>
 * </ul>
 * <p>挑哪份由 {@link DrinksMachineBlock#rawModel(BlockState)} 说，这里只负责画。
 * 两份 Blockbench 模型的局部坐标都是「自己那一格的 0..16」（素材本来就这么切的），
 * 所以不需要任何 y 偏移；{@code open} 一变，方块状态同步到客户端、下一帧就换模型，
 * 不需要额外的包。
 *
 * <h2>朝向</h2>
 * <p>与其它厦门机器的约定一致：{@code facing=south → y=0, west → 90, north → 180, east → 270}
 * （模型原生正面朝 +Z）。原版 {@code "y": n} 等价于绕方块中心转 {@code -n} 度，
 * 所以这里是 {@code rotationDegrees(-FACING.asRotation())}。
 *
 * <h2>渲染层</h2>
 * <p>{@code RenderLayer.getEntityCutout(texture)}：贴图直绑（方块模型已经是空的，不进方块图集），
 * alpha 裁掉，背面剔除开着。贴图 {@code mtrlte:block/drinks_machine}
 * （模型里写的是 {@code drinks_machine}，由 {@link RawModelJson} 补成
 * {@code textures/block/drinks_machine.png}）。
 *
 * <h2 id="width">{@link #MODEL_X_SCALE}：素材是两格宽的</h2>
 * <p>两份模型的实际范围是 {@code x = -8..24}（<b>32 单位 = 两格宽</b>）、{@code y = 0..16}、
 * {@code z = 0..16}，也就是一台「双开门」的宽机器。默认 {@code 1.0}：原样画，向左右各探出半格
 * （贴图一个像素都不变形，柜体 / 玻璃 / 取货口的位置和 Blockbench 里一模一样）。
 * 想把它塞进一格就把它改成 {@code 0.5F}：绕格心把 X 压一半，代价是所有面板被横向拉扁一半。
 */
public class DrinksMachineRenderer implements BlockEntityRenderer<DrinksMachineBlockEntity> {

    /**
     * 模型 X 方向的缩放（1.0 = 原样，见<a href="#width">类注释</a>）。
     * 绕格心缩放，所以 0.5 时机器正好落在 0..16 之内。
     */
    public static final float MODEL_X_SCALE = 1.0F;

    public DrinksMachineRenderer(final BlockEntityRendererFactory.Context context) {
    }

    @Override
    public void render(final DrinksMachineBlockEntity entity, final float tickDelta,
                       final MatrixStack matrices, final VertexConsumerProvider vertexConsumers,
                       final int light, final int overlay) {
        final World world = entity.getWorld();
        final BlockState state = world != null ? world.getBlockState(entity.getPos()) : entity.getCachedState();
        if (!(state.getBlock() instanceof DrinksMachineBlock)) {
            return;
        }

        final RawMesh mesh = RawModelJson.get(RawModelJson.id(DrinksMachineBlock.rawModel(state)));
        if (mesh == null) {
            return;
        }

        final VertexConsumer consumer = vertexConsumers.getBuffer(RawModelRenderer.cutoutLayer(mesh));

        matrices.push();
        matrices.translate(0.5D, 0.0D, 0.5D);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-state.get(HorizontalFacingBlock.FACING).asRotation()));
        matrices.translate(-0.5D, 0.0D, -0.5D);
        if (MODEL_X_SCALE != 1.0F) {
            // 绕格心（x = 0.5 格）压 X：只有「塞进一格」时才需要。
            matrices.translate(0.5D, 0.0D, 0.0D);
            matrices.scale(MODEL_X_SCALE, 1.0F, 1.0F);
            matrices.translate(-0.5D, 0.0D, 0.0D);
        }
        RawModelRenderer.render(matrices, consumer, mesh, light, overlay);
        matrices.pop();
    }
}
