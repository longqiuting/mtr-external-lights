// PSD-LCD 挂板的渲染器：整屏交给 Java2D 画成**一张**位图（PsdLcdPainter），再贴成一个四边形。
//
// 只有一个绘制层。以前这里还有「广告层」「滚动标语层」两个额外的 quad / MC 文字层，
// 结果屏幕上的字出现两遍（同位图里的字 + 叠在上面的文字层）、而且三个层各自的深度与
// 尺寸都对不上 —— 现在全部并进位图：文字 / 图标 / 标语 / 广告文字都画在同一个 BufferedImage
// 里，渲染器只发一个四边形。
//
// 四边形的四个顶点**就是**模型里凹槽底那张面的四个角，由
// PsdLcdLayout#windowQuad 算出（不做任何矩阵旋转）。所以：
//   · 窗口矩形 x 0…16、y 1.5…8.5、z = 10.2（凹槽底；四边形再往前 0.016px = 10.184）——不多一个像素；
//   · 位图宽高比 = 窗口宽高比（16:7 / 段），内容不会被拉伸（见 tools/psd-lcd-fit-check.js）。
//
// 剪刀把某一格剪成「只剩上半格」（PsdLcdBlock.HALF_CUT = true）以后，这一格的板子只占
// y 7.5…16、屏幕口在 y 9…16（模型 psd_lcd_xiamen_half.json）。渲染上只有一件事变了：
// **这一格的四边形 y 带从 1.5…8.5 换成 9…16**，宽度仍然是整格（一格 = 一整段）。
// 被剪掉的下半截是真的没有几何（有洞就是洞），不是把画面裁掉一半。
//
// 另外还画一条**线路色带**（MTR 式，见 PsdLcdStrip / PsdLcdLayout#stripQuad）：板子下沿那条
// 0.5px 的前脸上，颜色取这个站台经过的线路色。
//
// 旧版错在把「屏幕面中线 y = 5px」当成了四边形原点，再 rotZ(180) 让画面往下长，
// 于是 7px 高的画面从 y=5px 一直挂到 y=-2px（吊板下面），看起来像「多画了一遍」。
package dev.js.mtrheadlight.xiamen.pids;

import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.client.RenderDistance;
import mtr.client.ClientData;
import mtr.data.RailwayData;
import mtr.data.Route;
import mtr.data.ScheduleEntry;
import mtr.data.Station;
import mtr.data.TrainClient;
import mtr.mappings.BlockEntityRendererMapper;
import mtr.render.MoreRenderLayers;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/** 厦门地铁 PSD-LCD 挂板（{@code mtrlte:psd_lcd_xiamen}）的方块实体渲染器。 */
public class PsdLcdRenderer extends BlockEntityRendererMapper<PsdLcdBlockEntity> {

    /**
     * <b>兜底</b>换图节奏：标语是空的时候（这张广告没有 {@code text}，PSD-LCD 的标语条也就没字）
     * 才回落到「每 200 tick（10 秒）换一张」。
     *
     * <p>正常情况<b>不用这个数</b>：换图触发点是「这一张的标语滚完一轮」
     * （{@link PidsXiamenTicker#scrollSpanTicks}，与厦门 PIDS 的
     * {@code PidsXiamenRenderer#slideOf} 同一套算法、同一个速度表），
     * 见 {@link #adOf(PsdLcdBlockEntity, World)}。
     */
    private static final int AD_ROTATE_TICKS = 200;

    /**
     * 滚动标语那条四边形沿法线再外推多少（16 分之格）：1/32 格 = 0.5px 的 1/32。
     *
     * <p>beacon 渲染层不写深度，「谁在上」其实只由绘制顺序决定；这个外推只是让它在
     * 任何深度精度下都不会和整屏四边形共面（两者差 0.001px 就已经足够）。
     */
    private static final float TICKER_LIFT_PX = 0.032F;

    /**
     * 每块屏自己的广告轮播状态（{@code world|anchor} → 播到第几张、这一张播到哪个 tick）。
     *
     * <p>为什么不是全局一个 index：广告集的起点是每块屏自己的 {@code advertisementIndex}，
     * 一个人多段拼接的长屏和隔壁单段小屏不该同拍换图（PIDS 那边也是每块屏各算各的时间线）。
     * 这里是<b>纯客户端</b>的记账 —— 真正的 {@code advertisementIndex} 存在方块实体里、
     * 由刷子界面改；渲染线程不该去写 NBT，所以「滚完一轮 +1」只记在这张表里。
     */
    private static final java.util.Map<String, AdRotation> ROTATIONS = new java.util.HashMap<>();

    /** 一块屏的轮播状态。 */
    private static final class AdRotation {
        /** 当前生效的那份广告集（{@link PidsAdSetCache#activeAds()} 返回的同一个 List 实例）。 */
        Object setRef;
        /** 那时候有几张（集合换了 / 张数变了就重来）。 */
        int count;
        /** 现在播的是第几张（已经 {@code floorMod} 过的下标）。 */
        int slot;
        /** 这一张的标语<b>开始</b>滚的时刻（世界 tick）—— 标语相位从这里算起。 */
        long passStart;
        /** 这一张的标语滚完一轮的时刻（世界 tick）= 换下一张的时刻。 */
        long passEnd;
    }

    /**
     * 纯色几何（线路色带）用的**载体贴图**：{@code mtrlte:textures/block/white.png}，
     * 16×16、每个像素都是不透明的纯白 —— 顶点色 × 白图 = 纯色。
     *
     * <p>为什么不会像上一版那样「黑紫方块」：{@link RenderLayer#getEntitySolid(Identifier)}
     * 是**按资源路径直绑贴图**（{@code Sampler0} 读原图），<b>不经过方块图集</b>，
     * 所以这张图不需要被任何模型 json 引用、也不需要 {@code block/white} 这种精灵名。
     */
    private static final Identifier SOLID_TEXTURE = new Identifier("mtrlte", "textures/block/white.png");

