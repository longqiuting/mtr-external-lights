package dev.js.mtrheadlight.server;

import dev.js.mtrheadlight.HeadlightConfig;
import dev.js.mtrheadlight.collision.CollisionImpact;
import dev.js.mtrheadlight.collision.RailBed;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * <b>服务端道床</b>：让掉落物 / 下落的方块 / 生物也踩得到 MTR 轨面。
 *
 * <h2>为什么要有它</h2>
 * 客户端那条路（{@code CollisionSystem} + {@link dev.js.mtrheadlight.collision.Trackbed}）只作用于
 * <b>本地玩家</b>，因为玩家的位置是客户端权威的。其它实体不行：{@code setPosition} 在客户端会被
 * 服务端的位置包冲掉，{@code LivingEntity#damage} 在客户端第一行就返回 false。于是玩家丢下的方块
 * 会从轨道中间穿过去、一直掉到虚空，生物会卡在轨道下方。
 *
 * <h2>判定（与玩家那条路同一个几何）</h2>
 * <pre>
 * 轨面 y   = RailBed 采样（{@code RailwayData#getRailsMap()}，与客户端 {@code ClientData.RAILS} 同构）
 * 可站立面 = 轨面 + trackbedThickness（默认 0.15）
 *
 * resting（已经贴着）：vy ≤ 0.02 且 脚底 ∈ [面 − 0.03, 面 + 0.10]
 * crossed（这一 tick 从上往下穿过）：vy < 0 且 上一 tick 的 y ≥ 面 − 0.03 且 本 tick 的 y < 面
 * 两者都不满足 → 不接
 * 生效时：y 吸到面、竖直速度归零、fallDistance 清零、onGround = true
 * </pre>
 *
 * <p>「从上方穿过」这个语义很重要：站在高架下面（实体远在道床面下方）永远不满足 crossed，
 * 所以不会被从下面吸上去。另外再加一道 {@link #MAX_DROP}：<b>只在「已经贴着轨面」（resting）时
 * 才限制</b> —— 一次掉超过 3 格、明显已经在虚空里的不接。crossed 的实体这一 tick 的起点就在轨面
 * 之上，那是货真价实的「落下来穿过轨面」，一 tick 掉多少格都要接（终端速度 3.92 格/tick 比旧上限
 * 还大，旧实现正是这样让高速下落的方块穿过轨道掉进虚空的）。
 *
 * <h2>范围</h2>
 * 不按玩家取中心：<b>每个实体以自己的位置查轨道</b>（{@code RailBed#nearestCell} 的锚就是它所在
 * 的区块格），所以「车开走之后掉在 64 格外的方块」照样接得住 —— 那正是「远距离物品掉下轨道」的
 * 成因：旧实现用「玩家 64 格内」做闸门，超过就直接跳过，方块一路穿到虚空。
 *
 * <p>现在的边界只有两条，都与玩家无关：<b>该实体所在区块已加载</b>（未加载的区块里没有可信的
 * 轨道数据；实体本来就只存在于已加载的区块，等效半径就是一个模拟距离 ≈128–160 格），以及它真的
 * 落在某条实体轨道的 {@link RailBed#HALF_WIDTH} 之内。每个实体的查询命中自己区块格的
 * {@link RailBed} 缓存，命中缓存时只是一次 Map 查表 + 一小段扫描。
 *
 * <h2>与列车碰撞的关系</h2>
 * 被列车撞飞的实体正在按抛物线飞，不能立刻被轨面吸住 —— {@link ServerCollision#recentlyHit} 给的
 * {@value #IMPACT_GRACE} tick 宽限期内一律跳过；{@code CollisionImpact} 的「飞出」状态也一样跳过
 * （那种实体的伤害还没结算完）。
 */
public final class ServerTrackbed {

    /**
     * 处理半径（格，到最近玩家）。<b>只是 CPU 上限，不是几何基准</b>：轨道查询永远以实体自己在的
     * 位置为锚。128 格已经覆盖「车开过去、物品掉在身后」的场景；再远的位置正常情况下区块都没加载，
     * 实体本身也不在 {@code iterateEntities()} 里，那条闸门由 {@link #chunkLoaded} 负责。
     */
    private static final double RANGE = 128.0D;

    /** 被列车撞过之后多少 tick 内不吸附（给撞飞留出飞行时间）。 */
    private static final int IMPACT_GRACE = 10;

    /** 已经贴着轨面时的判定容差（格）。 */
    private static final double REST_BELOW = 0.03D;
    private static final double REST_ABOVE = 0.10D;

    /** 一次最多从上方「接住」多少格的下落；更深的一律不管。 */
    private static final double MAX_DROP = 3.0D;

    private static final Map<ServerWorld, Map<Long, RailBed>> BEDS = new WeakHashMap<>();

    private static int diagItems;
    private static int diagMobs;
    private static int diagPlayers;

    private ServerTrackbed() {
    }

    /** {@code END_SERVER_TICK}（由 {@code ServerCollision#tick} 每个维度调一次）。 */
    public static void tick(final ServerWorld world, final HeadlightConfig cfg, final long now) {
        if (world == null || !cfg.trackbedCollision) {
            return;
        }
        if (!cfg.trackbedCollisionItems && !cfg.trackbedCollisionEntities) {
            return;
        }
        final List<ServerPlayerEntity> players = world.getPlayers();
        if (players.isEmpty()) {
            return;
        }
        final Object rails = MtrServerData.rails(world);
        if (rails == null) {
            return;
        }
        final double thickness = cfg.trackbedThicknessClamped();
        int seen = 0;
        for (final Entity entity : world.iterateEntities()) {
            try {
                if (entity instanceof PlayerEntity) {
                    // 玩家由客户端那条路处理（位置是客户端权威的）。
                    if (near(entity, players)) {
                        seen++;
                    }
                    continue;
                }
                final boolean item = entity instanceof ItemEntity || entity instanceof FallingBlockEntity;
                if (item) {
                    if (!cfg.trackbedCollisionItems) {
                        continue;
                    }
                } else {
                    if (!cfg.trackbedCollisionEntities || !(entity instanceof LivingEntity)) {
                        continue;
                    }
                    if (!entity.isAlive() || CollisionImpact.launched(entity.getUuid())) {
                        continue;
                    }
                }
                if (!near(entity, players)) {
                    continue;
                }
                if (!chunkLoaded(world, entity)) {
                    continue;
                }
                if (ServerCollision.recentlyHit(entity, now, IMPACT_GRACE)) {
                    // 刚被列车撞飞：让它飞完，别在半空吸到轨面上。
                    continue;
                }
                if (snap(world, rails, entity, thickness, now)) {
                    if (item) {
                        diagItems++;
                    } else {
                        diagMobs++;
                    }
                }
            } catch (Throwable ignored) {
                // 单个实体算错不能影响其它实体，更不能影响服务端 tick。
            }
        }
        diagPlayers = Math.max(diagPlayers, seen);
    }

    /** 把一个实体吸到轨面上；返回 true 表示真的动了。 */
    private static boolean snap(final ServerWorld world, final Object rails,
                                final Entity entity, final double thickness, final long now) {
        final double x = entity.getX();
        final double feet = entity.getY();
        final double z = entity.getZ();
        final int cellX = entity.getBlockX() >> 4;
        final int cellZ = entity.getBlockZ() >> 4;
        final RailBed.Sample sample = bed(world, cellX, cellZ)
                .nearestCell(rails, now, cellX, cellZ, x, feet, z);
        if (sample == null) {
            return false;
        }
        final double surface = sample.y + thickness;
        final Vec3d v = entity.getVelocity();
        // 上一 tick 的 y：Entity#baseTick 在 tick 一开始就把 prevY 设为当时的 y，
        // 所以 END_SERVER_TICK 读到的是「这一 tick 从哪里来」。
        final double from = entity.prevY;
        final boolean resting = v.y <= 0.02D
                && feet >= surface - REST_BELOW && feet <= surface + REST_ABOVE;
        final boolean crossed = v.y < -1.0E-3D
                && from >= surface - REST_BELOW && feet < surface;
        if (!resting && !crossed) {
            return false;
        }
        // 「已经掉得很深」只有对 resting 才成立：crossed 已经证明这一 tick 起点在轨面之上，
        // 那就一定是「落下来穿过轨面」，多深都该接。旧实现拿 feet < surface - 3 一律拒绝，
        // 于是高速下落（终端速度 3.92 格/tick）的物品一 tick 就掉过 3 格，直接穿进虚空 ——
        // 这才是「远距离 / 高处掉下的物品掉下轨道」的另一半原因。
        if (!crossed && feet < surface - MAX_DROP) {
            return false;
        }
        final Box moved = entity.getBoundingBox().offset(0.0D, surface - feet, 0.0D);
        if (!world.isSpaceEmpty(entity, moved)) {
            return false;
        }
        entity.setPosition(x, surface, z);
        entity.setVelocity(v.x, 0.0D, v.z);
        entity.fallDistance = 0.0F;
        entity.setOnGround(true);
        // 服务端实体的速度/位置变化要同步给客户端，否则画面上它还在继续掉。
        entity.velocityModified = true;
        return true;
    }

    /** 玩家 64 格内（水平）。 */
    private static boolean near(final Entity entity, final List<ServerPlayerEntity> players) {
        final double x = entity.getX();
        final double z = entity.getZ();
        for (final ServerPlayerEntity player : players) {
            final double dx = player.getX() - x;
            final double dz = player.getZ() - z;
            if (dx * dx + dz * dz <= RANGE * RANGE) {
                return true;
            }
        }
        return false;
    }

    /** 实体所在区块已加载（未加载的区块里没有可信的轨道数据）。 */
    private static boolean chunkLoaded(final ServerWorld world, final Entity entity) {
        try {
            return world.isChunkLoaded(entity.getBlockX() >> 4, entity.getBlockZ() >> 4);
        } catch (Throwable t) {
            return false;
        }
    }

    private static RailBed bed(final ServerWorld world, final int cellX, final int cellZ) {
        final long key = ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
        synchronized (BEDS) {
            Map<Long, RailBed> cells = BEDS.get(world);
            if (cells == null) {
                cells = new java.util.HashMap<>();
                BEDS.put(world, cells);
            }
            RailBed bed = cells.get(Long.valueOf(key));
            if (bed == null) {
                if (cells.size() > 512) {
                    // 走了很远：整张表丢掉重来，比逐个过期便宜。
                    cells.clear();
                }
                bed = new RailBed();
                cells.put(Long.valueOf(key), bed);
            }
            return bed;
        }
    }

    /** 世界卸载 / 重载。 */
    public static void reset() {
        synchronized (BEDS) {
            BEDS.clear();
        }
        MtrServerData.reset();
        diagItems = 0;
        diagMobs = 0;
        diagPlayers = 0;
    }

    /** 诊断：这一窗里被接住的物品 / 生物，以及附近玩家数（取峰值）。 */
    public static String diagLine() {
        final String line = "trackbed: players=" + diagPlayers
                + " items=" + diagItems + " mobs=" + diagMobs;
        diagItems = 0;
        diagMobs = 0;
        diagPlayers = 0;
        return line;
    }

    /** 有没有值得打印的东西（这一窗一个也没接住就不打）。 */
    public static boolean diagDirty() {
        return diagItems > 0 || diagMobs > 0 || diagPlayers > 0;
    }
}
