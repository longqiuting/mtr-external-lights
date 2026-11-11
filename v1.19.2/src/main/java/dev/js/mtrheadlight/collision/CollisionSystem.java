package dev.js.mtrheadlight.collision;

import dev.js.mtrheadlight.DepartureState;
import dev.js.mtrheadlight.HeadlightConfig;
import dev.js.mtrheadlight.MtrHeadlightClient;
import dev.js.mtrheadlight.TrainPose;
import dev.js.mtrheadlight.net.ServerAuthority;
import mtr.client.ClientData;
import mtr.data.TrainClient;
import mtr.data.TransportMode;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 玩家与列车 / 轨道道床的碰撞解算 —— 客户端、只作用于本地玩家。
 *
 * <h2>挂在哪</h2>
 * {@code dev.js.mtrheadlight.mixin.collision.MixinEntityMove} 往 {@code Entity#move} 上挂了三处：
 * <ul>
 *   <li>{@code HEAD} → {@link #beforeMove}：记下移动前的 y（道床判定要用「从上方穿过」的语义），
 *       并把这一 tick 会穿过车顶的竖直位移截到车顶（防止高速下落时一 tick 穿过去）；</li>
 *   <li>{@code HEAD} → {@link #clampMovement}：{@code @ModifyVariable} 改写 {@code movement.y}；</li>
 *   <li>{@code TAIL} → {@link #afterMove}：原版碰撞（方块、台阶、水）全部算完之后，再处理车体推出、
 *       车顶站立、道床站立。</li>
 * </ul>
 * 之所以不在 {@code ClientTickEvents} 里直接改 pos：那样会与原版 {@code move} 的碰撞结果互相覆盖
 * （同一 tick 里先被方块推出、再被我们拉回去）。挂在 {@code move} 里，我们的位移就是这一 tick 的
 * 最后一次写入，且发生在 {@code ClientPlayerEntity#move} 发移动包之前 —— 服务器看到的是修正后的位置，
 * 不会因为「客户端位置与服务器不一致」而被拉回。
 *
 * <p><b>为什么 {@code Entity#move} 而不是 {@code ClientPlayerEntity#move}</b>：后者会覆盖，但它先
 * {@code super.move(...)} 再发包，所以挂在父类上天然在发包之前；而 {@code Entity#move} 对所有实体
 * 只挂一次，处理器开头一个 {@code entity == client.player} 就挡掉了全部其它实体（含服务端线程）。
 *
 * <h2>车厢碰撞箱：分段近似 OBB</h2>
 * 每节车厢从 {@link TrainPose} 拿到两个端点 a（车头面）和 b（车尾面），沿车厢轴线把它切成若干
 * 短段，<b>每段是一小段 OBB</b>（有中心、轴、法向、半长、半宽），推出在段的局部坐标系里算：
 *
 * <pre>
 * u  = normalize(b - a)（只看水平分量）            n = (-u.z, 0, u.x)
 * segCount = clamp(ceil(len / 2.0), 1, min(32, floor(len / 1.5)))     // 段长落在 1.5–2.0 格
 * seg[i] 覆盖 s ∈ [i·segLen - 0.15, (i+1)·segLen + 0.15] ∩ [0, len]   // 段间重叠 0.3 格
 * 段内判定：pen_n = halfW + proj_n − |lat|      pen_u = halfLen + proj_u − |along|
 *           pen_n ≤ 0 或 pen_u ≤ 0 → 分离（不碰）
 * </pre>
 *
 * <p><b>为什么不再是「两端点的轴对齐包围盒」</b>：旧方案用一个 AABB 包住整节倾斜的车厢，弯道上
 * 那个盒子在 X/Z 方向各要多出 {@code (len/2 + w)·|u| − len/2} 格；20 格长的车厢斜置 45° 时，
 * 盒子的角比车体角远出约 {@code (len/2 + w)(√2 − 1) ≈ 3.4} 格 —— 玩家一碰到就被推到 3 格开外，
 * 这就是「过弯被撞飞」。现在推出的方向是<b>段自身的法向</b>（或端盖的轴向），穿透量只有真实的
 * {@code halfW + 玩家半宽 − |lat|}，与车厢相对世界坐标的朝向<b>无关</b>：车厢转 0°/45°/90°，
 * 推出距离完全一样。分段的意义是：(a) 让粗筛用的 AABB 贴着车体（每段只有 2 格长，AABB 在 45° 时
 * 只比车体角远 0.66 格，而整节车是 3.4 格）；(b) 让「最近几段」的局部检测成为可能（每 tick 每节车
 * 只测 3 段）；(c) 让每段的竖直范围跟着坡度走。
 *
 * <p><b>残差</b>：一段的 AABB 仍然比这段 OBB 大（45° 时单段角上多 0.66 格），但推进量不再取自
 * AABB，所以这个残差只影响粗筛，不会变成位移。真正的误差只剩「车厢是弦、轨道是弧」的矢高
 * {@code len²/(8R)}（R = 100 时约 0.5 格）—— 那是 MTR 自己画车厢的方式（每节车一个直的盒子），
 * 也就是玩家看到的形状，属于「对得上画面」而不是误差。
 *
 * <h2>车头车尾外扩与连接处</h2>
 * 每节车两端各有一个<b>端盖</b>：从 car 端面沿轴向向外 {@code trainCollisionEndPad}（默认 1.0 格，
 * 钳制 0–3），宽度仍是车宽。端盖是唯一允许「轴向推出」的部分（车身段只做侧向推出，避免相邻段
 * 之间来回弹）。连接处的覆盖量 = 两端盖 {@code 2 × endPad = 2.0} 格 + 两侧最外段的段重叠
 * {@code 2 × 0.15 = 0.3} 格，合计 <b>2.3 格</b>：车钩、端墙、跨接处都不再漏。
 *
 * <p>整列车的<b>两头</b>可以单独调：头车的前端（行进方向）用
 * {@code max(trainCollisionEndPad, collisionHeadExtra)}，尾车的后端用
 * {@code max(trainCollisionEndPad, collisionTailExtra)}（各 0–4 格，默认都是 1.0，所以不调时
 * 与只有 endPad 时完全一致）。两者和 endPad 描述的是同一处外扩，因此取 {@code max} 而不是相加。
 * 中间车的两端、头车的后端、尾车的前端仍然只用 endPad。这两个值走
 * {@link HeadlightConfig.Effective}（按列车所属侧线解析），所以侧线可以单独覆盖。
 *
 * <h2>推出算法</h2>
 * 每段给出穿透量 {@code pen}（车顶/车底/方块先行排除），沿该段的法向（端盖还可沿轴向、且只能向外）
 * 把玩家推出去，位移 = {@code pen + 1e-3}；推出去之后 {@code isSpaceEmpty} 不成立就<b>不推</b>
 * （被夹在车与墙之间时宁可停在原地，也不把玩家推进方块）。速度只削掉「指向车厢」的那个分量，
 * 不整轴清零 —— 玩家贴着车走的时候不会被粘住，也不会因为一次推出获得额外速度。
 *
 * <h2>车顶站立</h2>
 * 车顶平面不是平的：{@code roof(t) = a.y + (b.y - a.y)·t + height}，t 是玩家在车厢轴上的投影
 * （坡道上随车倾斜）。玩家在车顶正上方、竖直速度 ≤ 0、脚底落在 {@code [roof - 0.45, roof + 0.05]}
 * 时：脚底吸到 roof、{@code setOnGround(true)}、{@code fallDistance = 0}，并按该车厢这一 tick 的
 * 水平位移平移玩家 —— 位移取自相邻两 tick 的同一节车厢中心，是「移动平台」的做法，天然不会
 * 被车推飞，也不会重复积分（若把列车速度加进 {@code velocity}，原版 {@code move} 下一 tick 还会再
 * 用一次速度，等于走两倍）。
 *
 * <p><b>什么时候不载人（滑落）</b>：
 *
 * <pre>
 * slideOff = 车速 &gt; roofDropMinSpeedKmh (50)  AND  曲率半径 &lt; roofDropMaxRadiusM (100 格)
 * 曲率半径 R = (|P(mid−3) − P(mid)| + |P(mid+3) − P(mid)|) / θ
 *              θ = 两段弦的水平夹角；θ &lt; 0.02 rad 或采样退化 → R = ∞（直道）
 * </pre>
 *
 * 满足条件时这一 tick 不吸附、不载人：玩家留在原地，车从他脚下开走，随后自然下落。其余情况
 * 一律继续粘在车顶上（旧行为是「一过弯就掉」，现在只有高速急弯才掉）。
 *
 * <p><b>车顶上的行走动画</b>：踩在车顶上时玩家相对车不动，但水平速度还在，原版会一直播
 * 走路摆腿。{@link #tick()} 在 {@code END_CLIENT_TICK}（渲染之前的最后一次写入）把
 * {@code limbAnimator} 的速度置 0，于是第三视角里腿是静止的；离开车顶的那个 tick 立刻恢复
 * （只清这一 tick 的 {@code speed}，不动 {@code pos}，所以恢复时不会跳帧）。
 *
 * <h2>开门放行</h2>
 * <b>列车级，不是车厢级</b>：只要任意一节车厢的门开着（或正在开/关，{@code getDoorValue() > 0}），
 * 整列车的<b>每一节</b>车厢侧面都不参与碰撞 —— 车身段整段跳过，只剩两个端盖（车头车尾照旧挡人）
 * 和车顶（独立的一遍）。这样「只有一节车停在站台门口」时，从任意车厢侧面都能走进车里，不会再被
 * 没开门的那几节挡在外面。MTR 只把「有没有门在开」同步到客户端（{@code doorOpening} 一个 boolean），
 * 没有左右两侧的区分，所以两侧一起放行；站台侧能走进车里，另一侧同样能。侧面碰撞面还额外内缩
 * {@value #SIDE_INSET} 格 —— 站在车门口边缘时不再被那 5 cm 的边界判定卡住（这是「开门时有概率
 * 上不了车」的第二半原因）。
 *
 * <h2>乘车豁免（MTR 乘客）</h2>
 * MTR 的乘客<b>不是</b>原版 vehicle：它自己用 {@code Train#ridingEntities} 记账，
 * {@code player.hasVehicle()} 在车厢里通常是 false。所以「坐着也算被车体包住」这件事必须单独判定：
 * {@link #isRiding} 通过反射问一次 {@code Train#isPlayerRiding}，一旦为真，<b>推出 / 车顶吸附 /
 * 道床整套跳过</b>（只保留 {@link #flight} 的飞行伤害）。不跳过的后果就是玩家在座位里被我们的
 * 推出和 MTR 的座位位置每 tick 互相拉扯 —— 表现为「被挤出车外 + 视角一直抖」。
 *
 * <h2>碰撞伤害：按车速分档</h2>
 * 玩家被<b>运行中</b>的列车撞到（{@link #pushSegment} 真的推出了位移）时，后果按车速分档
 * （阈值与系数全部来自 {@link HeadlightConfig}，公式与状态在 {@link CollisionImpact}）：
 *
 * <pre>
 * v &lt;  5    只推开
 * v &lt;  20   推出 + 低速率扣血
 * v &lt;  50   推出 + 较高速率扣血
 * v &lt;  80   同上 + 撞飞冲量（水平远离车体，向上分量很小）
 * v &gt;= 80   更大的冲量，并且飞行/落地里持续扣血直到死亡
 *
 * hp/s = base · exp(k · (v − v0) / scale) · (v ≥ 80 ? flyScale : 1)，按 tick 积分
 * 冲量  = 推出方向（水平）· 水平值 + (0, 竖直值, 0)，20 tick 内只结算一次
 * </pre>
 *
 * <p>伤害打的是<b>服务端玩家实例</b>（单人即整合服务器），走 {@code LivingEntity#damage}；死亡消息
 * 来自 {@link TrainImpactDamageSource}，两段文案按 50 km/h 分界。创造 / 旁观免疫。多人服务器上
 * 没有我们的服务端组件，退化成对本地玩家实例调用（只有受击表现，血量由服务器同步回来）。
 * 其它生物（村民 / 猫 / 狗 / 动物…）由 {@link MobCollision} 在整合服务器上走同一套后果。
 *
 * <h2>道床</h2>
 * 见 {@link Trackbed}：轨面 y 直接来自 {@code Rail#getPosition}，可站立面 = 轨面 + thickness。
 * 只有「已经站在上面」或「这一 tick 从上方落下来穿过它」才生效；脚下真正实心方块的顶面不低于
 * 道床面时按原版走（真实方块优先）。
 */
public final class CollisionSystem {

    /** 只有这个距离内的列车才生成碰撞箱（格）。 */
    private static final double SEARCH_RANGE = 48.0D;

    /** 推出时多给的一点余量，避免浮点数贴着边界反复相交。 */
    private static final double PUSH_EPS = 1.0E-3D;

    /** 侧面碰撞面内缩量（格）：车门边缘不再卡人。 */
    private static final double SIDE_INSET = 0.05D;

    /**
     * 正面撞击的判定宽度：轴向穿透小于 {@code 横向穿透 × 本值} 就算「车头顶着人」。
     * 1.5 是让车头/车尾那一小段（端盖 1.0 格 + 半车宽约 1.25）在深穿透时仍然走轴向 —— 取 1.0 太
     * 严（80 km/h 一 tick 就压进 1.1 格，direct 会退回横向），取 2.5 又会让侧面擦碰被误判成正面。
     */
    private static final double FRONTAL_PEN_RATIO = 1.5D;

    /** 速度推送：水平速度上限（格/tick，≈97 km/h），避免异常 pose 给出荒谬推力。 */
    private static final double MAX_CARRY_VEL = 1.35D;

    /** 速度推送：侧面沿行进方向带的比例（不是 1.0 —— 擦身而过应该被甩出去，不是被拖着走）。 */
    private static final double SIDE_CARRY = 0.45D;

    /** 速度推送：侧向挤出速度 = 车速 × 本值，下限 {@link #MIN_OUT_VEL}（格/tick）。 */
    private static final double OUT_VEL_SCALE = 0.55D;

    /** 侧向挤出速度的下限（格/tick）：慢车推人也要明显，否则玩家会"粘"在车侧面。 */
    private static final double MIN_OUT_VEL = 0.12D;

    /**
     * 速度推送的写入门槛（格/tick）：目标速度与当前速度差小于它就不写。
     *
     * <p>每 tick 无条件 {@code setVelocity} 即使数值几乎一样，也会让客户端每帧都在改速度、
     * 位置插值来回抢，表现为「被推着走的时候画面一直在抖」。
     */
    private static final double PUSH_VEL_EPS = 0.03D;

    /**
     * 门值超过这个数就算「门已经开了」：0 = 完全关闭，1 = 完全打开。2% 刚好在关闭动画的第一帧
     * 之后，又远低于任何肉眼可见的缝隙，所以列车级放行不会因为门抖动而误判成关闭。
     */
    private static final float DOOR_VALUE_EPS = 0.02F;

    /** 相邻分段之间的重叠量（格），前后各分一半。 */
    private static final double SEG_OVERLAP = 0.3D;

    /** 目标段长（格）：段数 = ceil(len / 2)，但段长不低于 1.5 格。 */
    private static final double SEG_TARGET_LEN = 2.0D;
    private static final double SEG_MIN_LEN = 1.5D;

    /** 段数上限：异常 pose（车厢长度离谱）时不至于生成几百个盒子。 */
    private static final int SEG_MAX = 32;

    /** 每节车每 tick 最多检测的段数（以玩家投影位置为中心）。 */
    private static final int SEG_WINDOW = 1;

    /** 车顶认定的脚底下容差（格）。 */
    private static final double ROOF_TOL = 0.45D;

    /** 车顶认定的脚底上容差：再高就是还在下落，交给下一 tick 的 HEAD 截断。 */
    private static final double ROOF_ABOVE = 0.06D;

    /** 单 tick 的载人位移上限；超过就当作列车瞬移（重同步），不载人。 */
    private static final double CARRY_MAX = 4.0D;

    /** 车宽缺失时的半宽兜底（格）。 */
    private static final double DEFAULT_HALF_WIDTH = 1.3D;

    /**
     * 半车宽的下限（格）。MTR 的 {@code Train#width} 不是量出来的格数，而是车型字符串里的一个小
     * 整数因子（实测 {@code train_sp1900} 与 {@code train_m_train} 都是 1，裸 {@code sp1900} 是 2），
     * 所以 {@code width / 2} 只能当参考：真按 0.5 格算，玩家能直接走进车体。MTR 的车体本身约 2.6–3
     * 格宽，这里以 1.3 格（2.6 格宽）为下限。
     */
    private static final double MIN_HALF_WIDTH = 1.3D;

    /** 内缩之后还留着的绝对下限（格），挡住异常配置。 */
    private static final double MIN_HALF_WIDTH_ABS = 0.6D;

    /** 曲率采样半窗（格）：车厢中点 ±本值取三点。 */
    private static final double CURVE_EPS = 3.0D;

    /** 三点转角小于这个值就当直道（弧度，约 1.1°）。 */
    private static final double STRAIGHT_ANGLE = 0.02D;

    /** {@code Train#getSpeed()}（blocks/tick）→ km/h，与 MTR 驾驶台同一换算。 */
    private static final double KMH_PER_BLOCK_PER_TICK = 72.0D;

    /** 诊断窗口：100 client ticks = 5 s。 */
    private static final int DIAG_PERIOD = 100;

    // ---- 每 tick 的临时状态 ----------------------------------------------------------------

    /** 自己的 tick 计数：不依赖 {@code LightController} 的计数器（那条可能提前 return）。 */
    private static long ticks;

    /** 本 tick 移动前的脚底 y（道床「从上方穿过」判定）。 */
    private static double startY = Double.NaN;

    private static Entity cacheEntity;
    private static long cacheTick = -1L;
    private static double cacheHeight;
    private static double cacheEndPad;
    private static List<CarHit> cacheCars = List.of();

    /** 本 tick 玩家踩在哪个车厢的车顶上（null = 没踩）。车顶动画修正要用。 */
    private static String roofKey;

    /** 本 tick 推出方向（单位向量，水平）：撞飞冲量沿它走。没推过就是 (0,0)。 */
    private static double pushDirX;
    private static double pushDirZ;

    /** 每节车厢上一 tick 的中心，用来算「移动平台」位移。key = trainKey + '#' + carIndex。 */
    private static final Map<String, CarTrack> TRACKS = new HashMap<>();

    // ---- 诊断 ------------------------------------------------------------------------------

    private static int diagCars;
    private static int diagSegs;
    private static int diagHits;
    private static double diagDamage;
    private static int diagPushed;
    /** 本窗口里速度推送的次数（速度推送 = 「手感」那一层，与位置推出分开计）。 */
    private static int diagVelPushes;
    /** 本窗口里由「撞击后持续掉血窗口」扣掉的血量（hp）。 */
    private static double diagHurt;
    /** 上一次快照的冲量累计值：诊断行里的 {@code impulses=} 是这 5 秒的差值。 */
    private static long diagImpulsesLast;
    /** 本窗口里因为「坐在车里」而整段跳过的实体 tick 数（诊断：确认乘车豁免真的生效）。 */
    private static int diagRiding;
    private static double diagTrackbed = Double.NaN;
    /** 本窗口内真正用到的两端延长（格，取峰值）；NaN = 这一窗没有生成任何车厢。 */
    private static double diagHead = Double.NaN;
    private static double diagTail = Double.NaN;

    /** 上一 tick 的世界，用来发现进/退世界与维度切换。 */
    private static ClientWorld lastWorld;

    // ---- 乘车判定缓存（一 tick 只问一次 MTR）-----------------------------------------------
    private static long rideTick = -1L;
    private static Entity rideEntity;
    private static boolean rideCached;
    private static java.lang.reflect.Method RIDING_CHECK;
    private static boolean RIDING_PROBED;

    private static final java.util.concurrent.atomic.AtomicBoolean WARNED =
            new java.util.concurrent.atomic.AtomicBoolean();

    private CollisionSystem() {
    }

    // ---------------------------------------------------------------------------------------------
    // 乘车判定（MTR 自己记 ridingEntities，不一定走原版 vehicle）
    // ---------------------------------------------------------------------------------------------

    /**
     * MTR 的乘客<b>不是</b>原版意义上的 vehicle：3.x 用 {@code Train#ridingEntities} 记账，
     * {@code player.hasVehicle()} 在车厢里常常是 false。此时车体推出仍然生效，座位每 tick 又把人
     * 拉回去 —— 玩家看到的就是「坐在车里被挤、视角一直抖」。
     *
     * <p>所以推出 / 车顶这两块在「玩家是某列车乘客」时必须<b>整套跳过</b>，只保留伤害那条线
     * （{@link #flight}）。判定问的是 MTR 自己的 {@code isPlayerRiding}。
     */
    private static boolean isRiding(final Entity entity) {
        if (!(entity instanceof PlayerEntity player)) {
            return false;
        }
        if (player.hasVehicle() || player.getVehicle() != null) {
            return true;
        }
        // 每 tick 只问一次（同一实体）：一 tick 里 beforeMove / clampMovement / afterMove 会问三遍。
        if (rideTick == ticks && rideEntity == entity) {
            return rideCached;
        }
        rideTick = ticks;
        rideEntity = entity;
        rideCached = ridingAny(player);
        return rideCached;
    }

    /** 这个玩家正坐在某列 MTR 列车里吗？拿不到数据时返回 false（几何照常判定）。 */
    private static boolean ridingAny(final PlayerEntity player) {
        final java.lang.reflect.Method check = ridingCheck();
        if (check == null) {
            return false;
        }
        final List<TrainClient> trains;
        try {
            trains = new ArrayList<>(ClientData.TRAINS);
        } catch (Throwable t) {
            return false;
        }
        for (final TrainClient train : trains) {
            if (train == null) {
                continue;
            }
            try {
                if (Boolean.TRUE.equals(check.invoke(train, player))) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 反射失败：当作没坐车，同 ServerCollision 的处理。
            }
        }
        return false;
    }

    /**
     * 懒加载 + 缓存 {@code Train#isPlayerRiding(PlayerEntity)}。
     *
     * <p>MTR 的 jar 没有 remap，形参在字节码里是 intermediary 名（{@code class_1657}），直接用
     * 具名类型调用编译不过；运行期两者是同一个类，所以反射拿得到。{@code TrainClient} 继承
     * {@code Train}，方法就声明在父类上。
     */
    private static java.lang.reflect.Method ridingCheck() {
        if (RIDING_PROBED) {
            return RIDING_CHECK;
        }
        RIDING_PROBED = true;
        try {
            RIDING_CHECK = TrainClient.class.getMethod("isPlayerRiding", PlayerEntity.class);
        } catch (Throwable t) {
            RIDING_CHECK = null;
        }
        return RIDING_CHECK;
    }

    // ---------------------------------------------------------------------------------------------
    // 注入点 1/2：Entity#move 的 HEAD
    // ---------------------------------------------------------------------------------------------

    /** 记录移动前的脚底 y。所有判定都以它作为「这一 tick 从哪来」的基准。 */
    public static void beforeMove(final Entity entity) {
        if (!isLocal(entity)) {
            return;
        }
        startY = entity.getY();
    }

    /**
     * 把「这一 tick 会穿过车顶」的竖直位移截到车顶。
     *
     * <p>不截断的话，自由落体 3 格/tick 时玩家会在一 tick 内从车顶上方 2 格掉到车顶下方 1 格，
     * TAIL 的判定就再也看不到「站在车顶上」这个瞬间了（穿模）。
     *
     * <p>只处理「脚底已经在车顶平面上或之上」的情形：脚底低于车顶说明玩家在车体内部或车下方
     * （例如从高架下面走过），那是另一半逻辑（侧向推出），不能把人往上一顶。
     */
    public static Vec3d clampMovement(final Entity entity, final Vec3d movement) {
        if (!isLocal(entity) || movement.y >= 0.0D) {
            return movement;
        }
        // 坐在车里：座位自己决定高度，车顶截断只会把人往下按（坐着时脚底就在车顶平面附近）。
        if (isRiding(entity)) {
            return movement;
        }
        try {
            final HeadlightConfig cfg = MtrHeadlightClient.config();
            if (!cfg.trainCollision) {
                return movement;
            }
            final double height = cfg.trainCollisionHeightClamped();
            final double feet = entity.getY();
            double clamped = movement.y;
            for (final CarHit car : cars(entity, height)) {
                final double roof = car.roofAt(entity.getX(), entity.getZ(), height);
                if (Double.isNaN(roof)) {
                    continue;
                }
                if (feet < roof - ROOF_ABOVE) {
                    continue;
                }
                // 会落到车顶平面以下 → 截到车顶。
                if (feet + clamped < roof) {
                    clamped = roof - feet;
                }
            }
            return clamped == movement.y ? movement : new Vec3d(movement.x, clamped, movement.z);
        } catch (Throwable t) {
            warnOnce(t);
            return movement;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 注入点 3：Entity#move 的 TAIL
    // ---------------------------------------------------------------------------------------------

    /** 原版碰撞完成之后的解算：先是车顶（含载人），否则是车体推出，最后是道床。 */
    public static void afterMove(final Entity entity) {
        if (!isLocal(entity)) {
            return;
        }
        roofKey = null;
        try {
            final HeadlightConfig cfg = MtrHeadlightClient.config();
            if (!cfg.trainCollision && !cfg.trackbedCollision) {
                return;
            }
            final PlayerEntity player = (PlayerEntity) entity;
            if (player.isDead() || player.getHealth() <= 0.0F) {
                // 已经死了：不再推、不再吸车顶、不再扣血。死后还被车推着走正是「死了画面还在抖」。
                roofKey = null;
                return;
            }
            if (player.isSpectator() || player.hasVehicle()) {
                // 旁观者穿一切；坐在车里/船上的人由载具决定位置，动他只会抖动。
                return;
            }
            // MTR 的乘客（ridingEntities）不算原版载具：推出 / 车顶必须整套跳过，否则座位每 tick
            // 把玩家拉回去、我们每 tick 再推出来 —— 就是「坐车被挤 + 视角抖」。伤害那条线
            // （flight）仍然保留：万一是被撞飞之后才上的车，飞行伤害还得结算完。
            if (isRiding(player)) {
                roofKey = null;
                diagRiding++;
                flight(player, cfg);
                return;
            }
            final ClientWorld world = (ClientWorld) entity.getWorld();
            final double height = cfg.trainCollisionHeightClamped();
            final List<CarHit> cars = cfg.trainCollision ? cars(entity, height) : List.of();

            // 1) 车顶优先：站在车顶上时不能又被侧向推出（坡道上车顶面低于包围盒顶面，两者会打架）。
            boolean onRoof = false;
            for (final CarHit car : cars) {
                if (standOnRoof(player, car, height, world)) {
                    onRoof = true;
                    roofKey = car.key;
                    break;
                }
            }

            // 2) 车体推出：车身段只做侧向推、端盖还要挡住车头车尾。
            //    返回 true = 「撞上了」（哪怕位置一格都推不动、只给了速度），伤害与冲量都挂在这个
            //    判定上 —— 以前要求「位置推出成功」才算撞击，被夹在车与墙之间时于是既不推也不疼。
            int contacted = 0;
            double hitSpeed = 0.0D;
            long hitSidingId = 0L;
            double hitDirX = 0.0D;
            double hitDirZ = 0.0D;
            pushDirX = 0.0D;
            pushDirZ = 0.0D;
            if (!onRoof) {
                for (final CarHit car : cars) {
                    if (!pushCar(player, car, world)) {
                        continue;
                    }
                    contacted++;
                    if (car.speedKmh > hitSpeed) {
                        hitSpeed = car.speedKmh;
                        // 伤害按「撞上的是哪列车」的侧线解析，不是玩家脚下那条。
                        hitSidingId = car.sidingId;
                        hitDirX = pushDirX;
                        hitDirZ = pushDirZ;
                    }
                }
            }
            diagPushed += contacted;
            diagHits += contacted;
            if (hitSpeed > 0.0D) {
                impact(player, cfg, hitSpeed, hitSidingId, hitDirX, hitDirZ);
            }
            // 上一次 >80 的撞击：飞行/落地中继续扣血直到死亡，不再需要碰到车。
            flight(player, cfg);
            // 其它档位的「撞击后持续掉血窗口」：接触只有几 tick，光靠接触期的伤害永远死不了。
            hurt(player, cfg);

            // 3) 道床：只有在没站在车顶时才考虑。
            if (!onRoof && cfg.trackbedCollision) {
                trackbed(player, cfg, world);
            }

            // 4) 记下这一 tick 的车厢中心，下一 tick 算载人位移用。
            remember(cars);
            int segs = 0;
            for (final CarHit car : cars) {
                segs += car.body.length + car.caps.length;
            }
            diagCars = Math.max(diagCars, cars.size());
            diagSegs = Math.max(diagSegs, segs);
        } catch (Throwable t) {
            warnOnce(t);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 每 tick
    // ---------------------------------------------------------------------------------------------

    /** {@code END_CLIENT_TICK}：车顶动画修正、推进计数器、打诊断、世界切换时清缓存。 */
    public static void tick() {
        ticks++;
        final MinecraftClient client = MinecraftClient.getInstance();
        final ClientWorld world = client == null ? null : client.world;
        if (world != lastWorld) {
            reset();
            lastWorld = world;
        }
        stillLimbs(client == null ? null : client.player);
        // 流血窗口内把原版受击镜头晃动按掉：每 10 tick 一笔伤害会把 hurtTime 反复重置成 10，
        // 一秒两次的连续倾斜就是玩家看到的「一直抖」。放在诊断早退之前 —— 关了诊断也要生效。
        CollisionImpact.suppressHurtTilt(client == null ? null : client.player);
        CollisionImpact.sweep(ticks);

        final HeadlightConfig cfg = MtrHeadlightClient.config();
        if (!cfg.collisionDiagnostics) {
            resetDiag();
            return;
        }
        if (ticks % DIAG_PERIOD != 0L) {
            return;
        }
        final int cars = diagCars;
        final int segs = diagSegs;
        final int hits = diagHits;
        final double damage = diagDamage;
        final int pushed = diagPushed;
        final int velPushes = diagVelPushes;
        final double hurt = diagHurt;
        final long impulses = CollisionImpact.impulsesTotal() - diagImpulsesLast;
        diagImpulsesLast = CollisionImpact.impulsesTotal();
        final double bed = diagTrackbed;
        final double headExtra = diagHead;
        final double tailExtra = diagTail;
        final int riding = diagRiding;
        resetDiag();
        try {
            MtrHeadlightClient.LOGGER.info(
                    "[lte] collision: cars={} segs={} hits={} damage={} trackbed={} pushed={}"
                            + " impulses={} vel={} hurt={} head=+{} tail=+{} pad={} riding={}"
                            + " creativeDmg={}",
                    Integer.valueOf(cars),
                    Integer.valueOf(segs),
                    Integer.valueOf(hits),
                    String.format(Locale.ROOT, "%.1f", Double.valueOf(damage)),
                    bed == bed ? String.format(Locale.ROOT, "%.2f", Double.valueOf(bed)) : "-",
                    Integer.valueOf(pushed),
                    Long.valueOf(impulses),
                    Integer.valueOf(velPushes),
                    String.format(Locale.ROOT, "%.1f", Double.valueOf(hurt)),
                    endExtraText(headExtra),
                    endExtraText(tailExtra),
                    String.format(Locale.ROOT, "%.1f",
                            Double.valueOf(cfg.trainCollisionEndPadClamped())),
                    Integer.valueOf(riding),
                    Boolean.valueOf(cfg.damageInCreative));
        } catch (Throwable t) {
            // 日志永远不许影响 tick。
        }
    }

    /**
     * 车顶静止：踩在车顶上时把行走动画的幅度清零。
     *
     * <p>1.20.1 的人形模型从 {@code LivingEntity#limbAnimator} 取摆腿相位/幅度，但渲染读到的是
     * <b>插值</b>过的值 —— {@code getSpeed(partialTick)} 在 {@code prevSpeed} 与 {@code speed}
     * 之间线性插值。只 {@code setSpeed(0)} 的话，一帧里 partialTick 从 0 走到 1，画面上仍然会先
     * 出现旧速度（tick 刚结束时 partialTick≈0）再滑到 0，看起来像在抽腿。
     *
     * <p>所以用 {@code updateLimbs(0, 1)} 跑两遍：它的语义是
     * 「{@code prevSpeed = speed}；{@code speed += (target - speed) · smoothing}」——
     * 第一遍把 {@code speed} 砸到 0，第二遍把 {@code prevSpeed} 也砸到 0。{@code pos} 每遍加的是
     * 新的 {@code speed}（= 0），所以相位不动，离开车顶那一 tick 不会跳帧。
     *
     * <p>只在 {@code END_CLIENT_TICK} 写一次：此时这一 tick 的 {@code updateLimbs} 已经跑完、渲染
     * 还没开始，写进去的就是渲染看到的值；下一 tick 若已不在车顶，原版会自己算回正常幅度。
     */
    private static void stillLimbs(final ClientPlayerEntity player) {
        if (roofKey == null || player == null) {
            return;
        }
        // 1.19.2 没有 LimbAnimator（1.19.4 才有）：腿的摆幅就是 LivingEntity 上的
        // limbDistance / lastLimbDistance 两个字段（limbAngle 只是相位累加）。
        // 把两个距离都砸到 0 等于「这一 tick 没在走」，渲染看到的腿就是并拢的；
        // 离开车顶后原版 tick 会自己算回正常值。
        if (player.limbDistance == 0.0F && player.lastLimbDistance == 0.0F) {
            return;
        }
        player.limbDistance = 0.0F;
        player.lastLimbDistance = 0.0F;
    }

    /** 世界 / 维度切换：碰撞箱缓存、载人位移、warn 状态全部作废。 */
    public static void reset() {
        cacheEntity = null;
        cacheTick = -1L;
        cacheCars = List.of();
        roofKey = null;
        rideTick = -1L;
        rideEntity = null;
        rideCached = false;
        TRACKS.clear();
        CollisionImpact.clear();
        Trackbed.reset();
        Trackbed.clearWarned();
        resetDiag();
    }

    // ---------------------------------------------------------------------------------------------
    // 车厢盒
    // ---------------------------------------------------------------------------------------------

    /** 一段车厢（车身段或端盖）：一段小 OBB + 粗筛用的 AABB。 */
    private static final class Seg {
        /** 中心（水平）。 */
        final double cx;
        final double cz;
        /** 轴向单位向量（a → b）与法向单位向量（水平左手侧）。 */
        final double ux;
        final double uz;
        final double nx;
        final double nz;
        /** 半长（沿轴）与半宽（沿法向）。 */
        final double halfLen;
        final double halfW;
        /** 竖直范围：本段轨面最低点 / 车顶最高点。 */
        final double yLo;
        final double yHi;
        /** 粗筛 AABB（包含本段 OBB）。 */
        final double minX;
        final double maxX;
        final double minZ;
        final double maxZ;
        /** 是不是端盖（车头/车尾外扩段）。只有端盖允许轴向推出。 */
        final boolean cap;
        /** 端盖的向外方向在轴上的符号：车头 -1、车尾 +1；车身段为 0。 */
        final int side;

        Seg(final double cx, final double cz, final double ux, final double uz,
            final double halfLen, final double halfW, final double yLo, final double yHi,
            final boolean cap, final int side) {
            this.cx = cx;
            this.cz = cz;
            this.ux = ux;
            this.uz = uz;
            this.nx = -uz;
            this.nz = ux;
            this.halfLen = halfLen;
            this.halfW = halfW;
            this.yLo = yLo;
            this.yHi = yHi;
            final double ex = Math.abs(ux) * halfLen + Math.abs(this.nx) * halfW;
            final double ez = Math.abs(uz) * halfLen + Math.abs(this.nz) * halfW;
            this.minX = cx - ex;
            this.maxX = cx + ex;
            this.minZ = cz - ez;
            this.maxZ = cz + ez;
            this.cap = cap;
            this.side = side;
        }
    }

    /** 一节车厢的碰撞几何 + 这一 tick 的位移。 */
    private static final class CarHit {
        final String key;
        final int index;
        /** 车厢在轨面上的两个端点，a = 行进方向的前端。 */
        final Vec3d a;
        final Vec3d b;
        /** 水平单位轴 u（a → b）与半车宽（已内缩 {@value #SIDE_INSET} 格）。 */
        final double ux;
        final double uz;
        final double len;
        final double halfW;
        final double cx;
        final double cz;
        /** 车顶平面两端的 y（a.y + height，b.y + height）与轨面两端的 y。 */
        final double roofA;
        final double roofB;
        final double floorA;
        final double floorB;
        final boolean doorsOpen;
        /** 这一节车所属列车的速度（km/h）。 */
        final double speedKmh;
        /**
         * 这节车所属列车的侧线 id（{@code Train.sidingId}，0 = 没有侧线）。伤害 / 水雾这些
         * 侧线可覆盖的参数按它解析，不看玩家站在哪儿。
         */
        final long sidingId;
        /** 这节车所在轨道的曲率半径（格）；直道 / 拿不到轨道 = 无穷大。 */
        final double radiusM;
        /** 车身分段（按轴向顺序）。 */
        final Seg[] body;
        /** 车头 / 车尾外扩段，[0] = 车头、[1] = 车尾。 */
        final Seg[] caps;
        /** 这一 tick 的水平位移（由上一 tick 的中心算出）。 */
        double deltaX;
        double deltaZ;
        /** 位移是不是一个可用的运动方向（上一 tick 有记录、且不是重同步跳跃）。 */
        boolean moving;
        /** 运动方向的单位向量与这一 tick 的速度（格/tick）。 */
        double moveX;
        double moveZ;
        double moveSpeed;

        CarHit(final String key, final int index, final Vec3d a, final Vec3d b, final double ux,
               final double uz, final double len, final double halfW, final double height,
               final boolean doorsOpen, final double speedKmh, final double radiusM,
               final double capA, final double capB, final long sidingId) {
            this.key = key;
            this.index = index;
            this.a = a;
            this.b = b;
            this.ux = ux;
            this.uz = uz;
            this.len = len;
            this.halfW = halfW;
            this.roofA = a.y + height;
            this.roofB = b.y + height;
            this.floorA = a.y;
            this.floorB = b.y;
            this.doorsOpen = doorsOpen;
            this.speedKmh = speedKmh;
            this.radiusM = radiusM;
            this.sidingId = sidingId;
            this.cx = (a.x + b.x) * 0.5D;
            this.cz = (a.z + b.z) * 0.5D;
            this.body = buildBody(height);
            this.caps = buildCaps(height, capA, capB);
        }

        /** 车身分段：段长 = len / segCount（1.5–2.0 格），相邻段各重叠 0.15 格。 */
        private Seg[] buildBody(final double height) {
            int count = (int) Math.ceil(len / SEG_TARGET_LEN);
            final int upper = Math.max(1, (int) Math.floor(len / SEG_MIN_LEN));
            count = Math.max(1, Math.min(Math.min(count, upper), SEG_MAX));
            final double segLen = len / count;
            final Seg[] out = new Seg[count];
            final double half = SEG_OVERLAP * 0.5D;
            for (int i = 0; i < count; i++) {
                double s0 = i * segLen - half;
                double s1 = (i + 1) * segLen + half;
                if (i == 0) {
                    s0 = 0.0D;
                }
                if (i == count - 1) {
                    s1 = len;
                }
                out[i] = seg(s0, s1, height, false, 0);
            }
            return out;
        }

        /**
         * 车头 / 车尾外扩段：只加长轴向长度，宽度不变。
         *
         * <p>{@code capA} 是本车 a 端（行进方向前端）的外扩量，{@code capB} 是 b 端（后端）。
         * 头车的 a 端、尾车的 b 端已经由调用方取过 {@code max(endPad, headExtra/tailExtra)}，
         * 所以这里只负责画，不再判断是哪一端。某一端为 0 就只留另一端。
         */
        private Seg[] buildCaps(final double height, final double capA, final double capB) {
            final boolean head = capA > 0.0D;
            final boolean tail = capB > 0.0D;
            if (head && tail) {
                return new Seg[]{
                        seg(-capA, 0.0D, height, true, -1),
                        seg(len, len + capB, height, true, 1)
                };
            }
            if (head) {
                return new Seg[]{seg(-capA, 0.0D, height, true, -1)};
            }
            if (tail) {
                return new Seg[]{seg(len, len + capB, height, true, 1)};
            }
            return new Seg[0];
        }

        /**
         * 在轴向距离区间 {@code [s0, s1]}（从 a 端起算，格）上生成一段。
         * 竖直范围按坡度线性插值：轨面在两端的 y 到两端车顶 y。
         */
        private Seg seg(final double s0, final double s1, final double height,
                        final boolean cap, final int side) {
            final double mid = (s0 + s1) * 0.5D;
            final double halfLen = Math.max(1.0E-4D, (s1 - s0) * 0.5D);
            final double t0 = len < 1.0E-4D ? 0.0D : s0 / len;
            final double t1 = len < 1.0E-4D ? 1.0D : s1 / len;
            final double y0 = floorA + (floorB - floorA) * t0;
            final double y1 = floorA + (floorB - floorA) * t1;
            return new Seg(a.x + ux * mid, a.z + uz * mid, ux, uz, halfLen, halfW,
                    Math.min(y0, y1), Math.max(y0, y1) + height, cap, side);
        }

        /** 玩家在车厢轴上的投影，0 = a 端，1 = b 端。 */
        double along(final double x, final double z) {
            if (len < 1.0E-4D) {
                return 0.5D;
            }
            return ((x - a.x) * ux + (z - a.z) * uz) / len;
        }

        /** 玩家在车厢轴上的投影，单位格（0 = a 端，len = b 端）。 */
        double alongDistance(final double x, final double z) {
            return (x - a.x) * ux + (z - a.z) * uz;
        }

        /**
         * 算出这一 tick 的运动方向（格/tick 单位向量）。
         *
         * <p>优先用<b>上一 tick 的车厢中心差</b>：那是车厢真实移动过的地方，不依赖
         * {@code a/b} 的方向约定，也不依赖 {@code getSpeed()} 的正负。只有拿不到上一 tick 的记录
         * （刚进视距、刚重同步）时才退回「轴向反向 + 速度大小」——MTR 的 {@code a} 端是行进方向的
         * 前端，所以行进方向 = {@code -u}。
         */
        void syncMotion() {
            final CarTrack track = TRACKS.get(key + '#' + index);
            if (track != null && track.tick == ticks - 1L) {
                final double dx = cx - track.x;
                final double dz = cz - track.z;
                final double len = Math.sqrt(dx * dx + dz * dz);
                if (len > 1.0E-4D && len <= CARRY_MAX) {
                    moving = true;
                    moveX = dx / len;
                    moveZ = dz / len;
                    moveSpeed = len;
                    deltaX = dx;
                    deltaZ = dz;
                    return;
                }
            }
            final double speed = speedKmh / KMH_PER_BLOCK_PER_TICK;
            if (speed > 0.02D) {
                moving = true;
                moveX = -ux;
                moveZ = -uz;
                moveSpeed = speed;
                return;
            }
            moving = false;
            moveSpeed = 0.0D;
        }

        /** 玩家到车厢中心线的水平有符号距离（右为正）。 */
        double lateral(final double x, final double z) {
            return (x - a.x) * (-uz) + (z - a.z) * ux;
        }

        /** 该点的车顶高度；不在车厢水平投影内（含一点余量）时返回 NaN。 */
        double roofAt(final double x, final double z, final double height) {
            final double t = along(x, z);
            if (t < -0.02D || t > 1.02D) {
                return Double.NaN;
            }
            if (Math.abs(lateral(x, z)) > halfW + SIDE_INSET + 0.08D) {
                return Double.NaN;
            }
            final double tc = t < 0.0D ? 0.0D : (t > 1.0D ? 1.0D : t);
            return floorA + (floorB - floorA) * tc + height;
        }

        /** 该点的车底板（轨面）高度；不在车厢水平投影内时返回 NaN。 */
        double floorAt(final double x, final double z) {
            final double t = along(x, z);
            if (t < -0.02D || t > 1.02D) {
                return Double.NaN;
            }
            if (Math.abs(lateral(x, z)) > halfW + SIDE_INSET + 0.08D) {
                return Double.NaN;
            }
            final double tc = t < 0.0D ? 0.0D : (t > 1.0D ? 1.0D : t);
            return floorA + (floorB - floorA) * tc;
        }

        /** 高速急弯才把车顶上的人甩下去（其余情况继续载人）。 */
        boolean slidesOff(final HeadlightConfig cfg) {
            return speedKmh > cfg.roofDropMinSpeedKmh && radiusM < cfg.roofDropMaxRadiusMClamped();
        }
    }

    /** 这一 tick 的车厢盒列表（每 tick 每个玩家只建一次，HEAD 与 TAIL 共用）。 */
    private static List<CarHit> cars(final Entity entity, final double height) {
        final double endPad = MtrHeadlightClient.config().trainCollisionEndPadClamped();
        if (cacheEntity == entity && cacheTick == ticks && cacheHeight == height
                && cacheEndPad == endPad) {
            return cacheCars;
        }
        cacheEntity = entity;
        cacheTick = ticks;
        cacheHeight = height;
        cacheEndPad = endPad;
        cacheCars = buildCars(entity, height, endPad);
        return cacheCars;
    }

    private static List<CarHit> buildCars(final Entity entity, final double height,
                                          final double endPad) {
        final MinecraftClient client = MinecraftClient.getInstance();
        final ClientWorld world = client == null ? null : client.world;
        if (world == null) {
            return List.of();
        }
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        final Vec3d eye = entity.getPos();
        final double rangeSq = SEARCH_RANGE * SEARCH_RANGE;
        final List<CarHit> out = new ArrayList<>();

        final List<TrainClient> trains;
        try {
            trains = new ArrayList<>(ClientData.TRAINS);
        } catch (Throwable t) {
            warnOnce(t);
            return out;
        }

        for (final TrainClient train : trains) {
            if (train == null || train.isRemoved || train.path.isEmpty() || train.trainCars <= 0) {
                continue;
            }
            // 只给真正的列车做碰撞：船 / 缆车 / 飞机的位置语义不同，撞上去只会莫名其妙。
            if (train.transportMode != TransportMode.TRAIN) {
                continue;
            }
            final TrainPose pose;
            final boolean reversed;
            try {
                // 与 LightController 完全相同的方向表达式：几何与方向无关，但这样能命中同一个
                // TrainPose 缓存项，一 tick 只采样一次轨道。
                reversed = DepartureState.reversed(train, train.isReversed()) ^ cfg.swapEnds;
                pose = TrainPose.ofShared(train, reversed);
            } catch (Throwable t) {
                warnOnce(t);
                continue;
            }
            final int carCount = pose.cars();
            if (carCount <= 0) {
                continue;
            }
            final String key = DepartureState.keyOf(train);
            final double halfW = halfWidth(train);
            final boolean doorsOpen = doorsOpen(train);
            final double speed = speedKmh(train);
            // 端部延长按侧线覆盖解析：头车的前端 = max(endPad, headExtra)，尾车的后端 =
            // max(endPad, tailExtra)。两个值描述的是「同一处」外扩，所以取 max 不叠加；
            // 中间车的两端、头车的后端、尾车的前端仍然只用 endPad。
            final HeadlightConfig.Effective eff = cfg.effectiveFor(train.sidingId);
            final double noseExtra = Math.max(endPad, eff.collisionHeadExtra);
            final double rearExtra = Math.max(endPad, eff.collisionTailExtra);
            noteEndExtra(noseExtra, rearExtra);
            boolean curveNeeded = speed > cfg.roofDropMinSpeedKmh;

            // 车厢在路径上的位置（与 TrainPose#of 完全同一套换算），曲率采样要用它。
            double total = 0.0D;
            double progress = 0.0D;
            int spacing = 0;
            try {
                total = TrainPose.pathLength(train);
                progress = train.getRailProgress();
                spacing = Math.max(0, train.spacing);
            } catch (Throwable t) {
                // 拿不到路径：曲率一律按直道处理（保守 = 不甩人）。
                curveNeeded = false;
            }

            for (int i = 0; i < carCount; i++) {
                final Vec3d a = pose.carStart(i);
                final Vec3d b = pose.carEnd(i);
                final double cx = (a.x + b.x) * 0.5D;
                final double cz = (a.z + b.z) * 0.5D;
                final double cy = (a.y + b.y) * 0.5D;
                final double dx = cx - eye.x;
                final double dy = cy - eye.y;
                final double dz = cz - eye.z;
                if (dx * dx + dy * dy + dz * dz > rangeSq) {
                    continue;
                }
                double ax = b.x - a.x;
                double az = b.z - a.z;
                double len = Math.sqrt(ax * ax + az * az);
                if (len < 1.0E-3D) {
                    // 竖直方向或退化的车厢：用自身的朝向投影到水平面兜底。
                    final Vec3d fwd = pose.carForward(i);
                    ax = fwd.x;
                    az = fwd.z;
                    len = Math.sqrt(ax * ax + az * az);
                    if (len < 1.0E-4D) {
                        continue;
                    }
                }
                final double ux = ax / len;
                final double uz = az / len;
                final double midProgress = carProgress(progress, spacing, carCount, reversed, i);
                final double radius = curveNeeded && total > 0.0D
                        ? radiusAt(train, midProgress) : Double.POSITIVE_INFINITY;
                out.add(new CarHit(key, i, a, b, ux, uz, len, halfW, height, doorsOpen,
                        speed, radius,
                        i == 0 ? noseExtra : endPad,
                        i == carCount - 1 ? rearExtra : endPad,
                        train.sidingId));
            }
        }
        for (final CarHit car : out) {
            car.syncMotion();
        }
        return out;
    }

    /**
     * 车厢 {@code i} 在路径上的中点位置（格）。与 {@code TrainPose#of} 的
     * {@code progress - (reversed ? cars - i : i) * spacing} 同一套映射。
     */
    private static double carProgress(final double progress, final int spacing, final int cars,
                                      final boolean reversed, final int i) {
        final double here = progress - (reversed ? (double) (cars - i) : (double) i) * spacing;
        final double next = progress
                - (reversed ? (double) (cars - i - 1) : (double) (i + 1)) * spacing;
        return (here + next) * 0.5D;
    }

    /**
     * 轨道路径上 {@code ±CURVE_EPS} 三点估算的曲率半径（格）。拿不到轨道、采样退化、或转角
     * 小于 {@value #STRAIGHT_ANGLE} rad 时返回无穷大（直道 —— 保守方向是不甩人）。
     *
     * <pre>
     * R = (|P(mid) - P(mid-3)| + |P(mid+3) - P(mid)|) / θ
     * </pre>
     */
    private static double radiusAt(final TrainClient train, final double mid) {
        try {
            final double total = TrainPose.pathLength(train);
            if (!(total > 0.0D)) {
                return Double.POSITIVE_INFINITY;
            }
            final Vec3d p0 = TrainPose.samplePath(train, clamp(mid - CURVE_EPS, total));
            final Vec3d p1 = TrainPose.samplePath(train, clamp(mid, total));
            final Vec3d p2 = TrainPose.samplePath(train, clamp(mid + CURVE_EPS, total));
            final double l1 = horizontal(p1.subtract(p0));
            final double l2 = horizontal(p2.subtract(p1));
            if (l1 < 0.5D || l2 < 0.5D) {
                return Double.POSITIVE_INFINITY;
            }
            final double v1x = p1.x - p0.x;
            final double v1z = p1.z - p0.z;
            final double v2x = p2.x - p1.x;
            final double v2z = p2.z - p1.z;
            double cos = (v1x * v2x + v1z * v2z) / (l1 * l2);
            cos = Math.max(-1.0D, Math.min(1.0D, cos));
            final double theta = Math.acos(cos);
            if (!(theta > STRAIGHT_ANGLE)) {
                return Double.POSITIVE_INFINITY;
            }
            return (l1 + l2) / theta;
        } catch (Throwable t) {
            return Double.POSITIVE_INFINITY;
        }
    }

    private static double horizontal(final Vec3d v) {
        return Math.sqrt(v.x * v.x + v.z * v.z);
    }

    private static double clamp(final double v, final double total) {
        if (!(v > 0.0D)) {
            return 0.0D;
        }
        return v > total ? total : v;
    }

    /** MTR 车型宽度的一半（格），减去侧面内缩，下限 {@value #MIN_HALF_WIDTH} 格、上限 4 格。 */
    private static double halfWidth(final TrainClient train) {
        double half;
        try {
            half = ((double) train.width) * 0.5D;
        } catch (Throwable t) {
            return DEFAULT_HALF_WIDTH - SIDE_INSET;
        }
        if (!(half > 0.0D)) {
            return DEFAULT_HALF_WIDTH - SIDE_INSET;
        }
        half = Math.max(MIN_HALF_WIDTH, Math.min(4.0D, half));
        return Math.max(MIN_HALF_WIDTH_ABS, half - SIDE_INSET);
    }

    /**
     * 这列车是否处在「开门放行」状态。<b>列车级，不是车厢级</b>：只要任意一节车厢的门在动/开着，
     * 整列车的侧面就一起放行（见 {@link #pushCar}）。MTR 只把「有没有门在开」同步到客户端
     * （{@code TrainClient} 上的一个 {@code doorOpening} 布尔），并没有左右两侧或单节车厢的区分；
     * 逐节判定会让「只有一节车开了门」时其它车厢继续挡人，站在车门口反而上不去。
     *
     * <p>两道信号取或：{@code isDoorOpening()} 是 MTR 的目标状态，{@code getDoorValue() > 0.02}
     * 是门真的离开关闭位置的证明。后者兜住「目标状态已经翻掉、门还在慢慢关」和「同步只让某一节车
     * 的 flag 为真」两种时序，所以不存在「门明明开着、人却被推出来」的窗口。
     */
    private static boolean doorsOpen(final TrainClient train) {
        try {
            if (train.isDoorOpening()) {
                return true;
            }
        } catch (Throwable t) {
            // 继续看门值：单看一个信号失败不该把另一条也丢掉。
        }
        try {
            return train.getDoorValue() > DOOR_VALUE_EPS;
        } catch (Throwable t) {
            return false;
        }
    }

    /** {@code Train#getSpeed()}（blocks/tick）→ km/h。 */
    private static double speedKmh(final TrainClient train) {
        try {
            return Math.abs(train.getSpeed()) * KMH_PER_BLOCK_PER_TICK;
        } catch (Throwable t) {
            return 0.0D;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 推出
    // ---------------------------------------------------------------------------------------------

    /**
     * 把玩家从这节车厢里推出去（先车顶已排除）。返回 true 表示<b>撞上了</b> —— 这同时是冲量与
     * 碰撞伤害的触发条件。注意它不再等价于「位置真的动了」：被夹在车与墙之间时位置一格都推不动，
     * 但速度推送和伤害照旧生效（旧实现里 `isSpaceEmpty` 一失败就 return false，于是既不推也不疼，
     * 列车直接从人身上穿过去 —— 就是玩家报的「卡一会儿然后列车穿过去」）。
     *
     * <p>开门时整条侧面放行：车身段一个都不测，只剩两个端盖（车头车尾照旧挡人）。
     */
    private static boolean pushCar(final PlayerEntity player, final CarHit car,
                                   final ClientWorld world) {
        boolean hit = false;
        if (!car.doorsOpen) {
            hit = pushBody(player, car, world);
        }
        // 端盖在开门时也保留：站在车头正前方照样被挡住。
        if (pushCaps(player, car, world)) {
            hit = true;
        }
        return hit;
    }

    /**
     * 车身段：以玩家在轴上的投影为中心取 {@code 2 * SEG_WINDOW + 1} 段（「只与最近几段做检测」），
     * 一段命中就收工（同一节车的段共轴，推一次所有段都分离了）。
     */
    private static boolean pushBody(final PlayerEntity player, final CarHit car,
                                    final ClientWorld world) {
        final Seg[] body = car.body;
        if (body.length == 0) {
            return false;
        }
        final double s = car.alongDistance(player.getX(), player.getZ());
        final double segLen = car.len / body.length;
        int centre = segLen < 1.0E-4D ? 0 : (int) Math.floor(s / segLen);
        if (centre < 0) {
            centre = 0;
        } else if (centre >= body.length) {
            centre = body.length - 1;
        }
        final int from = Math.max(0, centre - SEG_WINDOW);
        final int to = Math.min(body.length - 1, centre + SEG_WINDOW);
        for (int i = from; i <= to; i++) {
            if (pushSegment(player, car, body[i], world)) {
                return true;
            }
        }
        return false;
    }

    /** 车头 / 车尾两个端盖。 */
    private static boolean pushCaps(final PlayerEntity player, final CarHit car,
                                    final ClientWorld world) {
        for (final Seg cap : car.caps) {
            if (pushSegment(player, car, cap, world)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单段推出：2D 分离轴判定（段的轴向 u 与法向 n），沿穿透更小的那个方向推出去。
     *
     * <h2>正面 vs 侧面（这是「被挤到一旁」的根因）</h2>
     * 旧实现里车身段<b>只</b>推法向，而法向永远是「垂直于轨道」的。车头正面撞过来时，玩家与车身段
     * 的横向穿透（约 1.5 格）总是小于纵向穿透（整段半长），于是每次都被往侧面挤 —— 玩家看到的就是
     * 「被挤到一旁」，而不是「被推着走」。现在先判一次方位：
     *
     * <pre>
     * ahead = (玩家 − 车厢中心) · 运动方向
     * 正面 = 运动可用 &amp;&amp; ahead &gt; 0 &amp;&amp; penU &lt; penN · 1.5
     * </pre>
     *
     * 正面时沿<b>运动方向</b>推（穿透量取轴向 {@code penU}），每一 tick 都推一次 —— 玩家被车头
     * 顶着往前走 = 「被推着走」；侧面（车从身边掠过）才推法向 + 沿行进方向带一点。
     */
    private static boolean pushSegment(final PlayerEntity player, final CarHit car,
                                       final Seg seg, final ClientWorld world) {
        final Box pb = player.getBoundingBox();
        // 粗筛：段的 AABB 包含段的 OBB，不与它相交就肯定不与 OBB 相交。
        if (pb.maxX <= seg.minX || pb.minX >= seg.maxX
                || pb.maxZ <= seg.minZ || pb.minZ >= seg.maxZ) {
            return false;
        }
        // 竖直分离：整个人在车底板以下（从高架 / 坡道下面走过）或整个人在车顶以上（站在车顶）。
        if (pb.maxY <= seg.yLo + 0.02D || pb.minY >= seg.yHi - 0.02D) {
            return false;
        }
        final double px = (pb.minX + pb.maxX) * 0.5D;
        final double pz = (pb.minZ + pb.maxZ) * 0.5D;
        final double dx = px - seg.cx;
        final double dz = pz - seg.cz;
        final double along = dx * seg.ux + dz * seg.uz;
        final double lat = dx * seg.nx + dz * seg.nz;
        final double hx = (pb.maxX - pb.minX) * 0.5D;
        final double hz = (pb.maxZ - pb.minZ) * 0.5D;
        final double projU = hx * Math.abs(seg.ux) + hz * Math.abs(seg.uz);
        final double projN = hx * Math.abs(seg.nx) + hz * Math.abs(seg.nz);
        final double penN = seg.halfW + projN - Math.abs(lat);
        final double penU = seg.halfLen + projU - Math.abs(along);
        if (penN <= 0.0D || penU <= 0.0D) {
            // 存在分离轴 → 这一 tick 不碰。
            return false;
        }
        // 侧向：往玩家已经在的那一侧推。
        final double sideN = lat >= 0.0D ? 1.0D : -1.0D;
        final boolean frontal = isFrontal(car, seg, px, pz, penU, penN);
        if (frontal) {
            final double fx = car.moving ? car.moveX : seg.ux * seg.side;
            final double fz = car.moving ? car.moveZ : seg.uz * seg.side;
            if (applyPush(player, car, fx, fz, penU, true)) {
                return true;
            }
        }
        if (seg.cap && penU < penN) {
            // 端盖的轴向推出，方向固定向外。
            if (applyPush(player, car, seg.ux * seg.side, seg.uz * seg.side, penU, true)) {
                return true;
            }
        }
        return applyPush(player, car, seg.nx * sideN, seg.nz * sideN, penN, frontal);
    }

    /**
     * 玩家是不是在车的<b>正前方</b>（车正朝着他开过来）：在运动方向的半侧、且轴向穿透没有比横向
     * 穿透大太多。端盖本身按「近端面」算正面。
     */
    private static boolean isFrontal(final CarHit car, final Seg seg, final double px,
                                     final double pz, final double penU, final double penN) {
        if (!car.moving || !(car.moveSpeed > 0.02D)) {
            return seg.cap && penU < penN;
        }
        final double ahead = (px - car.cx) * car.moveX + (pz - car.cz) * car.moveZ;
        if (ahead <= 0.0D) {
            // 车已经开到玩家身后：剩下的是「被车尾扫到」，侧向甩开更合理。
            return false;
        }
        return penU < penN * FRONTAL_PEN_RATIO;
    }

    /**
     * 执行一次撞击：<b>先给速度</b>（无论位置推不推得动），再尽量把位置也分开。
     *
     * <h2>为什么是速度推送</h2>
     * 旧的「只改 pos + {@code isSpaceEmpty} 一失败就放弃」有两个死结：
     * <ol>
     *   <li>站在站台边缘 / 墙边被车挤住时 {@code isSpaceEmpty} 永远为 false → 位置不动 → 玩家卡在
     *       原地，列车从他身上穿过去（"卡一会儿然后列车穿过去"）；</li>
     *   <li>位置修正发生在 {@code Entity#move} 之后，下一 tick 原版 move 又从旧速度开始算 —— 没有
     *       惯性，人被"挪"开而不是"推"开，手感像贴图错位。</li>
     * </ol>
     * 现在每一 tick 写一次 {@code velocity}：正面撞击时水平速度 = <b>车道速度</b>（格/tick），玩家
     * 被车头顶着一起走；侧面撞击时 = 车速 × {@value #SIDE_CARRY}（沿行进方向）+ 远离车体的横向
     * 推力（{@code max(车速×0.55, 0.12)} 格/tick）。位置修正仍保留，能推就推（推不完整就推一部分）。
     *
     * <p>撞飞冲量（{@code CollisionImpact.impulse}）刚写进来的头 8 tick 不做速度推送，否则 1.8
     * 格/tick 的"飞出去"会被"跟着车走"覆盖成 1.1 格/tick。
     *
     * @return true = 撞上了（即使位置一格都推不动）
     */
    private static boolean applyPush(final PlayerEntity player, final CarHit car,
                                     final double dirX, final double dirZ, final double pen,
                                     final boolean frontal) {
        if (!(pen > 0.0D) || pen > 8.0D) {
            return false;
        }
        if (player.isDead() || player.getHealth() <= 0.0F) {
            // 死后不再推：人已经躺下了，再推只会让尸体/镜头继续动。
            return false;
        }
        // 记下撞击方向：撞飞冲量沿它走（水平）。位置推不动也要记 —— 冲量/伤害都靠它。
        pushDirX = dirX;
        pushDirZ = dirZ;
        velocityPush(player, car, dirX, dirZ, frontal);

        double amount = pen + PUSH_EPS;
        if (!fits(player, world(player), dirX, dirZ, amount)) {
            // 推不完整：退到「能推多少推多少」。一格都推不动就只留下速度与伤害。
            amount = maxFree(player, world(player), dirX, dirZ, pen);
        }
        if (amount > 0.0D) {
            player.setPosition(player.getX() + dirX * amount, player.getY(),
                    player.getZ() + dirZ * amount);
            // 速度只削掉「指向车厢」的那个分量：贴着车走不会被粘住。
            final Vec3d v = player.getVelocity();
            final double inward = v.x * dirX + v.z * dirZ;
            if (inward < 0.0D) {
                player.setVelocity(v.x - inward * dirX, v.y, v.z - inward * dirZ);
                player.velocityModified = true;
            }
        }
        return true;
    }

    /** 玩家这一 tick 所在的世界（拿不到就返回 null，{@link #fits} 会当作"推得动"）。 */
    private static ClientWorld world(final PlayerEntity player) {
        return player.getWorld() instanceof ClientWorld w ? w : null;
    }

    /** 按 {@code amount} 推出去之后站得下吗？世界拿不到时按"可以"处理（宁可推，也别卡住）。 */
    private static boolean fits(final PlayerEntity player, final ClientWorld world,
                                final double dirX, final double dirZ, final double amount) {
        if (world == null) {
            return true;
        }
        final Box moved = player.getBoundingBox().offset(dirX * amount, 0.0D, dirZ * amount);
        return world.isSpaceEmpty(player, moved);
    }

    /**
     * 沿 {@code (dirX, dirZ)} 最多能推多远（二分 4 次 ≈ 穿透量的 6% 精度）。
     *
     * <p>这是「被夹住时不要放弃」的那一半：整段推不出去时，推 0.4 格也比原地不动好 —— 下一 tick
     * 车又往前走了，玩家至少被一格一格地蹭出去，而不是被车体整个穿过。
     */
    private static double maxFree(final PlayerEntity player, final ClientWorld world,
                                  final double dirX, final double dirZ, final double pen) {
        if (!fits(player, world, dirX, dirZ, 0.0D)) {
            // 连原地都"站不下"（已经嵌在方块里）：位置修正无从下手，速度推送仍在生效。
            return 0.0D;
        }
        double lo = 0.0D;
        double hi = pen;
        for (int i = 0; i < 4; i++) {
            final double mid = (lo + hi) * 0.5D;
            if (fits(player, world, dirX, dirZ, mid)) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo > 0.05D ? lo : 0.0D;
    }

    /**
     * 速度推送：每 tick 把水平速度写成「跟着车走 + 被挤出去」。
     *
     * <pre>
     * 正面：v水平 = 运动方向 · min(车速, MAX_CARRY)               // 被车头顶着走
     * 侧面：v水平 = 运动方向 · 车速·SIDE_CARRY + 法向 · 挤出速度
     * 挤出速度  = min(max(车速·0.55, MIN_OUT), MAX_CARRY)
     * </pre>
     *
     * <p>竖直分量保持不动：撞飞冲量（含向上的 0.25 / 0.45）不被覆盖，正常走路/下落也不受影响。
     * 车速是 {@code 格/tick}（{@code km/h ÷ 72}），所以 80 km/h 的车 = 1.11 格/tick，玩家被推着
     * 走的速度就是 1.11 格/tick —— 和车一样快，不会被反复"追上—挤开"。
     */
    private static void velocityPush(final PlayerEntity player, final CarHit car,
                                     final double dirX, final double dirZ, final boolean frontal) {
        if (!car.moving || !(car.moveSpeed > 0.02D)) {
            return;
        }
        if (player.isDead() || player.getHealth() <= 0.0F) {
            return;
        }
        if (CollisionImpact.impulseActive(player)) {
            // 还在撞飞后的滞空阶段（锁到落地）：别把 1.8 格/tick 的冲量压回"跟车走"。
            return;
        }
        final double carry = Math.min(car.moveSpeed, MAX_CARRY_VEL);
        final double out = Math.min(Math.max(car.moveSpeed * OUT_VEL_SCALE, MIN_OUT_VEL),
                MAX_CARRY_VEL);
        final double vx;
        final double vz;
        if (frontal) {
            vx = car.moveX * carry;
            vz = car.moveZ * carry;
        } else {
            vx = car.moveX * carry * SIDE_CARRY + dirX * out;
            vz = car.moveZ * carry * SIDE_CARRY + dirZ * out;
        }
        // 只在「确实要改」时写 velocity：每 tick 无条件覆盖（哪怕只差 1e-3）会让客户端每帧都在
        // 改速度 → 位置插值来回抢，画面就是抖的。y 一律不动（撞飞的竖直冲量、正常下落都靠它）。
        final Vec3d cur = player.getVelocity();
        if (Math.abs(cur.x - vx) <= PUSH_VEL_EPS && Math.abs(cur.z - vz) <= PUSH_VEL_EPS) {
            return;
        }
        player.setVelocity(vx, cur.y, vz);
        // 客户端玩家要把速度同步出去：不设这个标志，服务端只看到位置突变（"moved too quickly"）。
        player.velocityModified = true;
        diagVelPushes++;
    }

    // ---------------------------------------------------------------------------------------------
    // 碰撞伤害
    // ---------------------------------------------------------------------------------------------

    /**
     * 这一进程里「服务端在结算」吗？客户端据此决定要不要自己下伤害 / 施加冲量。
     *
     * <pre>
     * 单人（整合服务器）      : MinecraftClient#getServer() != null  -> 服务端权威（ServerCollision）
     * 多人，服务端装了 LTE     : 握手过 mtrlte:hello                  -> 服务端权威
     * 多人，服务端没装 LTE     : 都不是                               -> 客户端自己来（老行为）
     * </pre>
     *
     * <p>注意不能只看 {@link ServerAuthority#authoritative()}：{@code main} 入口在客户端进程里也会
     * 初始化，而「装了 LTE 的服务端」只说明本进程有服务端代码，不代表玩家连的是它。
     */
    private static boolean serverOwnsIt() {
        if (ServerAuthority.remoteAuthoritative()) {
            return true;
        }
        final MinecraftClient client = MinecraftClient.getInstance();
        return client != null && client.getServer() != null;
    }

    /**
     * 撞上之后按档位结算：冲量 + 本 tick 的指数伤害。档位/公式/冷却全在 {@link CollisionImpact}，
     * 生物走的是同一套（见 {@link MobCollision}）。
     *
     * <p><b>服务端权威时这里什么都不做</b>（见 {@link #serverOwnsIt()}）：单人里由同进程的
     * {@code server.ServerCollision} 结算，多人里由装了 LTE 的服务端结算 —— 两边各算一次就是双倍
     * 伤害 / 双倍冲量。此时客户端只保留推出、车顶、受击表现。
     *
     * <p>服务端没装 LTE（纯客户端模式）：退回老行为，对本地玩家实例调用（只有受击表现，血量由
     * 服务器同步回来）。
     */
    private static void impact(final PlayerEntity player, final HeadlightConfig cfg,
                               final double speedKmh, final long sidingId,
                               final double dirX, final double dirZ) {
        if (serverOwnsIt()) {
            return;
        }
        final HeadlightConfig.Effective eff = cfg.effectiveFor(sidingId);
        final float hp = CollisionImpact.tick(player, cfg, eff, speedKmh,
                dirX, dirZ, ticks);
        if (hp > 0.0F) {
            final boolean high = speedKmh >= cfg.trainCollisionHighDamageMaxKmhClamped();
            hurtOnServer(player, hp, high, cfg);
            diagDamage += hp / 2.0D;
        }
    }

    /**
     * 撞击后的持续掉血（窗口内不碰车也扣）：{@code trainCollisionHurtTicks} 默认 120 tick（6 s）。
     * 伤害速率公式不变，只是不再拖 25 秒；撞飞的那些在落地时一次结算完（见 {@code flight}）。
     */
    private static void hurt(final PlayerEntity player, final HeadlightConfig cfg) {
        if (serverOwnsIt()) {
            return;
        }
        final float hp = CollisionImpact.hurtDamage(player, cfg, ticks);
        if (hp > 0.0F) {
            // 致死那一击的车速来自撞击快照，死亡消息才分得清高速/低速两段。
            hurtOnServer(player, hp, CollisionImpact.wasFast(player.getUuid()), cfg);
            diagHurt += hp;
            diagDamage += hp / 2.0D;
        }
    }

    /** &gt;= 飞出档撞过一次之后：飞行/落地里按撞击车速继续累积，每 10 tick 结算一次，直到死亡。 */
    private static void flight(final PlayerEntity player, final HeadlightConfig cfg) {
        if (serverOwnsIt()) {
            return;
        }
        if (!CollisionImpact.launched(player.getUuid())) {
            return;
        }
        final float hp = CollisionImpact.flightDamage(player, cfg, ticks);
        if (hp > 0.0F) {
            hurtOnServer(player, hp, CollisionImpact.wasFast(player.getUuid()), cfg);
            diagDamage += hp / 2.0D;
        }
    }

    /** 把一次伤害打到权威实例上（单人 = 服务端玩家；多人 = 客户端本地实例，只有表现）。 */
    private static void hurtOnServer(final PlayerEntity player, final float hp,
                                     final boolean high, final HeadlightConfig cfg) {
        final MinecraftServer server = MinecraftClient.getInstance().getServer();
        ServerPlayerEntity authoritative = null;
        if (server != null) {
            try {
                authoritative = server.getPlayerManager().getPlayer(player.getUuid());
            } catch (Throwable ignored) {
                authoritative = null;
            }
        }
        if (authoritative != null) {
            final ServerPlayerEntity target = authoritative;
            server.execute(() -> CollisionImpact.applyDamage(target, hp, high, cfg));
            return;
        }
        CollisionImpact.applyDamage(player, hp, high, cfg);
    }

    // ---------------------------------------------------------------------------------------------
    // 车顶
    // ---------------------------------------------------------------------------------------------

    /** 玩家是否站在这节车厢的车顶上；是则吸附 + 载人。高速急弯时返回 false（甩下去）。 */
    private static boolean standOnRoof(final PlayerEntity player, final CarHit car,
                                       final double height, final ClientWorld world) {
        if (car.slidesOff(MtrHeadlightClient.config())) {
            // 高速急弯：不吸附、不载人。玩家留在原地，车从他脚下开走，随后自然下落。
            return false;
        }
        final double x = player.getX();
        final double z = player.getZ();
        final double roof = car.roofAt(x, z, height);
        if (Double.isNaN(roof)) {
            return false;
        }
        final double feet = player.getY();
        if (feet < roof - ROOF_TOL) {
            // 脚底远低于车顶：在车体内部或从高架下面经过，不算站车顶。
            return false;
        }
        if (feet > roof + ROOF_ABOVE) {
            // 还在车顶上方（下一 tick 由 HEAD 截断）。
            return false;
        }
        if (player.getVelocity().y > 0.08D) {
            // 起跳中，不要把人按回车顶。
            return false;
        }
        player.setPosition(x, roof, z);
        player.setOnGround(true);
        player.fallDistance = 0.0F;

        final Vec3d carry = carryFor(car);
        if (carry.lengthSquared() > 1.0E-9D) {
            final Box moved = player.getBoundingBox().offset(carry.x, 0.0D, carry.z);
            if (world.isSpaceEmpty(player, moved)) {
                player.setPosition(player.getX() + carry.x, roof, player.getZ() + carry.z);
            }
        }
        return true;
    }

    /** 上一 tick 到这一 tick 该车厢的水平位移；间隔不是恰好一 tick、或位移过大（重同步）时返回零。 */
    private static Vec3d carryFor(final CarHit car) {
        final String key = car.key + '#' + car.index;
        final CarTrack track = TRACKS.get(key);
        if (track == null || track.tick != ticks - 1L) {
            return Vec3d.ZERO;
        }
        final double dx = car.cx - track.x;
        final double dz = car.cz - track.z;
        if (dx * dx + dz * dz > CARRY_MAX * CARRY_MAX) {
            return Vec3d.ZERO;
        }
        return new Vec3d(dx, 0.0D, dz);
    }

    /** 记下这一 tick 的车厢中心（诊断/载人位移用），并清掉已经不存在的列车的旧记录。 */
    private static void remember(final List<CarHit> cars) {
        if (cars.isEmpty()) {
            if (TRACKS.size() > 64) {
                TRACKS.clear();
            }
            return;
        }
        for (final CarHit car : cars) {
            TRACKS.put(car.key + '#' + car.index, new CarTrack(car.cx, car.cz, ticks));
        }
        if (TRACKS.size() > 512) {
            TRACKS.entrySet().removeIf(e -> e.getValue().tick < ticks - 2L);
        }
    }

    /** 一节车厢上一 tick 的中心。 */
    private static final class CarTrack {
        final double x;
        final double z;
        final long tick;

        CarTrack(final double x, final double z, final long tick) {
            this.x = x;
            this.z = z;
            this.tick = tick;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 道床
    // ---------------------------------------------------------------------------------------------

    /** 站到道床上：轨面 + thickness。只有「已经在上面」或「这一 tick 从上方落下穿过」才生效。 */
    private static boolean trackbed(final PlayerEntity player, final HeadlightConfig cfg,
                                    final ClientWorld world) {
        final double feet = player.getY();
        final double x = player.getX();
        final double z = player.getZ();
        final Trackbed.Sample sample = Trackbed.nearest(world, ticks, x, feet, z);
        if (sample == null) {
            return false;
        }
        final double surface = sample.y + cfg.trackbedThicknessClamped();
        final double vy = player.getVelocity().y;
        // 已经站在上面：脚底就在道床面附近且没有在跳。必须排除"正在起跳"（vy > 0）—— 否则跳起来
        // 的第一 tick 脚底还在容差里，会被立刻按回去并清零速度，跳不上去。
        final boolean resting = vy <= 0.02D && feet >= surface - 0.03D && feet <= surface + 0.10D;
        // 「从上方穿透」：本 tick 起点不低于道床面，终点低于它。站在高架下面（起点远低于道床面）
        // 永远不满足，所以桥下不会被吸上去。
        final boolean crossed = vy < -1.0E-3D && !Double.isNaN(startY)
                && startY >= surface - 0.03D && feet < surface;
        if (!resting && !crossed) {
            return false;
        }
        if (realBlockWins(world, x, feet, z, surface)) {
            return false;
        }
        final Box moved = player.getBoundingBox().offset(0.0D, surface - feet, 0.0D);
        if (!world.isSpaceEmpty(player, moved)) {
            return false;
        }
        player.setPosition(x, surface, z);
        player.setOnGround(true);
        final Vec3d v = player.getVelocity();
        player.setVelocity(v.x, 0.0D, v.z);
        diagTrackbed = surface;
        return true;
    }

    /**
     * 真实方块优先：脚下那块方块的碰撞形状顶面不低道床面时，交回原版。
     *
     * <p>用碰撞形状的顶面而不是「是不是实心方块」：一块矮台阶/半砖顶面只有半格，玩家本来就该被
     * 道床抬到 0.15 格上；而整块地砖的顶面高于道床面时，玩家站在地砖上，道床不该再抬他。
     */
    private static boolean realBlockWins(final ClientWorld world, final double x, final double feet,
                                         final double z, final double surface) {
        final BlockPos below = new BlockPos(MathHelper.floor(x), MathHelper.floor(feet - 0.02D), MathHelper.floor(z));
        try {
            final BlockState state = world.getBlockState(below);
            final VoxelShape shape = state.getCollisionShape(world, below);
            if (!shape.isEmpty() && below.getY() + shape.getMax(Direction.Axis.Y) >= surface - 0.02D) {
                return true;
            }
        } catch (Throwable t) {
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // 杂项
    // ---------------------------------------------------------------------------------------------

    /** 只有本地玩家参与解算；服务端线程、其他实体一律不碰。 */
    private static boolean isLocal(final Entity entity) {
        final MinecraftClient client = MinecraftClient.getInstance();
        return client != null && entity instanceof ClientPlayerEntity
                && entity == client.player && entity.getWorld().isClient();
    }

    private static void resetDiag() {
        diagCars = 0;
        diagSegs = 0;
        diagHits = 0;
        diagDamage = 0.0D;
        diagPushed = 0;
        diagVelPushes = 0;
        diagHurt = 0.0D;
        diagRiding = 0;
        diagTrackbed = Double.NaN;
        diagHead = Double.NaN;
        diagTail = Double.NaN;
    }

    /** 记下这一窗真正用到的两端延长（取峰值：一窗里可能有多条侧线、多个不同的覆盖值）。 */
    private static void noteEndExtra(final double head, final double tail) {
        diagHead = Double.isNaN(diagHead) ? head : Math.max(diagHead, head);
        diagTail = Double.isNaN(diagTail) ? tail : Math.max(diagTail, tail);
    }

    /** 诊断用：{@code 1.0} 这样的一位数；这一窗没有生成车厢时是 {@code -}。 */
    private static String endExtraText(final double value) {
        return Double.isNaN(value) ? "-" : String.format(Locale.ROOT, "%.1f", Double.valueOf(value));
    }

    /** MTR 调用失败只 warn 一次，之后静默（同一 tick 里刷屏没有意义）。 */
    private static void warnOnce(final Throwable t) {
        if (WARNED.compareAndSet(false, true)) {
            MtrHeadlightClient.LOGGER.warn("[lte] collision: MTR data unavailable, "
                    + "train collision disabled until it recovers", t);
        }
    }
}
