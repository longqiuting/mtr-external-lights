package dev.js.mtrheadlight.collision;

import dev.js.mtrheadlight.LteLog;
import mtr.data.Rail;
import mtr.data.RailType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 「这个水平位置落在哪根轨道的道床上，那里的轨面有多高」—— 道床几何的<b>纯计算</b>部分，两端共用。
 *
 * <p>调用方负责给一份「端点 → 轨道」表：
 * <ul>
 *   <li>客户端：{@code mtr.client.ClientData.RAILS}（{@link Trackbed} 传进来）；</li>
 *   <li>服务端：{@code mtr.data.RailwayData#getRailsMap()}（{@code server.MtrServerData} 传进来）。</li>
 * </ul>
 * 两张表的结构相同（{@code Map<BlockPos, Map<BlockPos, Rail>>}），所以几何、缓存、采样只有这一份。
 * 本类<b>不引用</b> {@code ClientData} / {@code ClientWorld} / 任何客户端类，否则服务端就用不了它。
 *
 * <h2>模型：横向列 + 弧长插值（照 RTE 的轨道段碰撞）</h2>
 * 旧模型是「最近点 + y 阈值」：求到中心线的最近采样点，再拿那个点的 y 去和脚底比。它在坡道上必然出错，
 * 因为「最近采样点」的 y 是<b>离散</b>的 —— 人往前走一步，轨面已经抬高，采样点还是上一个。RTE 的做法是
 * 把轨道当成一串<b>沿弧长的列</b>，这里照抄它的三条：
 *
 * <ol>
 *   <li><b>横向列</b>：把查询点投影到轨道折线上，得到<b>有符号横向偏移</b>
 *       {@code offset = (q - p) · N}，{@code N = T × UP}（轨道的法向，水平面内
 *       {@code N = (-tz, tx)}）。{@code |offset| ≤ }{@link #HALF_WIDTH} 才算踩在道床上。
 *       这是<b>法向</b>判定，不是包围盒：弯道内侧和外侧用的是同一条法线，所以两边都不会漏。</li>
 *   <li><b>弧长插值</b>：投影点给出沿轨道的弧长参数 {@code s}，轨面高度用 {@code Rail} 自己的
 *       高程曲线<b>解析</b>算出来（{@link #railY}，与 {@code Rail#getPosition} 的 y 完全同式）。
 *       所以坡道上是「脚下这一点的高度」，不是「最近采样点的高度」，坡度自然跟随。</li>
 *   <li><b>不拿 y 做阈值</b>：纵向只在调用方判「脚底落在可站立窗口内」，本类只负责给出轨面 y。
 *       因此 <b>onGround / vy / 包围盒</b> 都不参与几何命中，坡道、弯道、起坡点一视同仁。</li>
 * </ol>
 *
 * <h2>候选集：按「查询点到折线的真实距离」构造（不是包围盒）</h2>
 * 表只按端点索引。实体站在一条 20 格长的弯道中段时，两个端点都在 10 格以外，按端点取格子会漏掉
 * 这条轨道。所以这里维护一份「查询点附近」的轨道缓存，<b>准入判据是查询点到这条轨道折线的真实
 * 距离</b>（{@link #CANDIDATE_REACH}），不是「查询点是否落在某个包围盒里」：
 *
 * <ul>
 *   <li><b>第一级（快、且是精确下界）</b>：包围盒距离 {@code bboxDistance}。点到折线的距离
 *       <b>永远不会小于</b> 点到折线包围盒的距离，所以「包围盒距离 &gt; reach → 丢掉」不会漏掉任何
 *       真在附近的轨道。它只负责省掉远处的 {@code getPosition} 与逐段投影。</li>
 *   <li><b>第二级（准）</b>：逐段投影出来的真实距离，超过 {@link #CANDIDATE_REACH} 才丢。</li>
 * </ul>
 *
 * <p>旧实现两级都在用包围盒，而<b>「进缓存」用的是锚点、「能不能命中」用的是查询点</b>：接缝处
 * 那一小段轨道只要包围盒没覆盖到查询点，就会被判在候选之外 —— 表现就是「弯道能站、紧接的直道
 * 整段没有碰撞」。换成真实距离之后，「贴着但不在框内」的轨道照样进候选。
 *
 * <h2>接缝：两条相邻轨道都进候选，取 dist 最小的那条</h2>
 * MTR 相邻节点之间一段一条 {@code Rail}，所以「弯道接直道」在数据里通常是<b>两条</b> {@code Rail}
 * （共享一个端点），也可能是<b>同一条</b> {@code Rail} 的两段（{@code len1}/{@code len2}，
 * 实测 segment1 切向 = 起点朝向、segment2 切向 = 终点朝向，接缝处位置连续）。两种形态这里都按
 * 「每条折线各自投影、全部候选里取最小」处理，不需要额外的配对逻辑。
 *
 * <p>接缝处会有一处细节：弯道末端那个投影会被 clamp 在折线端点上（{@code f = 1}），它的 offset
 * 可能刚好比直道中段的投影小千分之几。只按 dist 取最小的话，选中的会是「已经走到头的弯道」，
 * 而它的法向在接缝上和直道差了半个弧度。所以这里加一条<b>接缝选边</b>规则：两条候选的距离差在
 * {@link #SEAM_TIE} 以内时，优先<b>投影落在轨道内部</b>的那条（不是 clamp 在端点上）。
 *
 * <p>实例持有缓存：客户端与服务端各用一个实例（各自的轨道表、各自的缓存中心），互不干扰 ——
 * 单人里两边会同时调用，共用一个缓存槽会互相把对方的缓存冲掉。
 */
public final class RailBed {

    /**
     * 可站立半宽（格）：轨道中心线两侧各这么宽都能站 / 都能接住掉落物。
     *
     * <p>MTR 只暴露 {@code Train#width}，<b>不暴露轨道模型的宽度</b>（{@code Rail#railType} 是
     * {@code WOODEN/STONE/PLATFORM/...} 这类枚举，没有尺寸字段），所以这里按视觉宽度兜底：MTR 的
     * 轨道床约 <b>2.6 格宽</b>，半宽 1.3。旧值是 1.0（2.0 格宽），掉在枕木边缘、离中心线 1.0–1.3
     * 格的物品会被判成「轨外」而漏接。
     */
    public static final double HALF_WIDTH = 1.3D;

    /** 查询点周围多远的轨道参与判定（格）。 */
    public static final double SEARCH_RADIUS = 8.0D;

    /**
     * <b>候选准入半径</b>（格）：查询点到轨道折线的<b>真实距离</b>不超过这个值，这条轨道才进候选。
     *
     * <p>比 {@link #HALF_WIDTH} 多 1.2：一是接缝处两条轨道要都留在候选里（它们的距离可以差到半格），
     * 二是诊断行要能报出 {@code dist=1.4} 这种「差一点点」的读数，而不是直接 {@code -}。
     * 这个值只影响「要不要算这一条」，不影响命中结果本身（命中仍然是 {@link #HALF_WIDTH}）。
     */
    public static final double CANDIDATE_REACH = HALF_WIDTH + 1.2D;

    /**
     * 接缝等距容差（格）：两条候选到查询点的距离差在这个范围内算「一样近」，此时优先
     * <b>投影落在轨道内部</b>的那条 —— 否则接缝处会永远选「已经走到端点」的那一条。
     */
    private static final double SEAM_TIE = 0.02D;

    /** 判定「投影落在折线内部」而不是 clamp 在端点上时，离两端至少这么远（格）。 */
    private static final double END_EPS = 0.02D;

    /**
     * 高度粗筛余量（格）：坡道上同一条轨道的 y 随参数变化，判定用采样得到的 [minY, maxY] 区间。
     * 取值要盖住调用方的纵向窗口（上坡抬升最多 {@code STEP_UP}，从上方落下的扫掠最多 2.0），
     * 这里都不接近，一个宽余量只影响「要不要算这一条」，不影响命中结果本身。
     */
    private static final double HEIGHT_SLACK = 4.0D;

    /** 折线采样步长上限（格）与最少细分数：{@code step = min(0.5, len/64)}。 */
    private static final double SAMPLE_MAX_STEP = 0.5D;
    private static final int SAMPLE_DIVISIONS = 64;

    /** 单条轨道采样点数上限（防病态数据把缓存构建拖死）。 */
    private static final int MAX_SAMPLES = 256;

    /** 几何复用表的大小上限：超了整表清空（键是弱引用，正常地图不会到）。 */
    private static final int GEOMETRY_MAX = 8192;

    /** 缓存重建条件：缓存超过这么多 tick（客户端锚在查询点，所以「走动」也算换锚）。 */
    private static final int REBUILD_TICKS = 20;

    /**
     * 按区块格子锚定时的外扩量（格）：查询点最多离格心 11.3 格（16×16 的半对角线），
     * 再加一点余量保证格子边角上的轨道不会漏。
     */
    private static final double CELL_MARGIN = 12.0D;

    /** {@code where} 诊断最多列几条候选（按真实距离从近到远）。 */
    private static final int WHERE_MAX = 8;

    /**
     * 一条轨道的预计算量：沿弧长等距的<b>折线</b>（x / z / 弧长 s）、高程曲线的两端、包围盒。
     *
     * <p>折线只用来做水平投影（{@code step ≤ 0.5} 保证弦误差毫米级）；<b>y 不插值折线</b>，
     * 而是用 {@link #railY} 按弧长解析算 —— 折线插值在坡道中段会低于真实轨面（MTR 的高程是
     * 两端平、中间陡的二次曲线，线性插值总是偏低）。
     */
    private static final class Entry {
        final Rail rail;
        final double length;
        /** 折线顶点（格），按弧长升序。 */
        final double[] xs;
        final double[] zs;
        /** 每个顶点对应的弧长参数（格），与 {@code Rail#getPosition} 的参数同刻度。 */
        final double[] ss;
        /** 采样折线的真实包围盒（格），不是端点包围盒 —— 这是弯道不漏的关键。 */
        final double minX, minZ, maxX, maxZ;
        final double minY, maxY;
        /** 高程曲线：{@code getPosition(0).y} / {@code getPosition(length).y}。 */
        final double yStart, yEnd;
        /** 缆车风格（两头各 0.5 格平台 + 中间线性 + 下垂），高程公式与普通轨道不同。 */
        final boolean cable;
        /** 这条轨道的折线是不是直线（诊断用的 {@code straight=}；几何上只影响读数，不影响命中）。 */
        final boolean straight;

        Entry(final Rail rail, final double length, final double[] xs, final double[] zs,
              final double[] ss, final double minX, final double minZ, final double maxX,
              final double maxZ, final double minY, final double maxY, final double yStart,
              final double yEnd, final boolean cable, final boolean straight) {
            this.rail = rail;
            this.length = length;
            this.xs = xs;
            this.zs = zs;
            this.ss = ss;
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            this.minY = minY;
            this.maxY = maxY;
            this.yStart = yStart;
            this.yEnd = yEnd;
            this.cable = cable;
            this.straight = straight;
        }

        /**
         * 点 {@code (qx, qz)} 到<b>包围盒</b>的水平距离（格）。
         *
         * <p>它同时是「点到折线真实距离」的<b>下界</b>（折线整个在包围盒里），所以拿它做剪枝是
         * 精确的：{@code bboxDistance > reach} ⇒ 真实距离也 {@code > reach}，一定不可能命中。
         * 反过来<b>不成立</b> —— 这正是旧实现用「包围盒 + 余量」当准入时漏掉「贴着但不在框内」
         * 那些轨道的原因。
         */
        double bboxDistance(final double qx, final double qz) {
            final double dx = Math.max(0.0D, Math.max(minX - qx, qx - maxX));
            final double dz = Math.max(0.0D, Math.max(minZ - qz, qz - maxZ));
            return Math.sqrt(dx * dx + dz * dz);
        }
    }

    /** 缓存里的一条候选：轨道几何 + 它到<b>缓存锚点</b>的真实距离（排序 / 诊断用）。 */
    private static final class Cand {
        final Entry entry;
        /** 到锚点的真实折线距离（格）。 */
        final double anchorDistance;

        Cand(final Entry entry, final double anchorDistance) {
            this.entry = entry;
            this.anchorDistance = anchorDistance;
        }
    }

    /**
     * 一个「横向列」的查询结果。
     *
     * <p>{@link #offset} 是<b>有符号</b>横向偏移（沿轨道法向 {@code N = T × UP}），
     * {@link #distance} 是它的绝对值（旧字段，服务端与日志还在用）；{@link #along} 是该列对应的
     * 弧长参数，{@link #y} 就是 {@code railY(along)} —— 坡道上「脚下这一点」的轨面高度。
     */
    public static final class Sample {
        /** 轨道面高度（= {@code Rail#getPosition} 的 y，与列车底板同一水平面）。 */
        public final double y;
        /** 查询点到轨道中心线的水平距离（格），= {@code |offset|}。 */
        public final double distance;
        /** 有符号横向偏移（格）：正值在法向 {@code T × UP} 一侧，负值在另一侧。 */
        public final double offset;
        /** 该列在轨道上的弧长参数（格，0 – {@link #length}）。 */
        public final double along;
        /** 这条轨道的全长（格）。 */
        public final double length;

        Sample(final double y, final double distance, final double offset, final double along,
               final double length) {
            this.y = y;
            this.distance = distance;
            this.offset = offset;
            this.along = along;
            this.length = length;
        }

        /** 投影落在折线<b>内部</b>（不是被 clamp 在两端）。接缝选边要用。 */
        boolean interior() {
            return along > END_EPS && along < length - END_EPS;
        }
    }

    /**
     * 纵向窗口（格）：脚底可以比轨面低这么多仍然被抬上去 —— 上坡时轨面在脚底之上，这一格就是
     * 「一步能跨上去」的高度。默认 1.0（一格台阶），可配置（{@code trackbedStepUp}）。
     */
    public static final double DEFAULT_STEP_UP = 1.0D;

    /** 齐平容差（格）：脚底与轨面差在这个范围内就算「正踩在轨面上」。 */
    public static final double LEVEL_TOL = 0.03D;

    /** 「贴着轨面往下走」的吸附带（格）：下坡时脚底落在轨面上方这么多以内就贴回去。 */
    public static final double STICK = 0.6D;

    /**
     * 纵向窗口的上限（格）：脚底最多在轨面上方这么多才参与判定。原版跳跃高度是 1.25 格，所以
     * 「上方 2.0 以内一律吸附」不能无条件用（跳到最高点会被拽下来，跳不起来）；真正把上方这一半
     * 兑现的是 {@link #verdict} 里的「扫掠穿过」与 {@link #STICK} 两档。
     */
    public static final double CEIL = 2.0D;

    /**
     * <b>安全网水平半径</b>（格）：救回<b>不</b>要求 {@code |offset| ≤ }{@link #HALF_WIDTH}，横向到
     * 中心线这么远都算「人在道床正上方」。
     *
     * <p>比 {@link #HALF_WIDTH}（1.3 = 道床视觉半宽）宽 1.2 格，是为了兜住「横向列算出来的偏移本身
     * 偏了」这种数据侧误差（接缝两侧两条轨道的法向差半个弧度、端点 clamp、缓存里少一条轨道）。
     * 它只决定「接不接」；接住之后站得住还是靠 {@link #HALF_WIDTH} —— 见
     * {@link #RESCUE_GRACE} 为什么不会让人浮在轨道旁边。
     */
    public static final double RESCUE_RADIUS = 2.5D;

    /**
     * <b>安全网下落窗口</b>（格）：轨面必须在脚底<b>下方</b> {@code 0 – 3} 格之内才接。
     *
     * <p>下界 0 = 齐平（轨面不许在脚底之上 —— 否则站在高架下面会被拽上轨道）；
     * 上界 3 = 从三格高处摔向道床也算「踩空」，再高就交给「扫掠穿过」那一档。
     */
    public static final double RESCUE_DROP = 3.0D;

    /**
     * 宽幅救回的连续上限（tick）：横向落在 {@code (HALF_WIDTH, RESCUE_RADIUS]} 的救回最多连做这么多
     * tick，之后放手让人真的掉下去。
     *
     * <p>没有这一条的话，站在离中心线 1.4 格的<b>空气</b>里会形成「每 tick 掉 8 厘米 → 被救回 → 再掉」
     * 的死循环：人看起来浮在轨道旁边的空中。数据侧误差只可能持续几 tick，1 秒（20 tick）足够；
     * 超过就说明这个人真的在道床外面。
     */
    public static final int RESCUE_GRACE = 20;

    /**
     * 一次接触判定的结论（取证用）。几何命中之后，剩下的就是「脚底与轨面的关系」这一件事。
     */
    public enum Verdict {
        /** 没要求判定（关闭 / 不在候选里）。 */
        NONE,
        /** 吸到轨面上。 */
        SNAP,
        /** 横向列之外：缓存里有轨道，但横向偏移超过半宽。 */
        LATERAL,
        /** 纵向窗口外：横向在道床上，脚底与轨面的高差不在可站立窗口里。 */
        DY,
        /** 正在起跳：脚底在轨面附近且竖直速度向上，这一 tick 让开。 */
        JUMP,
        /** 缓存里没有近处的轨道（真掉下去的第一嫌疑人）。 */
        NO_HIT,
        /** 脚下真实方块的顶面不低于轨面：交回原版。 */
        BLOCK,
        /** 抬起来的落点被方块占了。 */
        SPACE,
        /**
         * <b>下落安全网</b>接住了：{@link #verdict} 说「不在窗口里」，但横向在 {@link #RESCUE_RADIUS}
         * 内、轨面在脚底下方 {@code 0 – }{@link #RESCUE_DROP} 格、而且正在往下掉 —— 无条件按上去。
         */
        RESCUE;

        /** 这一档是不是「真的把人按到轨面上」（{@link #SNAP} 或安全网接住）。 */
        public boolean snaps() {
            return this == SNAP || this == RESCUE;
        }

        /** 诊断行里用的短名：{@code dy}/{@code lat}/{@code nohit}/{@code jump}/{@code block}/{@code space}/ {@code rescue}/{@code snap}。 */
        public String shortName() {
            switch (this) {
                case SNAP:
                    return "snap";
                case LATERAL:
                    return "lat";
                case DY:
                    return "dy";
                case JUMP:
                    return "jump";
                case NO_HIT:
                    return "nohit";
                case BLOCK:
                    return "block";
                case SPACE:
                    return "space";
                case RESCUE:
                    return "rescue";
                default:
                    return "-";
            }
        }
    }

    /**
     * <b>纵向判定</b>（纯函数，单元测试直接验它）：给定脚底与轨面的高差 {@code dy}、竖直速度
     * {@code vy}、这一 tick 起点的高差 {@code startDy}，决定要不要把人按到轨面上。
     *
     * <p><b>不看 onGround</b>：坡道上行时原版常常在「贴地走」和「一 tick 腾空」之间来回，onGround
     * 一旦为假就把人漏掉，这正是老实现「上坡走两步就掉下去」的直接原因。这里只用三个量：
     * <ul>
     *   <li>{@code dy ∈ [-stepUp, +0.03]} —— 正踩在 / 略低于轨面（上坡、跨台阶）→ 抬上去；</li>
     *   <li>起点在上、终点在下（扫掠穿过）→ 多快的下落都接住，不受 {@link #CEIL} 限制；</li>
     *   <li>轨面上方 0–0.6 格且竖直速度向下 → 贴着走（下坡不用每一步都自由落体）。</li>
     * </ul>
     * 「正在起跳」（{@code vy > 0.02} 且脚底不在轨面之下）一律让开，否则跳不起来。
     */
    public static Verdict verdict(final double dy, final double vy, final double startDy,
                                  final double stepUp) {
        // 起跳：脚底已在轨面附近还在往上走 → 让开这一 tick（这一步与 onGround 无关）。
        if (vy > 0.02D && dy > -LEVEL_TOL) {
            return Verdict.JUMP;
        }
        // 从上方穿过轨面：起点在轨面之上、终点在轨面之下。高速下落一 tick 跨过的深度可以远超窗口。
        if (vy < -1.0E-3D && !Double.isNaN(startDy) && startDy >= -LEVEL_TOL
                && dy < -LEVEL_TOL) {
            return Verdict.SNAP;
        }
        // 纵向窗口（下界 STEP_UP，上界 CEIL）之外：横向踩着轨道也不该被拉上去/拉下来。
        if (dy < -stepUp || dy > CEIL) {
            return Verdict.DY;
        }
        // 正踩在轨面上，或脚底比轨面低不超过一级台阶 —— 上坡就是这个分支，slope 越大 dy 越负。
        if (dy <= LEVEL_TOL) {
            return Verdict.SNAP;
        }
        // 轨面之上：只有「往下贴」才收；跳起来的人在上方腾空，等扫掠那一步再接。
        if (vy <= 0.02D && dy <= STICK) {
            return Verdict.SNAP;
        }
        return Verdict.DY;
    }

    /**
     * <b>下落安全网</b>（纯函数，单元测试与 {@code tools/trackbed-bridge-sim.js} 直接验它）：
     * {@code verdict} 说「不在窗口里」时，还有一条兜底 —— 这一 tick 人从道床面上方掉下来、<b>真的穿过
     * 了</b>轨面，那就接住。
     *
     * <p>它<b>不看</b> {@code verdict} 的纵向窗口（{@code -stepUp} 下界 / {@code CEIL} 上界 /
     * {@link #STICK} 带），也<b>不看</b> {@link #HALF_WIDTH}（横向由调用方按 {@link #RESCUE_RADIUS}
     * 判）—— 这正是它的意义：踩空的那一 tick，无论 {@code verdict} 给的是 {@code lat}（横向差一点）
     * 还是 {@code dy}（高度差一点），人都不会往下掉。
     *
     * <h2>四条条件各自挡掉什么</h2>
     * <ol>
     *   <li>{@code vy < 0} —— 只在<b>下落</b>时接。起跳 / 上升 / 停在半空一律让开，所以跳跃的上升段
     *       完全不受影响；</li>
     *   <li>{@code startDy >= 0} —— <b>这一 tick 的起点</b>不能已经在轨面之下。这一条就是「不会被
     *       吸到假想轨道上」的下界：站在高架<b>下面</b>、走在比道床低一格的地面上，起点都是负的，
     *       怎么掉都不会被拽上来；</li>
     *   <li>{@code dy < 0} —— 这一 tick <b>真的穿过了</b>轨面。它同时保证接住的位置就是轨面本身
     *       （不会在半空中提前半格把人按住），所以跳跃的下落段和原版落地一模一样；</li>
     *   <li>{@code startDy <= RESCUE_DROP}（慢速下落时）—— 就是「轨面在脚底下方 0–3 格」这条窗口：
     *       慢慢往下走的时候，不从三格以外的上方硬拽。快速下落（{@code vy <= -3}，一 tick 就跨过
     *       三格以上）不受这条限制，否则从高处砸向道床的高速下落反而接不住。</li>
     * </ol>
     *
     * @param startDy 这一 tick <b>起点</b>的「脚底 − 轨面」（格）：{@code startY - surface}
     * @param dy      这一 tick <b>终点</b>的「脚底 − 轨面」（格）
     * @param vy      竖直速度（格 / tick），原版约定往上为正
     */
    public static boolean rescues(final double startDy, final double dy, final double vy) {
        if (!(vy < 0.0D)) {
            return false;
        }
        if (!(startDy >= 0.0D) || !(dy < 0.0D)) {
            return false;
        }
        return startDy <= RESCUE_DROP || vy <= -RESCUE_DROP;
    }

    private List<Cand> cache = List.of();
    private Object cacheMap;
    private double cacheX = Double.NaN;
    private double cacheZ = Double.NaN;
    private long cacheTick;
    private final AtomicBoolean warned = new AtomicBoolean();

    /** 上一次建缓存扫过多少条轨道 / 有多少条被单条异常跳过（诊断）。 */
    private int lastScanned;
    private int lastSkipped;

    /**
     * 每条轨道的几何（折线包围盒 + 高程端点）与查询点无关，所以只算一次、跨缓存重建复用。
     * 弱引用键：MTR 重载轨道表 / 换世界之后，旧 {@code Rail} 对象自己会被回收，不用手工清。
     */
    private final Map<Rail, Entry> geometry = new WeakHashMap<>();

    /** 上一次查询的取证数据（{@code /mtr_lte trackbed status} 读它）。 */
    private boolean lastHit;
    private double lastY = Double.NaN;
    private double lastDistance = Double.NaN;
    private double lastOffset = Double.NaN;
    private double lastAlong = Double.NaN;

    /** 丢掉缓存（换维度 / 换世界）。 */
    public void reset() {
        cache = List.of();
        cacheMap = null;
        cacheX = Double.NaN;
        cacheZ = Double.NaN;
        cacheTick = 0L;
        lastScanned = 0;
        lastSkipped = 0;
        geometry.clear();
        clearLast();
    }

    /** 新世界里的第一条 warn 仍然值得看。 */
    public void clearWarned() {
        warned.set(false);
    }

    /** 缓存里有多少条候选轨道（诊断）。 */
    public int cached() {
        return cache.size();
    }

    /** 上一次建缓存扫过多少条轨道（诊断：{@code rails=} 后面的 {@code scanned=}）。 */
    public int scanned() {
        return lastScanned;
    }

    /** 上一次建缓存有多少条轨道因为是单条异常被跳过（诊断：这个数不为 0 就说明数据里有坏轨道）。 */
    public int skipped() {
        return lastSkipped;
    }

    /** 上一次查询有没有在 {@link #HALF_WIDTH} 内找到轨面（诊断）。 */
    public boolean lastHit() {
        return lastHit;
    }

    /** 上一次查询命中的轨面 y；没命中时是 {@code NaN}（诊断）。 */
    public double lastY() {
        return lastY;
    }

    /**
     * 上一次查询到<b>最近那条候选轨道</b>的水平距离，即便它超过了 {@link #HALF_WIDTH} 也照报
     * （诊断）：{@code -} 表示缓存里根本没有足够近的候选（缓存漏 / 人真的不在轨道上），
     * {@code 1.4} 这种则表示「轨道找到了，只是差一点」。
     */
    public double lastDistance() {
        return lastDistance;
    }

    /** 上一次查询的<b>有符号</b>横向偏移（诊断）：正负号只表示在法向哪一侧。 */
    public double lastOffset() {
        return lastOffset;
    }

    /** 上一次查询命中的弧长参数（诊断）：上坡时它随人往前走，y 也跟着走。 */
    public double lastAlong() {
        return lastAlong;
    }

    /**
     * 查询点脚下最近的一条轨道；找不到（或水平距离超过 {@link #HALF_WIDTH}）时返回 {@code null}。
     *
     * @param railsMap {@code Map<BlockPos, Map<BlockPos, Rail>>}（两端各自的来源，可为 {@code null}）
     */
    public Sample nearest(final Object railsMap, final long tick, final double x,
                          final double y, final double z) {
        // 锚点按 2 格量化：玩家在一个格子里小步走动不该每 tick 重建整张表（旧行为是
        // 「移动超过 2 格才重建」，量化到 2 格得到同样的重建频率）。
        return nearest(railsMap, tick, x, y, z,
                Math.floor(x / 2.0D) * 2.0D, Math.floor(z / 2.0D) * 2.0D,
                SEARCH_RADIUS + 2.0D);
    }

    /**
     * 和 {@link #nearest} 相同，但缓存锚在<b>区块格子中心</b>而不是查询点。
     *
     * <p>服务端要对「玩家 64 格内的所有实体」查询：如果每个实体都把缓存挪到自己脚下，200 个掉落物
     * 就是 200 次整表重建。按 16 格格子锚定之后，同一格里的一堆实体共用一份缓存，重建频率降到
     * 「每格每 20 tick 一次」。
     *
     * @param cellX 区块格 x（{@code Math.floor(x / 16)}）
     * @param cellZ 区块格 z
     */
    public Sample nearestCell(final Object railsMap, final long tick, final int cellX,
                              final int cellZ, final double x, final double y, final double z) {
        return nearest(railsMap, tick, x, y, z,
                cellX * 16.0D + 8.0D, cellZ * 16.0D + 8.0D, CELL_MARGIN);
    }

    /** 锚点 + 查询点可能离锚点的最大距离：候选缓存按这个外扩量建。 */
    private Sample nearest(final Object railsMap, final long tick, final double x, final double y,
                           final double z, final double anchorX, final double anchorZ,
                           final double margin) {
        final Sample best = probe(railsMap, tick, x, y, z, anchorX, anchorZ, margin);
        return lastHit ? best : null;
    }

    /**
     * 和 {@link #nearest} 同一次查询，但<b>不过滤半宽</b>：横向偏移超了也把最近那条轨道返回来。
     *
     * <p>诊断需要它：{@code drop=lat}（「轨道找到了、只是横向没踩上」）和 {@code drop=nohit}
     * （「缓存里根本没有近处的轨道」）的修法完全不同，如果这里也过滤掉，两种就会混成一种。
     */
    public Sample probe(final Object railsMap, final long tick, final double x, final double y,
                        final double z) {
        return probe(railsMap, tick, x, y, z,
                Math.floor(x / 2.0D) * 2.0D, Math.floor(z / 2.0D) * 2.0D,
                SEARCH_RADIUS + 2.0D);
    }

    private Sample probe(final Object railsMap, final long tick, final double x, final double y,
                         final double z, final double anchorX, final double anchorZ,
                         final double margin) {
        if (railsMap == null) {
            clearLast();
            return null;
        }
        refresh(railsMap, tick, anchorX, anchorZ, margin);
        Sample best = null;
        // 接缝选边：距离相同时，优先「投影落在轨道内部」的那条。
        boolean bestInterior = false;
        for (final Cand cand : cache) {
            final Entry entry = cand.entry;
            // 高度先过一遍：坡道上同一条轨道两端高程不同，所以用 [minY, maxY] 的区间判定。
            if (y < entry.minY - HEIGHT_SLACK || y > entry.maxY + HEIGHT_SLACK) {
                continue;
            }
            // 包围盒距离是真实折线距离的下界：超过准入半径就一定不可能命中，直接跳过（精确剪枝）。
            if (entry.bboxDistance(x, z) > CANDIDATE_REACH) {
                continue;
            }
            final Sample sample = column(entry, x, z);
            if (sample == null || sample.distance > CANDIDATE_REACH) {
                continue;
            }
            if (best == null
                    || sample.distance < best.distance - SEAM_TIE
                    || (sample.distance <= best.distance + SEAM_TIE
                            && sample.interior() && !bestInterior)) {
                best = sample;
                bestInterior = sample.interior();
            }
        }
        // 取证值先落下来（可能超出 HALF_WIDTH，诊断行需要「差一点」这种读数），再决定是否命中。
        lastY = best == null ? Double.NaN : best.y;
        lastDistance = best == null ? Double.NaN : best.distance;
        lastOffset = best == null ? Double.NaN : best.offset;
        lastAlong = best == null ? Double.NaN : best.along;
        lastHit = best != null && best.distance <= HALF_WIDTH;
        return best;
    }

    private void clearLast() {
        lastHit = false;
        lastY = Double.NaN;
        lastDistance = Double.NaN;
        lastOffset = Double.NaN;
        lastAlong = Double.NaN;
    }

    /**
     * <b>横向列判定</b>：把 {@code (qx, qz)} 投影到这条轨道的折线上，返回最近的列。
     *
     * <p>逐段投影（不是逐采样点比距离）：段的两端是折线顶点，段方向就是该处的切向 {@code T}，
     * 法向 {@code N = (-Tz, Tx)}。投影参数 {@code f ∈ [0,1]} 给出弧长参数
     * {@code s = s_i + f·(s_{i+1} - s_i)}，横向偏移 {@code offset = (q - p) · N}。
     * 这样<b>每一段都有一个连续解</b>，不会出现「最近采样点跳了一格、y 突然变了半格」。
     */
    private static Sample column(final Entry entry, final double qx, final double qz) {
        final int last = entry.xs.length - 1;
        if (last < 1) {
            return null;
        }
        double bestSq = Double.MAX_VALUE;
        double bestOffset = 0.0D;
        double bestAlong = 0.0D;
        for (int i = 0; i < last; i++) {
            final double x1 = entry.xs[i];
            final double z1 = entry.zs[i];
            final double ex = entry.xs[i + 1] - x1;
            final double ez = entry.zs[i + 1] - z1;
            final double len2 = ex * ex + ez * ez;
            final double dx = qx - x1;
            final double dz = qz - z1;
            // 段内参数：水平投影（折线段是直线，水平投影与三维投影的分数相同）。
            double f = 0.0D;
            if (len2 > 1.0E-12D) {
                f = clamp((dx * ex + dz * ez) / len2, 0.0D, 1.0D);
            }
            final double px = x1 + ex * f;
            final double pz = z1 + ez * f;
            final double ox = qx - px;
            final double oz = qz - pz;
            final double sq = ox * ox + oz * oz;
            if (sq >= bestSq) {
                continue;
            }
            bestSq = sq;
            bestAlong = entry.ss[i] + (entry.ss[i + 1] - entry.ss[i]) * f;
            // 法向 N = T × UP，水平面内就是 (-ez, ex) 归一化（右手系：切向 × 上 = 左法向）。
            if (len2 > 1.0E-12D) {
                final double inv = 1.0D / Math.sqrt(len2);
                bestOffset = ox * (-ez * inv) + oz * (ex * inv);
            } else {
                // 退化段（水平长度 ≈ 0，MTR 正常不会出现）：给不了符号，只报距离。
                bestOffset = Math.sqrt(sq);
            }
        }
        if (bestSq == Double.MAX_VALUE) {
            return null;
        }
        final double y = railY(bestAlong, entry.length, entry.yStart, entry.yEnd, entry.cable);
        return new Sample(y, Math.sqrt(bestSq), bestOffset, bestAlong, entry.length);
    }

    /**
     * <b>沿弧长的轨面高度</b>（纯函数，单元测试直接验它）。
     *
     * <p>与 MTR {@code Rail#getPositionY} 逐项同式：普通轨道是一条两端平、中间陡的二次曲线
     * （{@code y = yStart + (yEnd-yStart)/2 · (2s/L)²}，{@code s < L/2} 时），所以坡道中点的坡度是
     * 平均坡度的 <b>两倍</b>；缆车风格是「两头各 0.5 格平台 + 中间线性 + 下垂」。
     * 用<b>弧长参数</b>而不是最近采样点，坡道上脚底与轨面的高差才是连续的。
     *
     * @param s      弧长参数（格），内部会钳到 {@code [0, length]}
     * @param length 轨道全长（格）
     * @param yStart {@code getPosition(0).y}
     * @param yEnd   {@code getPosition(length).y}
     * @param cable  缆车风格（{@code RailType.RailSlopeStyle.CABLE}）
     */
    public static double railY(final double s, final double length, final double yStart,
                               final double yEnd, final boolean cable) {
        if (!(length > 1.0E-6D)) {
            return yStart;
        }
        final double v = clamp(s, 0.0D, length);
        final double rise = yEnd - yStart;
        if (cable) {
            if (v < 0.5D) {
                return yStart;
            }
            if (v > length - 0.5D) {
                return yEnd;
            }
            final double offset = v - 0.5D;
            final double offsetLength = length - 1.0D;
            if (!(offsetLength > 1.0E-6D)) {
                return yStart;
            }
            final double posY = yStart + rise * offset / offsetLength;
            final double dip = offsetLength * offsetLength / 4.0D / 1000.0D;
            final double shape = dip > 8.0D ? 8.0D / dip : 1.0D;
            return posY + shape * (offset - offsetLength) * offset / 1000.0D;
        }
        final double intercept = length / 2.0D;
        final double yChange;
        final double yInitial;
        final double offset;
        if (v < intercept) {
            yChange = rise / 2.0D;
            yInitial = yStart;
            offset = v;
        } else {
            yChange = -rise / 2.0D;
            yInitial = yEnd;
            offset = length - v;
        }
        return yChange * offset * offset / (intercept * intercept) + yInitial;
    }

    /** 重建缓存（表变了 / 锚点换了 / 缓存过期）。 */
    private void refresh(final Object railsMap, final long tick, final double anchorX,
                         final double anchorZ, final double margin) {
        final boolean sameMap = railsMap == cacheMap;
        final boolean sameAnchor = sameMap && !Double.isNaN(cacheX)
                && cacheX == anchorX && cacheZ == anchorZ;
        final boolean fresh = sameMap && tick - cacheTick < REBUILD_TICKS;
        if (sameAnchor && fresh) {
            return;
        }
        cacheMap = railsMap;
        cacheX = anchorX;
        cacheZ = anchorZ;
        cacheTick = tick;
        cache = build(railsMap, anchorX, anchorZ, margin);
    }

    /**
     * 建候选缓存：<b>准入判据是「锚点到这条轨道折线的真实距离」</b>，包围盒只做精确的下界剪枝。
     *
     * <p>三条与旧实现不同、也是这次修的东西：
     * <ol>
     *   <li>不再用 {@code bbox + CACHE_MARGIN} 当准入 —— 那是「查询点是否落在框里」，贴着框外
     *       半格的轨道会被判在候选之外，接缝两侧正好就是这个位置；</li>
     *   <li>单条轨道的任何异常<b>只丢这一条</b>（try/catch 在循环内部）：旧实现把 try 包在整张表
     *       外面，一条坏轨道就让<b>其后所有候选</b>静默消失，而且 {@code entry()} 失败还是不留日志的，
     *       所以「弯道在候选里、紧接着的直道不在」可以完全无声地发生；</li>
     *   <li>候选按到锚点的真实距离<b>升序</b>排列：接缝处两条相邻轨道都在，谁更近一眼能看出来
     *       （{@code where} 诊断就是按这个顺序打的）。</li>
     * </ol>
     */
    private List<Cand> build(final Object railsMap, final double x, final double z,
                             final double queryMargin) {
        final List<Cand> out = new ArrayList<>();
        lastScanned = 0;
        lastSkipped = 0;
        if (!(railsMap instanceof Map)) {
            return out;
        }
        // 缓存要覆盖「锚点 + 查询点可能离锚点最远 queryMargin」这一圈里的所有真候选。
        final double reach = queryMargin + CANDIDATE_REACH;
        // MTR 未 remap：表的泛型实参是 intermediary 名（class_2338），编译期解析不到具名类型，
        // 所以整条链路按 Object/Map<?, ?> 走，运行时就是 Map<BlockPos, Map<BlockPos, Rail>>。
        try {
            for (final Map.Entry<?, ?> outer : ((Map<?, ?>) railsMap).entrySet()) {
                final Object endsRaw = outer.getValue();
                if (!(endsRaw instanceof Map)) {
                    continue;
                }
                final Object startRaw = outer.getKey();
                if (!(startRaw instanceof BlockPos)) {
                    continue;
                }
                final BlockPos start = (BlockPos) (Object) startRaw;
                for (final Map.Entry<?, ?> inner : ((Map<?, ?>) endsRaw).entrySet()) {
                    final Object railRaw = inner.getValue();
                    final Object endRaw = inner.getKey();
                    if (!(railRaw instanceof Rail) || !(endRaw instanceof BlockPos)) {
                        continue;
                    }
                    // 单条轨道失败只丢它自己 —— 绝不能让一条坏轨道截断整张候选表（旧实现的坑）。
                    try {
                        final Cand cand = candidate((Rail) railRaw, start,
                                (BlockPos) (Object) endRaw, x, z, reach);
                        if (cand != null) {
                            out.add(cand);
                        }
                    } catch (Throwable t) {
                        lastSkipped++;
                        warnOnce(t);
                    }
                }
            }
        } catch (Throwable t) {
            // 整张表的迭代本身出问题（表在别处被改）：已经收到的候选照用。
            warnOnce(t);
        }
        // 近的排前面（稳定排序：距离相同按扫到的顺序，同一条轨道每次结果一致）。
        out.sort(Comparator.comparingDouble((Cand c) -> c.anchorDistance));
        return out;
    }

    /** 一条轨道能不能进候选：先是端点包围盒的廉价粗筛，再是「到折线的真实距离」。 */
    private Cand candidate(final Rail rail, final BlockPos start, final BlockPos end,
                           final double x, final double z, final double reach) {
        lastScanned++;
        if (!physical(rail)) {
            // NONE / AIRPLANE_DUMMY 是数据占位，没有真正的轨面，不该接住任何东西。
            return null;
        }
        final double length = rail.getLength();
        if (!(length > 1.0E-3D) || Double.isNaN(length) || Double.isInfinite(length)) {
            return null;
        }
        // 采样前的保守粗筛：曲线上任一点到两端点中较近那个的距离不超过弧长的一半
        // （三角不等式），所以「端点包围盒 + len/2 + 余量」不会漏掉任何真在附近的弧。
        // 精确范围由下面的采样折线包围盒给出，这一步只是省掉远处的 getPosition。
        final double slack = reach + length * 0.5D + 1.0D;
        if (x < Math.min(start.getX(), end.getX()) - slack
                || x > Math.max(start.getX(), end.getX()) + slack + 1.0D
                || z < Math.min(start.getZ(), end.getZ()) - slack
                || z > Math.max(start.getZ(), end.getZ()) + slack + 1.0D) {
            return null;
        }
        final Entry entry = geometry(rail, length);
        if (entry == null) {
            return null;
        }
        // 包围盒距离 = 真实距离的下界：超了就一定不可能命中（精确剪枝，不会漏候选）。
        if (entry.bboxDistance(x, z) > reach) {
            return null;
        }
        // 真正的准入：到折线的距离。
        final Sample sample = column(entry, x, z);
        if (sample == null || sample.distance > reach) {
            return null;
        }
        return new Cand(entry, sample.distance);
    }

    /**
     * 采样缓存的入口：命中就复用，没有才算。采样失败（MTR 抛异常 / 长度病态）返回 {@code null}
     * 并不入表 —— 下一条轨道、下一次重建会再试。
     */
    private Entry geometry(final Rail rail, final double length) {
        Entry entry = geometry.get(rail);
        if (entry != null) {
            return entry;
        }
        entry = entry(rail, length);
        if (entry == null) {
            return null;
        }
        if (geometry.size() >= GEOMETRY_MAX) {
            // 病态的大地图：宁可丢一次命中率，也不让这张表无界增长（键是弱引用，正常不会走到这里）。
            geometry.clear();
        }
        geometry.put(rail, entry);
        return entry;
    }

    /**
     * 把一条轨道按 {@code min(0.5, len/64)} 的步长<b>整条弧</b>采样成折线（弧长等距），求出真实包围盒，
     * 并记下高程曲线的两端。采样失败（MTR 抛异常 / 长度病态 / 出现 NaN）返回 {@code null}。
     *
     * <p>{@code getPosition} 对「同一条 Rail 的两段」是连续的（segment1 = 起点切向那段、
     * segment2 = 终点切向那段，接缝处位置实测差 1e-9 以内），所以整条弧一次采样不会在接缝处裂开。
     * 但任何一个顶点出现 NaN 就整条丢弃 —— NaN 包围盒会让 {@code bboxDistance} 永远返回 NaN、
     * 比较永远为假，那条轨道就会<b>静默地</b>永远进不了候选。
     */
    private static Entry entry(final Rail rail, final double length) {
        final double sampleStep = Math.min(SAMPLE_MAX_STEP, length / SAMPLE_DIVISIONS);
        final int steps = (int) Math.min(MAX_SAMPLES, Math.ceil(length / sampleStep));
        if (steps < 2) {
            return null;
        }
        final double[] xs = new double[steps + 1];
        final double[] zs = new double[steps + 1];
        final double[] ss = new double[steps + 1];
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (int i = 0; i <= steps; i++) {
            final double s = length * i / steps;
            final Vec3d p = position(rail, s);
            if (p == null) {
                return null;
            }
            if (!Double.isFinite(p.x) || !Double.isFinite(p.y) || !Double.isFinite(p.z)) {
                return null;
            }
            xs[i] = p.x;
            zs[i] = p.z;
            ss[i] = s;
            minX = Math.min(minX, p.x);
            maxX = Math.max(maxX, p.x);
            minZ = Math.min(minZ, p.z);
            maxZ = Math.max(maxZ, p.z);
            minY = Math.min(minY, p.y);
            maxY = Math.max(maxY, p.y);
        }
        // 高程曲线：两端采样点就是 getPosition(0)/(length) 的 y，与 MTR 的 yStart/yEnd 同值
        // （yStart/yEnd 是私有字段，从 getPosition 反推比反射稳）。
        final double yStart = sampleY(rail, 0.0D, minY, maxY);
        final double yEnd = sampleY(rail, length, minY, maxY);
        return new Entry(rail, length, xs, zs, ss, minX, minZ, maxX, maxZ, minY, maxY,
                yStart, yEnd, cable(rail), straight(xs, zs));
    }

    /**
     * 折线是不是直线（诊断里的 {@code straight=}）：所有顶点到首尾弦的距离都 ≤ 1mm。
     *
     * <p>只读 MTR 的公开 API（{@code getPosition}），不碰 {@code isStraight1/2} 这两个私有字段 ——
     * 它们在开发环境里能反射到，但运行时是 intermediary 名，靠不住。弯道 R=5 时弧中段离弦
     * 1.46 格，这个判定分得很干净。
     */
    private static boolean straight(final double[] xs, final double[] zs) {
        final int n = xs.length;
        if (n < 3) {
            return true;
        }
        final double ex = xs[n - 1] - xs[0];
        final double ez = zs[n - 1] - zs[0];
        final double len = Math.sqrt(ex * ex + ez * ez);
        if (!(len > 1.0E-6D)) {
            return true;
        }
        final double nx = -ez / len;
        final double nz = ex / len;
        double worst = 0.0D;
        for (int i = 1; i < n - 1; i++) {
            worst = Math.max(worst, Math.abs((xs[i] - xs[0]) * nx + (zs[i] - zs[0]) * nz));
        }
        return worst <= 1.0E-3D;
    }

    /** 端点 y：{@code getPosition} 拿不到时退回采样区间（宁可给个保守值，也不要 NaN）。 */
    private static double sampleY(final Rail rail, final double s, final double fallback,
                                  final double fallbackHigh) {
        final Vec3d p = position(rail, s);
        if (p == null || !Double.isFinite(p.y)) {
            return s <= 0.0D ? fallback : fallbackHigh;
        }
        return p.y;
    }

    /**
     * 这条轨道有没有真正的轨面。MTR 的 {@code Rail#railType} 枚举里 {@code NONE} /
     * {@code AIRPLANE_DUMMY} 只是数据占位（飞机航线的假轨道），没有可站的床面；其余类型
     * （WOODEN / STONE / PLATFORM / …）都是实体轨道。读不到类型时按「是」处理，与旧行为一致。
     */
    private static boolean physical(final Rail rail) {
        try {
            final RailType type = rail.railType;
            return type != RailType.NONE && type != RailType.AIRPLANE_DUMMY;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 缆车风格的高程公式不同（两头平台 + 中间线性 + 下垂），读不到就按普通轨道算。 */
    private static boolean cable(final Rail rail) {
        try {
            return rail.railType.railSlopeStyle == RailType.RailSlopeStyle.CABLE;
        } catch (Throwable t) {
            return false;
        }
    }

    /** {@code Rail#getPosition} 的唯一出口：MTR 未 remap，返回类型在编译期是 intermediary 名。 */
    private static Vec3d position(final Rail rail, final double t) {
        try {
            return (Vec3d) (Object) rail.getPosition(t);
        } catch (Throwable e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // /mtr_lte trackbed where —— 「这个位置到底有哪些轨道在候选里」
    // ---------------------------------------------------------------------------------------------

    /**
     * <b>{@code where} 诊断</b>：在这个位置重新查一次，然后把<b>候选集本身</b>按距离从近到远列出来。
     *
     * <p>{@code status} 那一行回答的是「结论是什么」（{@code drop=nohit} / {@code lat} / {@code dy}），
     * 但「为什么」只有候选表能回答：直道那条轨道到底在不在候选里、它的 {@code dist} 是多少、
     * 是不是被半宽挡住的。用户站到出问题的位置上跑一次，这一屏就是答案。
     *
     * <p>每一行：
     * <pre>[lte] where #1 dist=0.02 off=+0.02 y=64.00 along=3.20/10.00 straight=yes WOODEN WIN</pre>
     * 读法：
     * <ul>
     *   <li>{@code dist} —— 查询点到这条轨道<b>折线</b>的真实距离（不是包围盒距离）；</li>
     *   <li>{@code off} —— 有符号横向偏移（法向哪一侧，正负本身是信息）；</li>
     *   <li>{@code y} —— 投影列处的轨面高度；{@code along/len} —— 投影落在轨道的哪个位置，
     *       贴着 {@code 0.00} 或 {@code len} 就说明这条已经走到头了（接缝的典型形态）；</li>
     *   <li>{@code straight=yes|no} —— 折线是直线还是弧（{@code yes} 就是直道那条）；</li>
     *   <li>{@code WIN} —— 这次真正被选中的那条。没有 {@code WIN} 就是一条都没命中。</li>
     * </ul>
     * 表头另给 {@code rails=}（这张表里一共多少条轨道）、{@code near=}（过了廉价粗筛的）、
     * {@code cand=}（真正进候选的）、{@code skipped=}（单条异常被跳过的 —— 不为 0 就说明数据里有
     * 坏轨道）。
     */
    public String explain(final Object railsMap, final long tick, final double x, final double y,
                          final double z) {
        final StringBuilder out = new StringBuilder();
        final double anchorX = Math.floor(x / 2.0D) * 2.0D;
        final double anchorZ = Math.floor(z / 2.0D) * 2.0D;
        out.append("[lte] where at=(").append(fmt(x)).append(", ").append(fmt(y)).append(", ")
                .append(fmt(z)).append(") anchor=(").append(fmt(anchorX)).append(", ")
                .append(fmt(anchorZ)).append(')');
        if (railsMap == null || !(railsMap instanceof Map)) {
            return out.append(" rails=0 (轨道表为空/未加载 —— 没有 ClientData.RAILS)").toString();
        }
        final Sample best = probe(railsMap, tick, x, y, z, anchorX, anchorZ,
                SEARCH_RADIUS + 2.0D);
        out.append(" rails=").append(total(railsMap))
                .append(" near=").append(lastScanned)
                .append(" cand=").append(cache.size())
                .append(" skipped=").append(lastSkipped)
                .append(" halfW=").append(fmt(HALF_WIDTH))
                .append(" reach=").append(fmt(CANDIDATE_REACH));
        out.append(" hit=").append(lastHit ? "yes" : "no");
        if (cache.isEmpty()) {
            out.append('\n').append("[lte] where: 候选表是空的 —— 这个位置周围 ")
                    .append(fmt(SEARCH_RADIUS)).append(" 格里一条轨道都没进候选");
            return out.toString();
        }
        int index = 0;
        for (final Cand cand : cache) {
            if (index >= WHERE_MAX) {
                out.append('\n').append("[lte] where: … 还有 ").append(cache.size() - WHERE_MAX)
                        .append(" 条候选没列");
                break;
            }
            index++;
            final Entry entry = cand.entry;
            final Sample sample = column(entry, x, z);
            final boolean win = best != null && sample != null
                    && Math.abs(sample.distance - best.distance) < 1.0E-9D
                    && Math.abs(sample.along - best.along) < 1.0E-9D;
            out.append('\n').append("[lte] where #").append(index)
                    .append(" dist=").append(sample == null ? "-" : fmt(sample.distance))
                    .append(" off=").append(sample == null ? "-" : signed(sample.offset))
                    .append(" y=").append(sample == null ? "-" : fmt(sample.y))
                    .append(" along=").append(sample == null ? "-"
                            : fmt(sample.along) + "/" + fmt(entry.length))
                    .append(" straight=").append(entry.straight ? "yes" : "no")
                    .append(" anchorDist=").append(fmt(cand.anchorDistance))
                    .append(' ').append(type(entry))
                    .append(win ? " WIN" : (sample == null ? " (超出 reach)"
                            : (sample.distance > HALF_WIDTH ? " (超出 halfW)" : "")));
        }
        if (best == null) {
            out.append('\n').append("[lte] where: 没有命中 —— 上面每一条的 dist 都比 halfW=")
                    .append(fmt(HALF_WIDTH)).append(" 大");
        }
        return out.toString();
    }

    /**
     * 表里一共有多少条轨道（诊断行的 {@code rails=}）：只累加内层表的 {@code size()}，不碰任何轨道
     * 几何，所以每 tick 打一行逐 tick 诊断也不会变成性能问题（调用方还会再按秒缓存）。
     */
    public static int tableSize(final Object railsMap) {
        return total(railsMap);
    }

    /** 表里一共有多少条轨道（{@code where} 的 {@code rails=}）。 */
    private static int total(final Object railsMap) {
        int count = 0;
        try {
            for (final Map.Entry<?, ?> outer : ((Map<?, ?>) railsMap).entrySet()) {
                final Object ends = outer.getValue();
                if (ends instanceof Map) {
                    count += ((Map<?, ?>) ends).size();
                }
            }
        } catch (Throwable t) {
            return -1;
        }
        return count;
    }

    /** {@code WOODEN} / {@code PLATFORM} / … —— 诊断里一眼看出这条是哪种轨道。 */
    private static String type(final Entry entry) {
        try {
            return entry.rail.railType.name();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String fmt(final double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? "-"
                : String.format(Locale.ROOT, "%.2f", Double.valueOf(value));
    }

    private static String signed(final double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? "-"
                : String.format(Locale.ROOT, "%+.2f", Double.valueOf(value));
    }

    private static double clamp(final double v, final double lo, final double hi) {
        final double above = v < lo ? lo : v;
        return above > hi ? hi : above;
    }

    /** MTR 调用失败只 warn 一次，之后静默（同一 tick 里刷屏没有意义）。 */
    private void warnOnce(final Throwable t) {
        if (warned.compareAndSet(false, true)) {
            LteLog.LOGGER.warn("[lte] collision: MTR rail data unavailable, "
                    + "trackbed collision disabled until it recovers", t);
        }
    }
}
