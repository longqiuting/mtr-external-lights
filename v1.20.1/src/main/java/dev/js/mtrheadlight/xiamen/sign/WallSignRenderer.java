// 贴墙标识的渲染器：MTR 4.0.5 站名出入口那块板 + 站名文字（自动连接成条时整条铺满、只在左下角那一格落笔）。
package dev.js.mtrheadlight.xiamen.sign;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.xiamen.XiamenObjects;
import mtr.client.ClientData;
import mtr.data.RailwayData;
import mtr.data.Station;
import mtr.mappings.BlockEntityRendererMapper;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.World;

/**
 * 贴墙标识 {@code mtrlte:wall_sign_*} 的渲染器（站名出入口塔式款）。
 *
 * <h2>方块模型只是「灯」，画面全在这里</h2>
 * <p>{@code station_name_entrance_tall.json} 只有一块零厚度的发光板（MTR 4.0.5 原样），
 * 牌子的颜色和站名都是 BER 画的 —— MTR 的 {@code RenderStationNameBase} /
 * {@code RenderStationNameTall} 也是这么分工的，只是它们挂在 MTR 自己的方块实体类型上，
 * 我们这块方块用不了，所以按同一条链路自己画（不继承 MTR 的渲染器：
 * 它的泛型参数写死成 {@code BlockStationNameTallBase$TileEntityStationNameTallBase}）。
 *
 * <h2>数据链路（与 MTR 逐条对齐）</h2>
 * <pre>
 *   站名 / 站色 = RailwayData.getStation(ClientData.STATIONS, ClientData.DATA_CACHE, pos)   // 按位置
 *   形态        = blockstate 的 style（0 矮 / 1 高）                                        // 刷子切换
 *   整条尺寸    = WallSignBlock.runOf(...)：link 位掩码算出的 width × height
 * </pre>
 * 找不到车站（站在站外 / 还没建站）就画 MTR 的「未命名」文案
 * （{@code gui.mtr.untitled}，和 MTR 渲染器同一句）。
 *
 * <h2>只在「左下角那一格」落笔</h2>
 * <p>一条连着放了三格的牌子，三格各有自己的方块实体，三个都会进这里。
 * 只有 {@link WallSignBlock.Run#anchor()}（左边没连、下面没连的那一格）才画，
 * 而且按 {@code width × height} <b>铺满整条</b>：站名在整条上居中一次，不会重复三遍。
 *
 * <h2>变换链（改之前先读这段）</h2>
 * <pre>
 *   translate(0.5, 0, 0.5)                       // 方块中心（y 留 0，方便下面直接用「格内高度」）
 *   multiply(rotateY(-blockRotation(facing)))    // 与 blockstate 的 y 同一套角度
 *   translate(-0.5, 0, -0.5)                     // 回到模型空间：x/y/z 0..1，+Z 朝外
 *   translate(0, 0, PLATE_Z)  scale(1/16)        // 进入「牌子像素空间」：1 单位 = 1 px，+Y 朝上
 * </pre>
 * 显式取 {@code NEGATIVE_Y} 是因为 blockstate 的 {@code y} 是「从上往下看顺时针」，
 * 和 {@link WallSignBlock#blockRotation} 是同一张表，两块地方不可能各走各的
 * （{@code dep_on_wall} 踩过这个坑，照它的写法来）。
 *
 * <h2>文字坐标系</h2>
 * <p>{@code TextRenderer} 是 GUI 坐标（<b>+X 向右、+Y 向下</b>），所以我们再 {@code scale(s, -s, 1)}
 * 把 Y 翻过来 —— 负 Y 缩放会让绕序反过来，但 {@code RenderLayer.getText} 是关掉背面剔除的
 * （{@code disableCull()}），正面背面一样画。{@code PidsXiamenText} 用的是同一套做法。
 */
public class WallSignRenderer extends BlockEntityRendererMapper<WallSignBlockEntity> {

