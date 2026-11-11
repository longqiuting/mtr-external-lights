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
 * <h2>「像原版雨丝」的形状是怎么做出来的（颜色已不再是原版）</h2>
 * 原版下雨不是粒子，而是 {@code WeatherRenderer} 拿 {@code textures/environment/rain.png}
 * 画竖向条带：那张图是 64×256 的<b>雨滴图集</b>（4 列 × 16 行、每格 16×16），每格是一根
 * <b>1 像素宽、顶部实、尾部渐隐</b>的竖直雨丝。本类的贴图直接从那 64 格里裁出三根真雨丝
 * （见 {@code assets/mtrlte/textures/particle/spray*.png}），所以 <b>alpha 剖面</b>
 * （{@code FF FF FF … 86}）是原版数据，不是画出来的近似。
 *
 * <p>但原版那 64 格的<b>颜色</b>（{@code #4667C3}）已经全部清掉，贴图现在是纯白 RGB —— 留着它
 * 就等于每颗都带着「原版雨」的签名色，这正是过去「不仔细看看不出来」的根因。颜色改由 tint 决定
 * （见下），形状和柔和度一点没动。
 *
 * <p>原版那张图是 1 像素硬边，粒子缩小绘制时会闪，所以裁切时只做了<b>横向柔化</b>（把 1 像素
 * 剖面铺成 4 列、中间两列为主），纵向剖面保持原样。宽度因此只有四分之一格贴图，配合下面的
 * {@link #VERTICAL_STRETCH} 就得到细长的雨丝，而不是圆点或方块。
 *
 * <h2>参数（与需求一一对应）</h2>
 * <ul>
 *   <li>寿命 {@value #MIN_LIFE}–{@value #MAX_LIFE} tick（0.25–0.45 s）：<b>短而密</b>。同一节车厢
 *       同时挂着的雨丝因此只有 {@code 5–9 × 每秒 24 根} 的滚动量，而不是积成一条雾带；</li>
 *   <li>初速：竖直方向由调用方给出 {@code -(0.05..0.15)} blocks/tick 的<b>下沉</b>速度
 *       （{@link #drift(double)} 只把越界值夹回来，不再强行掰成正的上浮），水平分量原样保留
 *       （含调用方给的、沿车速反方向的涡流速度 ±{@link RainSpray} 的扩散）；</li>
 *   <li>重力 {@code gravityStrength = }{@value #GRAVITY_MIN}–{@value #GRAVITY_MAX}：1.20.1 的
 *       {@code Particle#tick} 是 {@code velocityY -= 0.04 * gravityStrength}，所以这只是"一点"重力 ——
 *       轨迹是一条向后为主、缓慢下弯的弧，不是垂直坠落；</li>
 *   <li>长轴倾角：{@link #buildGeometry} 把雨丝按速度在屏幕上的投影倾斜（上限 {@value #MAX_TILT} 弧度），
 *       否则轨迹虽然是斜的，画面上仍是一排竖直雨丝，看起来和原版雨没区别；</li>
 *   <li>尺寸：{@value #SCALE_MIN}–{@value #SCALE_MAX} 起，线性缩到起点的 {@code 1 - }{@value #SHRINK}；</li>
 *   <li>纵向拉伸 {@value #VERTICAL_STRETCH}×（{@link #buildGeometry}）：{@code scale} 是正方形的，
 *       只靠贴图里的细线还不够长，所以自绘四边形时只在竖直方向放大 —— 0.08–0.18 的方块因此变成
 *       0.18–0.40 格高、约 0.02–0.05 格宽的细丝；</li>
 *   <li>透明度：{@value #ALPHA_MIN}–{@value #ALPHA_MAX} 起，按 {@code 1 - t²} 二次衰减到 0；</li>
 *   <li>颜色：贴图是<b>纯白</b>（原版雨丝的形状 + alpha 剖面，颜色已被清掉），tint
 *       {@code (}{@value #TINT_R}, {@value #TINT_G}, {@value #TINT_B}{@code )} 在顶点色里乘上去，
 *       所以画面上是<b>亮灰白</b>而不是原版雨的深蓝 {@code #4667C3}。</li>
 * </ul>
 *
 * <h2>为什么现在是灰白而不是原版蓝</h2>
 * 贴图是从 {@code rain.png} 裁出来的真雨丝，但原版那 64 格里的颜色恒为 {@code #4667C3}
 * （亮度约 0.36，偏暗偏蓝），粒子 tint 又是白光 {@code (1,1,1)}，于是画出来就是「一小段原版雨」
 * —— 远看只会被当成原版天气。现在两步都改：
 * <ol>
 *   <li>贴图 RGB 全部改成 {@code (255,255,255)}，<b>alpha 剖面逐像素不动</b>（柔和度、渐隐长度都不变）；</li>
 *   <li>tint 用 {@code (0.94,0.95,0.97)}（略带冷调的白）在顶点色里乘。</li>
 * </ol>
 * 顶点色 × 白贴图 = {@code (240,242,247)}：线性相对亮度 {@code 0.149 → 0.889}（约 <b>5.9 倍</b>），
 * 感知亮度约 <b>2.2 倍</b>；再叠上 alpha 从均值 0.45 提到 0.70（1.56 倍），单根在画面上的贡献约
 * <b>3.5 倍</b>。
 * 「亮」全部来自<b>颜色和 alpha</b>，尺寸一点没放大 —— 所以是「看得清的一根根细丝」，
 * 不是「一团糊的白块」。
 */
public final class SprayParticle extends SpriteBillboardParticle {

    /** 寿命下/上限（tick）。短寿命 + 高密度：单根消失得快，画面由「更密的流」而不是「更长的带」变厚。 */
    static final int MIN_LIFE = 5;
    static final int MAX_LIFE = 9;

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

    /** 起始不透明度区间；随时间二次衰减到 0。灰白 + 高 alpha 才在雨幕里「跳」出来。 */
    private static final float ALPHA_MIN = 0.55F;
    private static final float ALPHA_MAX = 0.85F;

    /**
     * 竖直初速的合法区间（blocks/tick，<b>负 = 向下</b>）。
     *
     * <p>调用方（{@link RainSpray}）给的是车尾涡流的下沉速度 {@code -(0.05..0.15)}，这里只做兜底：
     * 向下不超过 {@value #DRIFT_MAX_DOWN}（再快就退回"垂直雨"），向上不超过
     * {@value #DRIFT_MAX_UP}（旧版"轻微上浮"的残留，探针和回退路径仍可能传正值）。
     */
    private static final double DRIFT_MAX_DOWN = 0.18D;
    private static final double DRIFT_MAX_UP = 0.02D;

    /**
     * 重力（{@code Particle#gravityStrength}）。
     *
     * <p>1.20.1 的 {@code Particle#tick} 里是 {@code velocityY -= 0.04 * gravityStrength}
     * （javap 核对过 {@code ldc2_w 0.04d} + {@code getfield gravityStrength}），所以 0.02–0.04
     * 对应的实际加速度是每 tick {@code 0.0008–0.0016} blocks/tick —— <b>是"一点点"而不是"砸下去"</b>：
     * 5–9 tick 的寿命里累计只多下沉 0.005–0.015 格/每 tick 的增量，轨迹从直线变成一条轻微下弯的弧。
     * 真按地球重力（≈0.08 blocks/tick²）来，雨丝两 tick 就掉出粒子体的高度范围，反而看不清方向。
     */
    private static final float GRAVITY_MIN = 0.02F;
    private static final float GRAVITY_MAX = 0.04F;

    /** 速度阻尼（每 tick 乘一次）。1.0 表示完全不变；0.90 让"甩出去"的初速自然衰减。 */
    private static final float VELOCITY_MULTIPLIER = 0.90F;

    /**
     * 雨丝长轴相对"屏幕竖直"的最大倾角（弧度，60°）。
     *
     * <p>粒子的运动方向是向后下方，但四边形如果永远画成竖的，看上去还是"原版雨在原地落"——
     * 真正让人读出"被车甩出去"的是<b>雨丝本身是斜的</b>。所以按速度在屏幕平面里的投影把长轴倾过去；
     * 上限 60° 是为了防止速度几乎垂直于视线时把雨丝压成一根横线。
     */
    private static final float MAX_TILT = 1.0472F;

    /**
     * 顶点色 tint：略带冷调的灰白。贴图已改成纯白，所以这里就是最终颜色
     * （{@code 255 × 0.94/0.95/0.97 ≈ 240,242,247}）—— 比原版雨丝的 {@code #4667C3} 亮约 2.5 倍，
     * 又不像纯白那样在暗景里显得发灰发脏。
     */
    private static final float TINT_R = 0.94F;
    private static final float TINT_G = 0.95F;
    private static final float TINT_B = 0.97F;

    /**
     * 诊断用的一行参数说明（{@code RainSpray} 的 {@code spray:} 行里打印）。
     * 直接读本类的常量，调参数时日志和画面永远是同一个数，不会出现「日志写 0.35、代码是 0.55」。
     */
    static String paramsLabel() {
        return String.format(java.util.Locale.ROOT,
                "tint=%.2f,%.2f,%.2f life=%d-%d alpha=%.2f-%.2f vy=-%.2f..-%.2f gravity=%.2f-%.2f",
                Float.valueOf(TINT_R), Float.valueOf(TINT_G), Float.valueOf(TINT_B),
                Integer.valueOf(MIN_LIFE), Integer.valueOf(MAX_LIFE),
                Float.valueOf(ALPHA_MIN), Float.valueOf(ALPHA_MAX),
                Double.valueOf(RainSpray.mistDownMin()), Double.valueOf(RainSpray.mistDownMax()),
                Float.valueOf(GRAVITY_MIN), Float.valueOf(GRAVITY_MAX));
    }

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

        // 贴图：spray.json 里列了 3 根从原版 rain.png 裁出来的雨丝（长度/亮度各不相同，对应原版那张
        // 64 格图集里的多样性）。RGB 已统一改成纯白，颜色由下面的 tint 决定。
        this.setSprite(sprites.getSprite(this.random));

        this.maxAge = MIN_LIFE + this.random.nextInt(MAX_LIFE - MIN_LIFE + 1);
        // 少量重力：轨迹有一点点往下弯，但不至于两 tick 就掉到轨面。
        this.gravityStrength = GRAVITY_MIN + this.random.nextFloat() * (GRAVITY_MAX - GRAVITY_MIN);
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

    /** 把竖直初速夹进合法区间（±{@link #DRIFT_MAX_DOWN} / {@link #DRIFT_MAX_UP}）。负值 = 向下。 */
    private static double drift(final double vy) {
        if (Double.isNaN(vy)) {
            // 退化值：给一个"半个正常下沉"，不要 0 —— 0 会让雨丝悬停，看起来像贴图没动。
            return -DRIFT_MAX_DOWN * 0.5D;
        }
        return Math.max(-DRIFT_MAX_DOWN, Math.min(DRIFT_MAX_UP, vy));
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
        // 长轴跟随速度：把速度转到相机空间，只取它在屏幕平面里的投影定倾角（见 MAX_TILT）。
        // 用副本 conjugate()，绝不改 camera.getRotation() 返回的那个共享四元数。
        final Vector3f vel = new Vector3f((float) this.velocityX, (float) this.velocityY,
                (float) this.velocityZ).rotate(new Quaternionf(camera.getRotation()).conjugate());
        // 倾角拆成「大小」和「方向」两半：
        //   大小 = 速度在屏幕平面里有多斜（只用 |分量|，落在 [0, 90°]）；
        //   方向 = 水平速度 · 相机右轴 的点积符号 —— 相机空间的 vel.x 就是这个点积，不是另造的约定。
        // 点积 > 0 表示粒子在画面上往右甩，雨丝就该拖着尾部往右后方倒；< 0 反过来。
        // 之前这里写死了 -vel.x，等于把方向固定成「朝车头那侧翻」，所以雨丝全部斜反了。
        final float horizDotRight = vel.x;
        final float planarSpeedSq = vel.x * vel.x + vel.y * vel.y;
        final float tilt = planarSpeedSq > 1.0E-8F
                ? MathHelper.clamp(Math.copySign(
                        (float) Math.atan2(Math.abs(vel.x), Math.abs(vel.y)), horizDotRight),
                        -MAX_TILT, MAX_TILT)
                : 0.0F;
        for (final Vector3f corner : corners) {
            corner.y *= VERTICAL_STRETCH;
            corner.rotateZ(tilt);
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
