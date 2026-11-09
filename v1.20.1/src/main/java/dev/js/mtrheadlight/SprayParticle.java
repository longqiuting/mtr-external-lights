package dev.js.mtrheadlight;

import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.client.particle.SpriteBillboardParticle;
import net.minecraft.client.particle.SpriteProvider;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * LTE 自己的水雾粒子 —— 注册为 {@code mtrlte:spray}（见 {@link SprayType}）。
 *
 * <h2>为什么要有自己的类型</h2>
 * Sodium Extra 在 {@code ParticleManager#addParticle} 的 HEAD 上按<b>粒子类型 id</b> 逐个屏蔽
 * （{@code otherMap.getOrDefault(id, TRUE)}）：{@code minecraft:cloud} / {@code large_smoke} /
 * {@code splash} 这些都是它设置界面里列出来的已知类型，用户一旦关掉就永远进不了粒子系统 ——
 * 和 {@code force=true}、距离、粒子档位都无关。查表时<b>键不存在走默认 TRUE（放行）</b>，所以
 * 一个它没见过的 id（{@code mtrlte:spray}）不会被任何已有配置屏蔽。
 *
 * <h2>「像原版雨丝」是怎么做出来的</h2>
 * 原版下雨不是粒子，而是 {@code WeatherRenderer} 拿 {@code textures/environment/rain.png}
 * 画竖向条带：那张图是 64×256 的<b>雨滴图集</b>（4 列 × 16 行、每格 16×16），每格是一根
 * <b>1 像素宽、顶部实、尾部渐隐</b>的竖直雨丝。本类的贴图直接从那 64 格里裁出三根真雨丝
 * （见 {@code assets/mtrlte/textures/particle/spray*.png}），所以 alpha 剖面（{@code FF FF FF …
 * 86}）与配色（{@code #4667C3}）都是原版数据，不是画出来的近似。
 *
 * <p>原版那张图是 1 像素硬边，粒子缩小绘制时会闪，所以裁切时只做了<b>横向柔化</b>（把 1 像素
 * 剖面铺成 4 列、中间两列为主），纵向剖面保持原样。宽度因此只有四分之一格贴图，配合下面的
 * {@link #VERTICAL_STRETCH} 就得到细长的雨丝，而不是圆点或方块。
 *
 * <h2>参数（与需求一一对应）</h2>
 * <ul>
 *   <li>寿命 {@value #MIN_LIFE}–{@value #MAX_LIFE} tick（0.4–0.7 s）：比雨滴略长，雨丝有足够的
 *       存在时间去被看见，又不会积成雾带；</li>
 *   <li>初速：竖直方向固定 {@value #DRIFT_MIN}–{@value #DRIFT_MAX} blocks/tick 的缓慢上浮
 *       （构造时把传入的 vy 夹到该区间，见 {@link #drift(double)}）；水平分量原样保留（含调用方给的
 *       ±0.08 扩散）；</li>
 *   <li>重力 {@code gravityStrength = 0}：永不加速下落，只有 {@link #VELOCITY_MULTIPLIER} 的轻微
 *       阻尼，所以整条轨迹是「飘」，不是「抛」；</li>
 *   <li>尺寸：{@value #SCALE_MIN}–{@value #SCALE_MAX} 起，线性缩到起点的 {@code 1 - }{@value #SHRINK}；</li>
 *   <li>纵向拉伸 {@value #VERTICAL_STRETCH}×（{@link #buildGeometry}）：{@code scale} 是正方形的，
 *       只靠贴图里的细线还不够长，所以自绘四边形时只在竖直方向放大 —— 0.08–0.18 的方块因此变成
 *       0.18–0.40 格高、约 0.02–0.05 格宽的细丝；</li>
 *   <li>透明度：{@value #ALPHA_MIN}–{@value #ALPHA_MAX} 起，按 {@code 1 - t²} 二次衰减到 0；</li>
 *   <li>颜色：贴图自带原版雨丝的 {@code #4667C3}，所以 tint 是白光 {@code (1,1,1)}，不再二次染色。</li>
 * </ul>
 */
public final class SprayParticle extends SpriteBillboardParticle {

    /** 寿命下/上限（tick）。 */
    static final int MIN_LIFE = 8;
    static final int MAX_LIFE = 14;

    /**
     * 起始尺寸区间（格，quad 半宽的量级）。0.08–0.18 再经 {@link #VERTICAL_STRETCH} 纵向拉长，
     * 才是原版雨丝那种「细而长」的形状 —— 靠数量而不是体积出效果。
     */
    private static final float SCALE_MIN = 0.08F;
    private static final float SCALE_MAX = 0.18F;

    /** 寿命结束时缩到起点的 (1 - SHRINK)。 */
    private static final float SHRINK = 0.55F;

    /**
     * 竖直方向对正方形的拉伸倍数。
     *
     * <p>{@code SpriteBillboardParticle} 只能等比（{@code getSize()} 一个值同时给宽高），
     * 所以这里重写 {@link #buildGeometry} 自绘四边形：本地坐标的 y 先乘本值再旋转，
     * 得到的永远是「相机竖直方向」上的拉长，屏幕上看就是一根竖着的雨丝。
     */
    private static final float VERTICAL_STRETCH = 2.2F;

    /** 起始不透明度区间；随时间二次衰减到 0。 */
    private static final float ALPHA_MIN = 0.35F;
    private static final float ALPHA_MAX = 0.55F;

    /** 竖直初速区间（blocks/tick），轻微向上。 */
    private static final double DRIFT_MIN = 0.01D;
    private static final double DRIFT_MAX = 0.02D;

    /** 水平阻尼（每 tick 乘一次）。1.0 表示完全不变。 */
    private static final float VELOCITY_MULTIPLIER = 0.90F;

    /** 贴图本身就是原版雨丝的颜色（#4667C3），tint 用白光，不再二次染色。 */
    private static final float TINT_R = 1.00F;
    private static final float TINT_G = 1.00F;
    private static final float TINT_B = 1.00F;

    private final float startScale;
    private final float startAlpha;

    /**
     * 诊断用的「出生尺寸」累计（{@code avgSize=}）。
     *
     * <p>尺寸是每颗粒子在自己的构造里抽签决定的，{@link RainSpray} 那边只看得到「发了几个」，
     * 看不到「多大」。所以让粒子自己在构造时记账，诊断窗口结束时由 {@link RainSpray} 读走并清零：
     * 这样 {@code avgSize=} 是实测量，而不是把常量区间取中值算出来的「应该值」。
     *
     * <p>全部读写都在客户端主线程（{@code addParticle} 与 {@code report} 同线程），加
     * {@code synchronized} 只是兜底（Sodium 之类的优化可能把粒子构造挪到别的线程）。
     */
    private static long bornCount;
    private static long bornMilliSum;

    /** 记一颗新生粒子的起始尺寸（由构造器调用）。 */
    private static synchronized void noteBorn(final float scale) {
        bornCount++;
        bornMilliSum += Math.round(scale * 1000.0F);
    }

    /**
     * 取走自上次数取以来的出生尺寸统计并清零。
     *
     * @return {@code {平均起始尺寸, 样本数}}；样本数为 0 时平均值为 {@code -1}
     *         （说明这段时间没走自注册粒子，例如回退到了 {@code minecraft:cloud}）
     */
    static synchronized double[] drainScaleStats() {
        final double avg = bornCount == 0 ? -1.0D : bornMilliSum / 1000.0D / bornCount;
        final double[] out = {avg, bornCount};
        bornCount = 0;
        bornMilliSum = 0;
        return out;
    }

    /**
     * 由 {@link SprayType} 注册的工厂调用。位置/水平速度来自 {@code addParticle}，
     * 竖直速度一律走 {@link #drift(double)}（本粒子只用来做水雾）。
     */
    SprayParticle(final ClientWorld world, final double x, final double y, final double z,
                  final double velocityX, final double velocityY, final double velocityZ,
                  final SpriteProvider sprites) {
        // 三参父构造：位置 + prevPos + 边界盒。速度随后自己赋，避免 7 参重载的语义差异。
        super(world, x, y, z);

        // 贴图：spray.json 里列了 3 根从原版 rain.png 裁出来的雨丝，随机取一根（长度/亮度各不相同，
        // 对应原版那张 64 格图集里的多样性）。
        this.setSprite(sprites.getSprite(this.random));

        this.maxAge = MIN_LIFE + this.random.nextInt(MAX_LIFE - MIN_LIFE + 1);
        this.gravityStrength = 0.0F;
        this.velocityMultiplier = VELOCITY_MULTIPLIER;

        this.velocityX = velocityX;
        this.velocityZ = velocityZ;
        this.velocityY = drift(velocityY);

        this.startScale = SCALE_MIN + this.random.nextFloat() * (SCALE_MAX - SCALE_MIN);
        this.scale = this.startScale;
        noteBorn(this.startScale);

        this.startAlpha = ALPHA_MIN + this.random.nextFloat() * (ALPHA_MAX - ALPHA_MIN);
        this.setAlpha(this.startAlpha);
        this.setColor(TINT_R, TINT_G, TINT_B);
    }

    /** 把竖直初速夹进「轻微上浮」区间：太快会像烟柱，太慢或向下就不像水雾了。 */
    private static double drift(final double vy) {
        if (Double.isNaN(vy)) {
            return DRIFT_MIN;
        }
        return Math.max(DRIFT_MIN, Math.min(DRIFT_MAX, vy));
    }

    /**
     * 每 tick：先走父类（移动 / 阻尼 / 寿命），再按寿命比例改尺寸和透明度。
     *
     * <p>{@code super.tick()} 在 {@code age++ >= maxAge} 时会 {@code markDead()}，此时必须
     * 立刻返回 —— 已死的粒子还会被 {@code buildGeometry} 画到帧里（那一帧它是 alpha=0 的
     * 全透明 quad，浪费顶点）。
     */
    @Override
    public void tick() {
        super.tick();
        if (this.dead) {
            return;
        }
        final float life = this.maxAge <= 0
                ? 1.0F
                : Math.min(1.0F, (float) this.age / (float) this.maxAge);
        this.scale = this.startScale * (1.0F - SHRINK * life);
        // 二次衰减：前半程几乎不透明（雨丝），后半程迅速化开。
        this.setAlpha(this.startAlpha * (1.0F - life * life));
    }

    /**
     * 自绘四边形：与 {@code BillboardParticle#buildGeometry} 完全一致，唯一区别是本地坐标的 y
     * 先乘 {@link #VERTICAL_STRETCH} —— 于是正方形贴图被拉成一根竖丝。
     *
     * <p>顶点顺序 / UV / 颜色 / 亮度都照抄父类（0:(maxU,maxV) 1:(maxU,minV) 2:(minU,minV)
     * 3:(minU,maxV)），贴图不会翻面；{@code angle} 的处理也保留（本粒子不用旋转，但保持一致）。
     */
    @Override
    public void buildGeometry(final VertexConsumer vertexConsumer, final Camera camera, final float tickDelta) {
        final Vec3d cameraPos = camera.getPos();
        final float dx = (float) (MathHelper.lerp(tickDelta, this.prevPosX, this.x) - cameraPos.getX());
        final float dy = (float) (MathHelper.lerp(tickDelta, this.prevPosY, this.y) - cameraPos.getY());
        final float dz = (float) (MathHelper.lerp(tickDelta, this.prevPosZ, this.z) - cameraPos.getZ());

        final Quaternionf rotation;
        if (this.angle == 0.0F) {
            rotation = camera.getRotation();
        } else {
            rotation = new Quaternionf(camera.getRotation());
            rotation.rotateZ(MathHelper.lerp(tickDelta, this.prevAngle, this.angle));
        }

        final Vector3f[] corners = {
                new Vector3f(-1.0F, -1.0F, 0.0F),
                new Vector3f(-1.0F, 1.0F, 0.0F),
                new Vector3f(1.0F, 1.0F, 0.0F),
                new Vector3f(1.0F, -1.0F, 0.0F)
        };
        final float size = this.getSize(tickDelta);
        for (final Vector3f corner : corners) {
            corner.y *= VERTICAL_STRETCH;
            corner.rotate(rotation);
            corner.mul(size);
            corner.add(dx, dy, dz);
        }

        final float minU = this.getMinU();
        final float maxU = this.getMaxU();
        final float minV = this.getMinV();
        final float maxV = this.getMaxV();
        final int light = this.getBrightness(tickDelta);

        emit(vertexConsumer, corners[0], maxU, maxV, light);
        emit(vertexConsumer, corners[1], maxU, minV, light);
        emit(vertexConsumer, corners[2], minU, minV, light);
        emit(vertexConsumer, corners[3], minU, maxV, light);
    }

    /** 一个顶点：位置 + UV + 颜色 + 亮度，链式调用与父类逐项相同。 */
    private void emit(final VertexConsumer vertexConsumer, final Vector3f corner,
                      final float u, final float v, final int light) {
        vertexConsumer.vertex(corner.x(), corner.y(), corner.z())
                .texture(u, v)
                .color(this.red, this.green, this.blue, this.alpha)
                .light(light)
                .next();
    }

    /** 半透明 sheet：贴图带 alpha，必须走 translucency 才不会画出黑边。 */
    @Override
    public ParticleTextureSheet getType() {
        return ParticleTextureSheet.PARTICLE_SHEET_TRANSLUCENT;
    }
}