    /** 纯白贴图（1 px 也能当色块用，颜色由顶点色的乘法决定）。 */
    public static final Identifier WHITE_TEXTURE =
            new Identifier(XiamenObjects.NAMESPACE, "textures/block/white.png");

    /** 牌面平面：模型里那块板在 {@code z = 0.05/16 = 0.003125}，往外挪 3/1000 格防 z-fighting。 */
    private static final float PLATE_Z = 0.005F;

    /** 板上的两张附件（品牌 logo / 文字）再各往外一层，单位 px（1 px = 1/16 格）。 */
    private static final float LOGO_Z = 0.12F;
    private static final float TEXT_Z = 0.24F;

    /** 矮形态的上下边（px，与 {@code station_name_entrance_short.json} 一致）。 */
    private static final float SHORT_BOTTOM = 4.0F;
    private static final float SHORT_TOP = 12.0F;

    /** 字形高 8 px（{@code TextRenderer} 的 {@code FONT_HEIGHT}）。 */
    private static final float FONT_HEIGHT = 8.0F;

    /** 站名左右各留 1 px，别贴着牌子边。 */
    private static final float TEXT_INSET = 1.0F;

    /** 品牌 logo 的边长 / 左边距（只在牌子 >= 2 格时画，1 格里没地方同时放 logo 和字）。 */
    private static final float LOGO_SIZE = 10.0F;
    private static final float LOGO_INSET = 2.0F;

    /** 没有车站 / 车站没设颜色时牌子的底色（深蓝，和 MTR 的 {@code dark_blue} 同色系）。 */
    private static final int DEFAULT_PLATE_COLOR = 0x1B2A6B;

    /** 渲染线程上只报一次错（否则每帧一条，日志会被刷爆）。 */
    private static boolean warned;

    public WallSignRenderer(final BlockEntityRendererFactory.Context context) {
        super(context.getRenderDispatcher());
    }