    /**
     * 受光的实体层：{@code entity_solid} 程序，顶点格式
     * {@code POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL}（<b>有 lightmap 元素</b>），
     * 写深度、开剔除、不混合。
     *
     * <p>颜色 = 顶点色 × 贴图 × lightmap × 方向性明暗（{@code minecraft_mix_light}），
     * 所以色带和旁边的普通方块吃同一套世界光照，不会「看着自己在发光」。
     */
    private static final RenderLayer SOLID_LAYER = RenderLayer.getEntitySolid(SOLID_TEXTURE);

    private boolean warned;
    /** 「拿不到实时编组 → 编组行留空」这条 warning 只打一次（consistOf 每帧都在跑）。 */
    private static boolean carsWarned;
    private long lastSweep;

    public PsdLcdRenderer(final BlockEntityRendererFactory.Context context) {
        super(context.getRenderDispatcher());
    }

    // ------------------------------------------------------------------ 视距

    @Override
    public boolean isInRenderDistance(final PsdLcdBlockEntity entity, final net.minecraft.util.math.Vec3d cameraPos) {
        return RenderDistance.inRange(entity, cameraPos);
    }

    @Override
    public boolean rendersOutsideBoundingBox(final PsdLcdBlockEntity entity) {
        return true;
    }

    @Override
    public int getRenderDistance() {
        return 64;
    }

    // ------------------------------------------------------------------ 入口

    /**
     * 屏幕**保持满亮**（getLight + FULL_LIGHT，和厦门 PIDS 同一套：屏幕本来就该像屏幕一样亮）。
     * **线路色带**（一块普通方块表面）以前走 getDebugQuads()（POSITION_COLOR，没有 lightmap 元素）→ 恒满亮，
     * 所以改成受世界光照：lightmap 坐标取那个方块位置的
     * {@code WorldRenderer#getLightmapCoordinates(world, pos)}（天空光 + 方块光打包，夜间由 lightmap 纹理里的环境暗度压暗）。
     * 颜色仍走顶点色 tint（白图 × 顶点色 = 纯色），多条线路一条一个颜色。
     */
    @Override
    public void render(final PsdLcdBlockEntity entity, final float tickDelta, final MatrixStack matrices,
                       final VertexConsumerProvider vertexConsumers, final int light, final int overlay) {
        try {
            renderSafe(entity, matrices, vertexConsumers);
        } catch (final Throwable t) {
            if (!this.warned) {
                this.warned = true;
                LteLog.LOGGER.error("[lte] psd lcd render failed", t);
            }
        }
    }