    @Override
    public void render(final WallSignBlockEntity entity, final float tickDelta, final MatrixStack matrices,
                       final VertexConsumerProvider vertexConsumers, final int light, final int overlay) {
        try {
            renderSafe(entity, matrices, vertexConsumers);
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                LteLog.LOGGER.error("[lte] wall sign render failed", t);
            }
        }
    }

    private void renderSafe(final WallSignBlockEntity entity, final MatrixStack matrices,
                            final VertexConsumerProvider vertexConsumers) {
        final World world = entity.getWorld();
        if (world == null) {
            return;
        }
        final BlockPos pos = entity.getPos();
        final BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof WallSignBlock block)) {
            return;
        }
        final Direction facing = state.get(WallSignBlock.FACING);
        final WallSignBlock.Run run = WallSignBlock.runOf(world, pos, state);
        if (!run.anchor()) {
            return;
        }

        final boolean tall = state.get(WallSignBlock.STYLE) != 0;
        final float plateBottom = tall ? 0.0F : SHORT_BOTTOM;
        final float plateTop = tall ? 16.0F * run.height() : SHORT_TOP;
        final float plateWidth = 16.0F * run.width();

        final Station station = stationAt(pos);
        final String name = station == null || station.name == null || station.name.isEmpty()
                ? Text.translatable("gui.mtr.untitled").getString()
                : station.name;
        final int plateColor = station == null || station.color == 0 ? DEFAULT_PLATE_COLOR : station.color;
        final int textColor = isDark(plateColor) ? 0xFFFFFF : 0x000000;

        matrices.push();
        try {
            // 1) 模型空间（x/y/z 0..1，+Z 朝外，和方块模型同一个角度）
            matrices.translate(0.5D, 0.0D, 0.5D);
            matrices.multiply(RotationAxis.NEGATIVE_Y.rotationDegrees(WallSignBlock.blockRotation(facing)));
            matrices.translate(-0.5D, 0.0D, -0.5D);

            // 2) 牌子像素空间：1 单位 = 1 px（x 从最左一格的左边起、y 从本格底面起）
            matrices.push();
            try {
                matrices.translate(0.0D, 0.0D, PLATE_Z);
                matrices.scale(1.0F / 16.0F, 1.0F / 16.0F, 1.0F / 16.0F);

                // 牌面：整条（width × height）一块，颜色取车站色
                drawQuad(vertexConsumers, matrices, 0.0F, plateBottom, plateWidth, plateTop, plateColor);
                // 品牌 logo：只在 >= 2 格的牌子上画，摆在左下角
                if (run.width() >= 2) {
                    drawBrandLogo(vertexConsumers, matrices, block.logo(), plateBottom, plateTop);
                }
            } finally {
                matrices.pop();
            }

            // 3) 站名：在整条牌面上居中
            final float textLeft = run.width() >= 2
                    ? LOGO_INSET + LOGO_SIZE + TEXT_INSET
                    : TEXT_INSET;
            final float textRight = plateWidth - TEXT_INSET;
            drawStationName(matrices, vertexConsumers, name, textLeft, textRight,
                    plateBottom, plateTop, textColor);
        } finally {
            matrices.pop();
        }
    }

    // ------------------------------------------------------------------ 画

    /**
     * 站名：字号取「塞得进可用宽高」的那个值（1 px = 1 px 起，最多不变形放大到 1.0），
     * 在给定区域里水平、竖直居中。
     *
     * @param leftPx    可用区域左边界（px，牌子像素空间）
     * @param rightPx   右边界
     * @param bottomPx  牌面下边
     * @param topPx     牌面上边
     */
    private static void drawStationName(final MatrixStack matrices,
                                        final VertexConsumerProvider vertexConsumers,
                                        final String name, final float leftPx, final float rightPx,
                                        final float bottomPx, final float topPx, final int color) {
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.textRenderer == null || name == null || name.isEmpty()) {
            return;
        }
        final TextRenderer textRenderer = client.textRenderer;
        final float available = Math.max(1.0F, rightPx - leftPx);
        final float widthPx = Math.max(1.0F, textRenderer.getWidth(name));
        // 竖着：字形 8 px，上下各留 1 px（矮形态牌面 8 px 高 → 字号 0.75）；横着按可用宽缩。
        final float availableHeight = Math.max(1.0F, (topPx - bottomPx) - 2.0F);
        final float scale = Math.min(1.0F, Math.min(available / widthPx, availableHeight / FONT_HEIGHT));
        if (scale <= 0.0F) {
            return;
        }
        final float centerYPx = (bottomPx + topPx) * 0.5F;

        matrices.push();
        try {
            // 模型空间 → 牌子像素空间（1 单位 = 1 px，x/y 原点 = 本格左下角），
            // 再挪到文字原点、把 Y 翻成 GUI 的「向下为正」。
            matrices.translate(0.0D, 0.0D, PLATE_Z + TEXT_Z / 16.0F);
            matrices.scale(1.0F / 16.0F, 1.0F / 16.0F, 1.0F / 16.0F);
            matrices.translate(leftPx + available * 0.5F, centerYPx, 0.0F);
            matrices.scale(scale, -scale, 1.0F);
            textRenderer.draw(name, -widthPx * 0.5F, -FONT_HEIGHT * 0.5F, color, false,
                    matrices.peek().getPositionMatrix(), vertexConsumers,
                    TextRenderer.TextLayerType.NORMAL, 0,
                    LightmapTextureManager.MAX_LIGHT_COORDINATE);
        } finally {
            matrices.pop();
        }
    }

    /** 品牌 logo（厦门 / 角美 / LTE 各一张，见 {@link WallSignObjects}）：10×10 px，竖直居中。 */
    private static void drawBrandLogo(final VertexConsumerProvider vertexConsumers, final MatrixStack matrices,
                                      final Identifier texture, final float plateBottom, final float plateTop) {
        final float bottom = (plateBottom + plateTop) * 0.5F - LOGO_SIZE * 0.5F;
        matrices.push();
        try {
            matrices.translate(0.0F, 0.0F, LOGO_Z);
            final VertexConsumer buffer = vertexConsumers.getBuffer(RenderLayer.getEntityTranslucentEmissive(texture));
            final MatrixStack.Entry entry = matrices.peek();
            final Matrix4f position = entry.getPositionMatrix();
            final Matrix3f normal = entry.getNormalMatrix();
            final float left = LOGO_INSET;
            final float right = LOGO_INSET + LOGO_SIZE;
            final float top = bottom + LOGO_SIZE;
            // 白（255,255,255）→ 贴图原样，不额外染色。
            vertex(buffer, position, normal, left, bottom, 0.0F, 0.0F, 255, 255, 255);
            vertex(buffer, position, normal, right, bottom, 1.0F, 0.0F, 255, 255, 255);
            vertex(buffer, position, normal, right, top, 1.0F, 1.0F, 255, 255, 255);
            vertex(buffer, position, normal, left, top, 0.0F, 1.0F, 255, 255, 255);
        } finally {
            matrices.pop();
        }
    }

    /** 一块纯色四边形（纯白贴图 × 顶点色），法线朝 +Z（朝外）。 */
    private static void drawQuad(final VertexConsumerProvider vertexConsumers, final MatrixStack matrices,
                                 final float left, final float bottom, final float right, final float top,
                                 final int color) {
        final VertexConsumer buffer = vertexConsumers.getBuffer(RenderLayer.getEntityTranslucentEmissive(WHITE_TEXTURE));
        final MatrixStack.Entry entry = matrices.peek();
        final Matrix4f position = entry.getPositionMatrix();
        final Matrix3f normal = entry.getNormalMatrix();
        final int r = (color >> 16) & 0xFF;
        final int g = (color >> 8) & 0xFF;
        final int b = color & 0xFF;
        // 左下 → 右下 → 右上 → 左上：从 +Z 看是逆时针（正面）。
        vertex(buffer, position, normal, left, bottom, 0.5F, 0.5F, r, g, b);
        vertex(buffer, position, normal, right, bottom, 0.5F, 0.5F, r, g, b);
        vertex(buffer, position, normal, right, top, 0.5F, 0.5F, r, g, b);
        vertex(buffer, position, normal, left, top, 0.5F, 0.5F, r, g, b);
    }

    /** 自发光层的顶点格式是 {@code POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL}，六样都不能少。 */
    private static void vertex(final VertexConsumer buffer, final Matrix4f position, final Matrix3f normal,
                               final float x, final float y, final float u, final float v,
                               final int r, final int g, final int b) {
        buffer.vertex(position, x, y, 0.0F)
                .color(r, g, b, 255)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(LightmapTextureManager.MAX_LIGHT_COORDINATE)
                .normal(normal, 0.0F, 0.0F, 1.0F)
                .next();
    }

    // ------------------------------------------------------------------ 数据

    /** 这一格所在车站（MTR 的按位置判定，和 {@code RenderStationNameBase} 同一条链路）。 */
    private static Station stationAt(final BlockPos pos) {
        try {
            return RailwayData.getStation(ClientData.STATIONS, ClientData.DATA_CACHE, pos);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 这颜色算不算「深色」（深底写字用白字）。按 sRGB 亮度加权，阈值取中。 */
    private static boolean isDark(final int color) {
        final int r = (color >> 16) & 0xFF;
        final int g = (color >> 8) & 0xFF;
        final int b = color & 0xFF;
        return 0.299D * r + 0.587D * g + 0.114D * b < 140.0D;
    }

    /** 整条牌子可能跨好几格，别让视锥按本格的包围盒把它整块剔掉。 */
    @Override
    public boolean rendersOutsideBoundingBox(final WallSignBlockEntity entity) {
        return true;
    }
}