    private void renderSafe(final PsdLcdBlockEntity entity, final MatrixStack matrices,
                            final VertexConsumerProvider vertexConsumers) {
        final World world = entity.getWorld();
        if (world == null) {
            return;
        }
        this.sweep(world);
        final BlockPos pos = entity.getPos();
        final BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof PsdLcdBlock)) {
            return;
        }
        // 整条链只由最左一格画（每次渲染都会重新算，拆 / 接一格立刻生效）。
        final BlockPos anchor = PsdLcdBlock.anchorOf(world, pos);
        if (!anchor.equals(pos)) {
            return;
        }
        final Direction facing = state.get(PsdLcdBlock.FACING);
        final PsdLcdLayout.Face face = faceOf(facing);
        // 一格 = 一整段（16px 宽）：剪刀不改宽度，只改这一格的 y 带，所以链的宽度永远是
        // 段数 × 16px，每格的四边形就是自己的那一整段。
        final java.util.List<BlockPos> chain = PsdLcdBlock.run(world, anchor);
        final boolean[] cuts = cutsOf(world, chain, state);
        final float extent = Math.max(1, chain.size()) * 16.0F;

        final int bitmapW = PsdLcdLayout.bitmapWidthFor(extent);
        // 位图高度跟着窗口高度走（整块 7px、剪后 9…16 也是 7px ⇒ 455px，两条一样）。
        final int bitmapH = PsdLcdLayout.bitmapHeight(anyCut(cuts));

        final Content content = contentOf(entity, world, anchor, extent, bitmapW, bitmapH);
        final String shapeKey = shapeKey(cuts);
        final String key = world.getRegistryKey().getValue() + "|" + anchor.asLong() + "|" + shapeKey;
        final PsdLcdTextures.Entry texture = PsdLcdTextures.acquire(key, bitmapW, bitmapH);
        if (texture == null) {
            return;
        }
        // 内容变了才重画（时钟 / 倒计时是分钟级，一般一分钟一次）。
        // 标语滚动相位**不**在哈希里：滚动标语走下面那张小贴图，不重画整屏。
        if (!content.hash().equals(texture.hash)) {
            final BufferedImage image = PsdLcdPainter.paint(content.content(), PsdLcdAssets.get(),
                    content.ad(), bitmapW, bitmapH);
            texture.hash = content.hash();
            PsdLcdTextures.upload(texture, image);
            // 换了广告 / 换了标语：滚动小贴图下一帧要重画（它的触发条件是「相位像素变了」）
            PsdLcdTextures.invalidateTicker(key);
        }

        final float[] normal = PsdLcdLayout.screenNormal(face);
        final Matrix4f matrix = matrices.peek().getPositionMatrix();
        final Matrix3f normalMatrix = matrices.peek().getNormalMatrix();

        // MTR 有时递过来的不是 Immediate（它自己的队列）：退回客户端的实体缓冲区并自己 flush。
        final VertexConsumerProvider.Immediate immediate;
        final boolean flush;
        if (vertexConsumers instanceof VertexConsumerProvider.Immediate direct) {
            immediate = direct;
            flush = false;
        } else {
            immediate = net.minecraft.client.MinecraftClient.getInstance()
                    .getBufferBuilders().getEntityVertexConsumers();
            flush = true;
        }

        matrices.push();
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        com.mojang.blaze3d.systems.RenderSystem.disableCull();
        try {
            final VertexConsumer vc = immediate.getBuffer(MoreRenderLayers.getLight(texture.id, false));
            // 一格一个四边形：u 取这一格在整条链里的那一段（所以整条链是一张连续画面），
            // v 永远是 0…1 —— 每格四边形的高度正好等于窗口高度，v 不做任何拉伸。
            // y 带按这一格自己的 HALF_CUT 取：没剪 1.5…8.5，剪过 9…16。
            for (int i = 0; i < cuts.length; i++) {
                final float start = i * 16.0F;
                final float end = start + 16.0F;
                final float[][] quad = PsdLcdLayout.windowQuad(face, start, end, cuts[i]);
                final float u0 = start / extent;
                final float u1 = end / extent;
                vertex(vc, matrix, normalMatrix, quad[0][0], quad[0][1], quad[0][2], u0, 0.0F, normal);
                vertex(vc, matrix, normalMatrix, quad[1][0], quad[1][1], quad[1][2], u0, 1.0F, normal);
                vertex(vc, matrix, normalMatrix, quad[2][0], quad[2][1], quad[2][2], u1, 1.0F, normal);
                vertex(vc, matrix, normalMatrix, quad[3][0], quad[3][1], quad[3][2], u1, 0.0F, normal);
            }
            // ---- 滚动标语：压在屏幕面上的一条小贴图（**短句长句都画**，一律滚动）
            drawTicker(content, cuts, extent, bitmapW, bitmapH, key, face,
                    matrix, normalMatrix, normal, immediate);
            // ---- 线路色带（MTR 式）：板子下沿那条 0.5px 的前脸（白图 × 顶点色，受世界光照）
            if (PidsXiamenSettings.lineStrip()) {
                drawStrip(entity, world, matrix, normalMatrix, normal, face, cuts, chain, immediate);
            }
        } finally {
            com.mojang.blaze3d.systems.RenderSystem.enableCull();
            matrices.pop();
            if (flush) {
                immediate.draw();
            }
        }
    }

    // ------------------------------------------------------------------ 剪没剪

    /**
     * 整条链每格「剪过没有」（{@link PsdLcdBlock#HALF_CUT}），下标和 {@link PsdLcdBlock#run} 一致。
     *
     * <p>第 i 格的 FACING 理论上和 anchor 一致（{@link PsdLcdBlock#run} 只认同类方块），
     * 但剪没剪是每格自己的事 —— 一条链可以「整块 + 剪过 + 整块」这样混着。
     */
    private static boolean[] cutsOf(final World world, final java.util.List<BlockPos> chain,
                                    final BlockState anchorState) {
        final boolean[] cuts = new boolean[chain.size()];
        for (int i = 0; i < chain.size(); i++) {
            final BlockState each = i == 0 ? anchorState : world.getBlockState(chain.get(i));
            cuts[i] = PsdLcdBlock.isCut(each);
        }
        return cuts;
    }

    /** 链里有没有剪过的格（决定位图高度用哪一档窗口带）。 */
    private static boolean anyCut(final boolean[] cuts) {
        for (final boolean cut : cuts) {
            if (cut) {
                return true;
            }
        }
        return false;
    }

    /** 形状签名（贴图缓存 / 内容哈希都用它）：哪一格剪过变了就得重建。 */
    private static String shapeKey(final boolean[] cuts) {
        final StringBuilder out = new StringBuilder(cuts.length);
        for (final boolean cut : cuts) {
            out.append(cut ? '1' : '0');
        }
        return out.toString();
    }

    /** 方块状态的 {@code FACING} → 面板几何用的 {@link PsdLcdLayout.Face}。 */
    private static PsdLcdLayout.Face faceOf(final Direction facing) {
        if (facing == null) {
            return PsdLcdLayout.Face.NORTH;
        }
        return switch (facing) {
            case SOUTH -> PsdLcdLayout.Face.SOUTH;
            case EAST -> PsdLcdLayout.Face.EAST;
            case WEST -> PsdLcdLayout.Face.WEST;
            default -> PsdLcdLayout.Face.NORTH;
        };
    }

    private void sweep(final World world) {
        final long now = System.currentTimeMillis();
        if (now - this.lastSweep > 5_000L) {
            this.lastSweep = now;
            PsdLcdTextures.sweep();
            sweepRotations(world);
        }
    }

    /**
     * 清掉<b>别的维度</b>的轮播状态（换维度 / 换存档后，旧键永远不会再被问到）。
     * 小于 512 条时不动：同一维度里几百块屏是正常的，逐条比前缀反而更贵。
     */
    private static void sweepRotations(final World world) {
        if (world == null) {
            return;
        }
        final String prefix = world.getRegistryKey().getValue() + "|";
        synchronized (ROTATIONS) {
            if (ROTATIONS.size() <= 512) {
                return;
            }
            ROTATIONS.keySet().removeIf(key -> key == null || !key.startsWith(prefix));
        }
    }

    // ------------------------------------------------------------------ 顶点

    private static void vertex(final VertexConsumer vc, final Matrix4f m, final Matrix3f n,
                               final float x, final float y, final float z,
                               final float u, final float v, final float[] normal) {
        vc.vertex(m, x, y, z)
                .color(255, 255, 255, 255)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(PidsXiamenLayout.FULL_LIGHT)
                .normal(n, normal[0], normal[1], normal[2])
                .next();
    }

    // ------------------------------------------------------------------ 线路色带

    /**
     * 板子下沿那条线路色带（MTR {@code RenderPSDTop#renderAdditional} 的对应物）。
     *
     * <p>MTR 的做法：{@code ClientData.DATA_CACHE.getColorStrip(platformId)} 现场生成一张
     * 1×N 的位图（每个经过这个站台的线路 1 像素），贴在 y 14.5…15、整条链宽、z 在前脸上的
     * 位置。我们这边：
     * <ul>
     *   <li><b>不生成位图</b>：{@link PsdLcdStrip#colors(long)} 直接给出颜色数组，一个颜色发一个
     *       纯色 quad，N 条线就是 N 条横带（{@link PsdLcdLayout#stripQuad(PsdLcdLayout.Face,
     *       float, float, boolean, float, float)} 按 0…1 的相对量把 0.5px 的带子均分）——
     *       视觉效果和 MTR 把 1×N 纹理拉满那条带子一样，但不用往 MTR 的纹理缓存里塞东西；</li>
     *   <li><b>y 带</b>跟着模型走：整块 1…1.5、剪过 8.5…9（{@link PsdLcdLayout#stripBandPx(boolean)}），
     *       都在用户模型留出来的那条 0.5px 前脸（z = 10）上。</li>
     * </ul>
     *
     * <h2>渲染层：受光的 {@code EntitySolid}（白图 × 顶点色），不再用 {@code POSITION_COLOR}</h2>
     * <p>这里<b>曾经</b>用 {@code MoreRenderLayers.getExterior(mtrlte:block/white)}
     * （= {@code EntityCutout}，顶点格式 {@code POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL}），
     * 靠一张 1×1 白图 + 顶点色上色。<b>但那张白图只被代码引用，没有任何模型 json 用到它，
     * 所以它不进方块图集</b>；图集查不到精灵就退回「缺失贴图」——实机上会出现一格格黑紫方块。
     * 修法是**别让它走图集**：{@link RenderLayer#getEntitySolid(Identifier)}
     * 按资源路径直绑贴图（{@code Sampler0} 直接读原图，不需要任何模型 json 引用它），
     * 于是能回到带 lightmap 的顶点格式 {@code POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL}，
     * 顶点色 tint = 线路色，白图 × 顶点色 = 纯色。
     * <p>光照：{@code WorldRenderer#getLightmapCoordinates(world, pos)} 取**这一格自己的**位置
     * （天空光 + 方块光打包，夜间由 lightmap 纹理里的环境暗度压暗），和隔壁 MTR 原版吊板一致。
     * 以前是 {@code getDebugQuads()}（{@code POSITION_COLOR}，没有 lightmap 元素）→ 色带恒满亮，
     * 用户对比隔壁原版吊板后指出「像在发光」，所以改回受光。
     */
    private static void drawStrip(final PsdLcdBlockEntity entity, final World world, final Matrix4f matrix,
                                  final Matrix3f normalMatrix, final float[] normal,
                                  final PsdLcdLayout.Face face, final boolean[] cuts,
                                  final java.util.List<BlockPos> chain,
                                  final VertexConsumerProvider.Immediate immediate) {
        final long platformId;
        try {
            platformId = entity.getPlatformId(ClientData.PLATFORMS, ClientData.DATA_CACHE);
        } catch (final Throwable t) {
            // 站台表还没同步：这一帧不画色带（下一帧会再试）。
            return;
        }
        final int[] colors = PsdLcdStrip.colors(platformId);
        // 受光的实体层：顶点格式 POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL（白图 × 顶点色）。
        final VertexConsumer vc = immediate.getBuffer(SOLID_LAYER);
        // 一格一段：每格自己的 y 带（剪没剪）、自己的光照（和这一格的邻居同一套世界光照），
        // 一条线路一条横带（从上往下按数组顺序）。
        for (int i = 0; i < cuts.length; i++) {
            final int cellLight = WorldRenderer.getLightmapCoordinates(world, chain.get(i));
            final float start = i * 16.0F;
            final float end = start + 16.0F;
            for (int c = 0; c < colors.length; c++) {
                final float v0 = 1.0F - (float) (c + 1) / colors.length;
                final float v1 = 1.0F - (float) c / colors.length;
                stripQuad(vc, matrix, normalMatrix, normal, cellLight,
                        PsdLcdLayout.stripQuad(face, start, end, cuts[i], v0, v1), colors[c]);
            }
        }
    }

    /**
     * 色带的一个四边形：顶点色 = 线路色，UV 取白图的任意一点（0,0；整张图都是纯白），
     * light 是该方块位置的真实光照 —— 所以颜色被世界光照压暗 / 提亮，不再恒满亮。
     */
    private static void stripQuad(final VertexConsumer vc, final Matrix4f m, final Matrix3f n,
                                  final float[] normal, final int light,
                                  final float[][] quad, final int argb) {
        final int r = (argb >> 16) & 0xFF;
        final int g = (argb >> 8) & 0xFF;
        final int b = argb & 0xFF;
        final int a = (argb >>> 24) & 0xFF;
        for (final float[] p : quad) {
            vc.vertex(m, p[0], p[1], p[2])
                    .color(r, g, b, a)
                    .texture(0.0F, 0.0F)
                    .overlay(OverlayTexture.DEFAULT_UV)
                    .light(light)
                    .normal(n, normal[0], normal[1], normal[2])
                    .next();
        }
    }

    // ------------------------------------------------------------------ 滚动标语

    /**
     * 滚动标语那条四边形（压在屏幕面上）。
     *
     * <p>位置 = 标语条「框内」矩形（{@link PsdLcdPainter#tickerInner}）在位图里的那一块，
     * 用 {@link PsdLcdLayout#bandQuad} 换算到世界坐标 —— 和窗口四边形同一份铺开轴 / 朝向翻转算术。
     * 贴图是一张<b>透明底</b>的小位图（{@link PsdLcdPainter#paintTickerStrip}），每 tick 只重画、
     * 重传这一块，整屏位图完全不参与滚动。
     *
     * <p>渲染层用 {@code getLight(id, true)}（beacon 层 + <b>半透明</b>）：小贴图除了字全是透明的，
     * 不开混合会画成一整条黑底。深度：beacon 层不写深度，也就是「后发的压在上面」——
     * 整屏四边形先发、这条后发，所以它在前（{@link PsdLcdLayout#bandQuad} 再沿法线推
     * {@value #TICKER_LIFT_PX} 个 1/16 px 只是保险）。
     *
     * <p>一格一段地发（和整屏四边形一样），因为每格的 y 带可能不同（剪过 9…16 / 没剪 1.5…8.5）。
     */
    private static void drawTicker(final Content content, final boolean[] cuts, final float extent,
                                   final int bitmapW, final int bitmapH, final String key,
                                   final PsdLcdLayout.Face face, final Matrix4f matrix,
                                   final Matrix3f normalMatrix, final float[] normal,
                                   final VertexConsumerProvider.Immediate immediate) {
        if (content.tickerPhase() < 0.0F || bitmapW < 2 || bitmapH < 2) {
            return;
        }
        final PsdLcdPainter.Content c = content.content();
        final PsdLcdPainter.Assets assets = PsdLcdAssets.get();
        final int sw = PsdLcdPainter.tickerStripWidth(bitmapW, bitmapH);
        final int sh = PsdLcdPainter.tickerStripHeight(bitmapW, bitmapH);
        if (sw < 4 || sh < 4) {
            return;
        }
        final PsdLcdTextures.Entry strip = PsdLcdTextures.acquire(key + "|ticker", sw, sh);
        if (strip == null) {
            return;
        }
        // 只在「滚到下一个像素」时才重画 / 重传：paintTickerStrip 是 Java2D 画一行字，很便宜，
        // 但每帧都做也没意义 —— 20 tick/秒 × 2~4 px，实际最多每 tick 一次。
        final int phasePx = Math.round(content.tickerPhase()
                * PsdLcdPainter.tickerScrollSpanPx(c, assets, bitmapW, bitmapH));
        if (phasePx != strip.lastPhasePx) {
            strip.lastPhasePx = phasePx;
            PsdLcdTextures.upload(strip,
                    PsdLcdPainter.paintTickerStrip(c, assets, bitmapW, bitmapH, content.tickerPhase()));
        }

        final float[] inner = PsdLcdPainter.tickerInner(bitmapW, bitmapH);
        final float fx0 = inner[0] / bitmapW;
        final float fx1 = inner[2] / bitmapW;
        final float fy0 = inner[1] / bitmapH;
        final float fy1 = inner[3] / bitmapH;
        if (fx1 - fx0 < 1.0E-4F || fy1 - fy0 < 1.0E-4F) {
            return;
        }
        final VertexConsumer vc = immediate.getBuffer(MoreRenderLayers.getLight(strip.id, true));
        final int segments = Math.max(1, cuts.length);
        for (int i = 0; i < segments; i++) {
            final float cell0 = (float) i / segments;
            final float cell1 = (float) (i + 1) / segments;
            final float a = Math.max(fx0, cell0);
            final float b = Math.min(fx1, cell1);
            if (b - a < 1.0E-5F) {
                continue;
            }
            final float[][] quad = PsdLcdLayout.bandQuad(face, extent, a, b, fy0, fy1,
                    i < cuts.length && cuts[i], TICKER_LIFT_PX);
            final float u0 = (a - fx0) / (fx1 - fx0);
            final float u1 = (b - fx0) / (fx1 - fx0);
            vertex(vc, matrix, normalMatrix, quad[0][0], quad[0][1], quad[0][2], u0, 0.0F, normal);
            vertex(vc, matrix, normalMatrix, quad[1][0], quad[1][1], quad[1][2], u0, 1.0F, normal);
            vertex(vc, matrix, normalMatrix, quad[2][0], quad[2][1], quad[2][2], u1, 1.0F, normal);
            vertex(vc, matrix, normalMatrix, quad[3][0], quad[3][1], quad[3][2], u1, 0.0F, normal);
        }
    }

    // ------------------------------------------------------------------ 内容

    /** 一帧的内容 + 用来判断「要不要重画位图」的哈希 + 标语滚动相位。 */
    private record Content(PsdLcdPainter.Content content, BufferedImage ad, String hash,
                           float tickerPhase, int tickerSpanTicks) {
    }

    /** 这一帧播的是哪张广告，以及它这一轮的起点 / 终点（广告轮播与标语滚动共用同一条时间线）。 */
    private record AdSlot(PidsXiamenAds.Ad ad, long passStart, long passEnd) {
    }

    private static Content contentOf(final PsdLcdBlockEntity entity, final World world, final BlockPos pos,
                                     final float spanPx, final int bitmapW, final int bitmapH) {
        final List<ScheduleEntry> schedules = PidsXiamenRenderer.schedulesFor(entity, pos);
        final int page = Math.max(0, entity.getDisplayPage());

        // ---- 时钟 / 日期
        final LocalDate date = PidsXiamenRenderer.dateNow(world);
        final String dateText = date.toString();
        final String weekdayZh = "星期" + "一二三四五六日".charAt(date.getDayOfWeek().getValue() - 1);
        final String weekdayEn = date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        final String clock = PidsXiamenRenderer.formatTime(world);

        // ---- 两班到站
        final ScheduleEntry first = entryAt(schedules, page * 2);
        final ScheduleEntry second = entryAt(schedules, page * 2 + 1);
        final Route route = first == null ? null
                : ClientData.DATA_CACHE.routeIdMap.get(first.routeId);
        final Route route2 = second == null ? null
                : ClientData.DATA_CACHE.routeIdMap.get(second.routeId);

        final String[] dest1 = first == null || route == null
                ? new String[]{"", ""} : PidsXiamenRenderer.destinations(route, first);
        final String[] dest2 = second == null || route2 == null
                ? new String[]{"", ""} : PidsXiamenRenderer.destinations(route2, second);

        final PidsXiamenRenderer.Countdown count1 = first == null
                ? new PidsXiamenRenderer.Countdown("", "") : PidsXiamenRenderer.countdown(first);
        final PidsXiamenRenderer.Countdown count2 = second == null
                ? new PidsXiamenRenderer.Countdown("", "") : PidsXiamenRenderer.countdown(second);
        // 单位：**两行各算各的**（以前两行共用 count1 算出来的那一份，第一行没数字时
        // 第二行的「分钟 / min」就一起消失了 —— 用户报的「分钟有时不显示」有一半是这个）；
        // 数字在、单位却是空的时候退回「分钟 / min」，屏上不会出现「17」后面没有单位。
        final String unitZh = unitOf(count1, true);
        final String unitEn = unitOf(count1, false);
        final String unitZh2 = unitOf(count2, true);
        final String unitEn2 = unitOf(count2, false);

        // ---- 线路徽标（取第一班的线路色）
        final String lineZh;
        final String lineEn;
        final int lineColor;
        if (route == null) {
            lineZh = "";
            lineEn = "";
            lineColor = 0xFF2B36C8;
        } else {
            final String raw = PidsXiamenRenderer.firstPart(route.name);
            lineZh = raw;
            final String digits = raw.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                lineEn = Text.translatable("gui.mtrlte.psd_lcd.line_en", digits).getString();
            } else {
                final int bar = route.name.indexOf('|');
                lineEn = bar < 0 ? "" : route.name.substring(bar + 1).trim();
            }
            lineColor = (route.color & 0xFFFFFF) == 0 ? 0xFF2B36C8 : (0xFF000000 | (route.color & 0xFFFFFF));
        }

        // ---- 当前站
        String stationZh = "";
        String stationEn = "";
        Station station = null;
        try {
            station = RailwayData.getStation(ClientData.STATIONS, ClientData.DATA_CACHE, pos);
            if (station != null) {
                final String[] parts = station.name.split("\\|", -1);
                stationZh = parts.length > 0 ? parts[0].trim() : "";
                stationEn = parts.length > 1 ? parts[1].trim() : "";
            }
        } catch (final Throwable ignored) {
            // 车站表还没同步：站名留空，其余照画。
        }
        // 没有站名数据时退回示意里的占位字「当前站 / This station」——
        // 这样空站也不会在那一格留一片空白（有数据时显示真实站名，和实拍一致）。
        if (stationZh.isEmpty()) {
            stationZh = Text.translatable("gui.mtrlte.psd_lcd.this_station_zh").getString();
        }
        if (stationEn.isEmpty()) {
            stationEn = Text.translatable("gui.mtrlte.psd_lcd.this_station_en").getString();
        }

        // ---- 编组：按 MTR 里「这一站 / 这条线」正在跑的那列车读实际节数（拿不到就不画编组行）
        final int cars = consistOf(station, first == null ? 0L : first.routeId);
        final List<String> carNumbers = new java.util.ArrayList<>(cars);
        for (int i = 1; i <= cars; i++) {
            carNumbers.add(String.valueOf(i));
        }

        // ---- 标语条的文字：**画进位图 + 渲染器每 tick 滚动**（见 drawTicker）。
        // 广告区画的是同一张广告（有图就铺图，没图就洋红占位块 + 「广告位」）。
        final AdSlot slot = adOf(entity, world, bitmapW, bitmapH);
        final BufferedImage adImage = slot.ad == null ? null : PsdLcdAssets.ad(slot.ad.image());
        final String ticker = slot.ad == null || slot.ad.text() == null ? "" : slot.ad.text().getString();

        final PsdLcdPainter.Content content = new PsdLcdPainter.Content(
                dateText, weekdayZh, weekdayEn, clock,
                Text.translatable("gui.mtrlte.pids.bound_for_label").getString(),
                Text.translatable("gui.mtrlte.pids.bound_for_sub").getString(),
                dest1[0], dest1[1], count1.number(),
                Text.translatable("gui.mtrlte.psd_lcd.next_train_label").getString(),
                Text.translatable("gui.mtrlte.pids.next_train_sub").getString(),
                dest2[0], dest2[1], count2.number(),
                unitZh, unitEn,
                unitZh2, unitEn2,
                lineZh, lineEn, lineColor,
                stationZh, stationEn,
                carNumbers,
                Text.translatable("gui.mtrlte.psd_lcd.consist_note").getString(),
                ticker,
                Text.translatable("gui.mtrlte.psd_lcd.place_ad").getString());

        // ---- 标语滚动：**一律滚**（短句也从右往左滚），一轮的 tick 数与广告轮播共用同一个数
        final PsdLcdPainter.Assets assets = PsdLcdAssets.get();
        final float fontPx = PsdLcdPainter.tickerFontPx(bitmapW, bitmapH);
        final float textW = PsdLcdPainter.tickerTextWidth(content, assets, bitmapW, bitmapH);
        final float innerW = PsdLcdPainter.tickerInner(bitmapW, bitmapH)[2]
                - PsdLcdPainter.tickerInner(bitmapW, bitmapH)[0];
        final float spanPxOfText = innerW + textW;
        final int spanTicks = textW > 0.0F ? Math.max(1, Math.round(
                spanPxOfText / PidsXiamenTicker.pxPerTick(fontPx))) : 0;
        final float phase;
        if (spanTicks <= 0) {
            // 没有标语（空字）：没有可滚的东西（标语框也不画）
            phase = -1.0F;
        } else if (slot.passEnd > slot.passStart) {
            // 广告轮播在走：相位就是「这一张已经播了多少」，滚完那一刻正好换图
            final long now = world.getTime();
            phase = Math.max(0.0F, Math.min(0.9999F,
                    (float) ((now - slot.passStart) / (double) (slot.passEnd - slot.passStart))));
        } else {
            // 没有广告轮播（广告集关着 / 只有一张）：自己按世界时间循环
            final long now = world.getTime();
            phase = (float) (Math.floorMod(now, (long) spanTicks) / (double) spanTicks);
        }

        // 哈希：只有这些变了才需要重画位图（广告图换了也要重画，所以把图片 id 也带上；
        // 接 / 拆一格以后宽度变了也重画 —— 位图宽度跟着 spanPx 走，不能拿旧宽度的位图拉伸）。
        // **滚动相位不进哈希**：滚动标语是单独一张小贴图，整屏位图不参与。
        final String adKey = slot.ad == null || slot.ad.image() == null ? "-" : slot.ad.image().toString();
        return new Content(content, adImage,
                content + "|" + Math.round(spanPx) + "|" + adKey, phase, spanTicks);
    }

    /**
     * 这块屏当前要显示的广告（和厦门 PIDS 同一套优先级：全局广告集 → 全局单张覆盖 →
     * 这块屏自己勾的分类，见 {@link PidsXiamenSettings#applyAdOverride(List)}）。
     * 广告关掉 / 表是空的 → {@code ad == null}。
     *
     * <h2>换图节奏：标语滚完一轮（不是定时）</h2>
     * <p>广告集生效时，换图触发点与厦门 PIDS 完全一致：<b>这一张的标语滚完一轮</b>才 +1。
     * 时长用的是 PSD-LCD 自己那条标语框的几何 —— 框内宽 + 这条字的宽度，除以
     * {@link PidsXiamenTicker#pxPerTick(float)}（与 PIDS <b>同一份</b>速度换算）：
     * 也就是屏上那句字正好滚出左缘的那一刻换图，不会「字滚到一半突然换广告」。
     * <b>短句也一样滚</b>（同一套速度、同一条行程），所以不再有「短句不滚 → 固定停留」的例外。
     *
     * <p>返回的 {@code passStart/passEnd} 同时是标语滚动的相位基准（{@code contentOf} 里用），
     * 所以「滚到哪」和「什么时候换图」永远是同一对数。
     *
     * <p>广告集关着 / 空 / 图都不在了（{@link PidsAdSetCache#activeAds()} 为 {@code null}）时，
     * 完全保持老行为：只按 {@code advertisementIndex} 取一张，不随时间变（{@code passStart == passEnd}
     * = 没有轮播时间线，标语自己按世界时间循环滚）。
     */
    private static AdSlot adOf(final PsdLcdBlockEntity entity, final World world,
                               final int bitmapW, final int bitmapH) {
        try {
            if (!PidsXiamenSettings.advertisements()) {
                return new AdSlot(null, 0L, 0L);
            }
            final List<PidsXiamenAds.Ad> ads = PidsXiamenSettings.applyAdOverride(entity.getAds());
            if (ads == null || ads.isEmpty()) {
                return new AdSlot(null, 0L, 0L);
            }
            final List<PidsXiamenAds.Ad> set = PidsAdSetCache.activeAds();
            if (set == null || set.isEmpty()) {
                // 广告集没生效（关着 / 空 / 图全丢了）：老行为，一张到底，不轮播。
                final String key = rotationKey(entity, world);
                if (key != null) {
                    synchronized (ROTATIONS) {
                        ROTATIONS.remove(key);
                    }
                }
                return new AdSlot(ads.get(Math.floorMod(entity.getAdvertisementIndex(), ads.size())),
                        0L, 0L);
            }
            final String key = rotationKey(entity, world);
            if (key == null) {
                return new AdSlot(set.get(Math.floorMod(entity.getAdvertisementIndex(), set.size())),
                        0L, 0L);
            }
            final long now = world.getTime();
            synchronized (ROTATIONS) {
                AdRotation state = ROTATIONS.get(key);
                if (state == null || state.setRef != set || state.count != set.size()) {
                    // 第一次见到这块屏 / 广告集换了 / 张数变了：从这块屏自己的起点重新开始。
                    state = new AdRotation();
                    state.setRef = set;
                    state.count = set.size();
                    state.slot = Math.floorMod(entity.getAdvertisementIndex(), state.count);
                    state.passStart = now;
                    state.passEnd = now + passTicks(set.get(state.slot), bitmapW, bitmapH);
                    ROTATIONS.put(key, state);
                } else if (now >= state.passEnd) {
                    // 标语滚完一轮 → 下一张。就算中间隔了很久（区块卸载后重进）也只前进一步，
                    // 不会一次跳过好几张。
                    state.slot = (state.slot + 1) % state.count;
                    state.passStart = now;
                    state.passEnd = now + passTicks(set.get(state.slot), bitmapW, bitmapH);
                }
                return new AdSlot(set.get(state.slot), state.passStart, state.passEnd);
            }
        } catch (final Throwable ignored) {
            return new AdSlot(null, 0L, 0L);
        }
    }

    /** 轮播状态的键：{@code world|anchor}（一条链只有一个锚点，所以一屏一条）。 */
    private static String rotationKey(final PsdLcdBlockEntity entity, final World world) {
        if (entity == null) {
            return null;
        }
        try {
            return (world == null ? "?" : world.getRegistryKey().getValue().toString())
                    + "|" + PsdLcdBlock.anchorOf(world, entity.getPos()).asLong();
        } catch (final Throwable ignored) {
            return null;
        }
    }

    /**
     * 这张广告「标语滚完一轮」要多少 tick。
     *
     * <p><b>用的是屏上那条标语自己的几何</b>：{@link PsdLcdPainter#tickerScrollSpanPx}
     * （框内宽 + 字宽）除以 {@link PidsXiamenTicker#pxPerTick(float)} ——
     * 后面这个函数的参数是这块屏的字高 {@link PsdLcdPainter#tickerFontPx}，
     * 所以「滚完」的时刻和 {@link #drawTicker} 画出来的滚动<b>逐像素同一套</b>
     * （PIDS 那边也是用同一个函数换算，只是字高不同）。
     *
     * <p><b>标语一律滚</b>（短句也从右往左滚），所以这里不再有「短句不滚」的分支；
     * 没字（{@code text} 空）时没有可滚的跨度，才回落 {@link #AD_ROTATE_TICKS}。
     */
    private static int passTicks(final PidsXiamenAds.Ad ad, final int bitmapW, final int bitmapH) {
        try {
            if (ad == null || ad.text() == null || ad.text().getString().isBlank()) {
                return AD_ROTATE_TICKS;
            }
            final PsdLcdPainter.Assets assets = PsdLcdAssets.get();
            final float textW = PsdLcdPainter.tickerTextWidthFor(ad.text().getString(), assets,
                    bitmapW, bitmapH);
            final float innerW = PsdLcdPainter.tickerInner(bitmapW, bitmapH)[2]
                    - PsdLcdPainter.tickerInner(bitmapW, bitmapH)[0];
            if (textW <= 0.0F) {
                // 量不出字宽：按固定停留
                return AD_ROTATE_TICKS;
            }
            final float pxPerTick = PidsXiamenTicker.pxPerTick(
                    PsdLcdPainter.tickerFontPx(bitmapW, bitmapH));
            final int ticks = Math.round((innerW + textW) / Math.max(0.01F, pxPerTick));
            return ticks > 0 ? ticks : AD_ROTATE_TICKS;
        } catch (final Throwable ignored) {
            return AD_ROTATE_TICKS;
        }
    }

    /**
     * 这一站 / 这条线上正在跑的那列车的<b>实际编组节数</b>（MTR 的 {@code Train#trainCars}）。
     *
     * <p>找法（越靠前越优先）：
     * <ol>
     *   <li>司机室里正停在这个站、并且跑的是第一班那条线路的车 → 用它；</li>
     *   <li>停在这个站的任意一列车（跨线车 / 折返车）；</li>
     *   <li>这条线路上任意一列在跑的车。</li>
     * </ol>
     * 三条都落空（单机存档还没发车、或者客户端还没同步列车）时返回 <b>0</b> —— 编组行整块
     * <b>不画</b>（不画占位、也不退回固定节数），广告区与其它文字照常；并且<b>只 warning 一次</b>
     * （这条路径每 200ms 走一次，不能刷屏）。
     */
    private static int consistOf(final Station station, final long routeId) {
        try {
            int atStationAny = 0;
            int onRouteAny = 0;
            for (final TrainClient train : ClientData.TRAINS) {
                if (train == null || train.trainCars <= 0) {
                    continue;
                }
                final Route route = train.getThisRoute();
                final boolean onRoute = routeId > 0L && route != null && route.id == routeId;
                if (onRoute && train.trainCars > onRouteAny) {
                    onRouteAny = train.trainCars;
                }
                if (!sameStation(train.getThisStation(), station)) {
                    continue;
                }
                if (onRoute) {
                    return train.trainCars;
                }
                atStationAny = Math.max(atStationAny, train.trainCars);
            }
            if (atStationAny > 0) {
                return atStationAny;
            }
            if (onRouteAny > 0) {
                return onRouteAny;
            }
        } catch (final Throwable t) {
            // 列车表正在被服务端包改写：这一帧按「拿不到」处理，下一帧再试。
        }
        if (!carsWarned) {
            carsWarned = true;
            LteLog.LOGGER.warn("[lte] psd lcd: no live train at this platform, "
                    + "leaving the consist row blank");
        }
        return 0;
    }

    /** 两个 Station 是不是同一个（按 id；任一为 null 就是 false）。 */
    private static boolean sameStation(final Station a, final Station b) {
        return a != null && b != null && a.id == b.id;
    }

    /**
     * 倒计时单位：中文写「分钟 / 秒」，英文写「min / s」。
     *
     * <h2>为什么改成吃整个 {@link PidsXiamenRenderer.Countdown}</h2>
     * <p>「分钟 / min 有时候不显示」的根因就在这个函数的调用方：
     * <ol>
     *   <li><b>两行共用一个单位</b>：以前只拿第一行的 {@code count1} 算，第二行（「下一趟开往」）
     *       永远跟着第一行走 —— 第一行是「即将进站」（没有数字 / 没有单位）时，第二行明明有
     *       「17」，单位却是空的；</li>
     *   <li><b>数字在、单位空</b>：{@code countdown()} 在「一分钟以内」那条分支返回
     *       {@code ("即将进站", "")}，未知单位又落到 {@code chinese ? unit : ""}，英文侧直接
     *       变成空串 —— 于是中文有「秒」、英文什么都没有。</li>
     * </ol>
     * 现在：数字为空 → 单位留空（不出现孤零零的「分钟」）；数字是纯数字却没解析出单位 →
     * 退回「分钟 / min」（宁可写错单位，也不能让「17」后面空着）。这两条在
     * {@code tools/psd-lcd-fit-check.js} 里有 0/1/2/17/99 × 中英文的像素级断言。
     */
    private static String unitOf(final PidsXiamenRenderer.Countdown countdown, final boolean chinese) {
        if (countdown == null || countdown.number() == null || countdown.number().isEmpty()) {
            return "";
        }
        final String unit = unitOf(countdown.unit(), chinese);
        if (!unit.isEmpty()) {
            return unit;
        }
        if (!countdown.number().codePoints().allMatch(Character::isDigit)) {
            // 「即将进站 / 已到站」这种文字状态：本来就没有单位，别硬塞一个
            return "";
        }
        return Text.translatable(chinese
                ? "gui.mtrlte.pids.minute" : "gui.mtrlte.psd_lcd.min_en").getString();
    }

    /** 倒计时单位：中文写「分钟 / 秒」，英文写「min / s」。 */
    private static String unitOf(final String zhUnit, final boolean chinese) {
        if (zhUnit == null || zhUnit.isEmpty()) {
            return "";
        }
        final String minute = Text.translatable("gui.mtrlte.pids.minute").getString();
        final String second = Text.translatable("gui.mtrlte.pids.second").getString();
        if (zhUnit.equals(minute)) {
            return chinese ? zhUnit : Text.translatable("gui.mtrlte.psd_lcd.min_en").getString();
        }
        if (zhUnit.equals(second)) {
            return chinese ? zhUnit : Text.translatable("gui.mtrlte.psd_lcd.sec_en").getString();
        }
        return chinese ? zhUnit : "";
    }

    private static ScheduleEntry entryAt(final List<ScheduleEntry> schedules, final int index) {
        return index >= 0 && index < schedules.size() ? schedules.get(index) : null;
    }
}
