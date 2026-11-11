package dev.js.mtrheadlight.signal;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.McCompat;
import mtr.client.ClientData;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Map;

/**
 * 手持<b>任意一个</b>连接器（{@code signal_linker} / {@code projector_connector} / {@code pids_connector}）时，
 * 在附近的轨道节点上画方块轮廓（白）。拿信号连接器时，第一次右键选中的那颗节点画青色。
 *
 * <h2>为什么是三个连接器共用一份渲染</h2>
 * <p>三个连接器要点的东西是同一张表：MTR 的轨道节点（{@code ClientData.RAILS} 的键）。
 * 信号连接器绑节点，投影机 / PIDS 连接器也要点节点才能反推站台。所以「哪只手拿着哪一件」
 * 只在最前面判一次，后面的收集 / 排序 / 绘制完全共用 —— 一条 {@code AFTER_TRANSLUCENT}
 * 回调、一个 {@code VertexConsumer}、一次 {@code draw(LINES)}，不会因为多判两个物品而多画一遍。
 *
 * <h2>为什么是这套做法</h2>
 * <ul>
 *   <li><b>节点从 {@code ClientData.RAILS} 拿，不是扫方块。</b>{@code BlockNode} 的 collision/outline
 *       只有 1px 高（{@code BlockNode#getOutlineShape}：{@code IS_CONNECTED} 时
 *       {@code cuboid(0,0,0,16,1,16)}），而且弯道上的轨道是几何体不是方块，玩家很难瞄。MTR 客户端本来
 *       就维护着 {@code ClientData.RAILS}（键 = 节点方块坐标，值 = 「另一端节点 → Rail」），
 *       所以「附近有哪些节点」是一次 Map 遍历，不用读 32³ 个方块。服务端那条路用的是
 *       {@code RailwayData#getRailsMap()}（见 {@link SignalLinkNet#railsAt}），两边是同一张表的两个副本。</li>
 *   <li><b>范围跟着视距走，不是写死 32 格。</b>玩家能看到的轨道就应该能选中，否则站在站台一端
 *       要走到另一头才能点节点（和 ANTE 的「刷子」那种「视距内全亮」的观感差很多）。半径取
 *       {@code options.getViewDistance() * 16}（区块数 × 16），也就是客户端真正加载/渲染的水平范围。</li>
 *   <li><b>视锥粗筛。</b>视距 12 区块 = 半径 192 格，球体积是 32 格时的 216 倍，全量画轮廓会有几万个
 *       节点。所以先用「相机朝向 · 到节点的方向」做一次点积筛选（阈值 {@value #FRUSTUM_DOT}，
 *       约 ±78°，比真实视锥宽，只淘汰明显在身后的节点，不追求精确剔除 —— 宁可多画别漏）。
 *       相机脚边（{@value #NEAR_HOLD} 格内）的节点无条件保留：正下方的节点点积接近 0，
 *       但那是玩家最可能想点的那一个。</li>
 *   <li><b>上限 {@value #MAX_NODES} 个，按距离取最近的。</b>超出时用一个小顶堆（键 = 到相机的距离平方）
 *       滚动保留最近的 N 个，最后堆排序成距离开序；不是「收集到 N 个就 break」（那样保留的是
 *       Map 的遍历顺序，等于随机）。</li>
 *   <li><b>一帧一次 draw。</b>所有节点都往同一个 {@code VertexConsumer}（{@code RenderLayer.getLines()}）
 *       里追加顶点，最后统一 {@code Immediate#draw(LINES)} —— 4000 个节点 = 96000 个顶点，一次提交；
 *       按节点逐个 draw 会有几千次 pipeline flush。</li>
 *   <li><b>自己 draw，不等别人。</b>世界渲染到 {@code AFTER_TRANSLUCENT} 时主轴已经画完，
 *       往 {@code VertexConsumerProvider.Immediate} 里塞完必须立刻 {@code draw(LINES)}，
 *       否则要等到下一次 pass 才可见（而 { LINES } 不一定还有机会被画）。</li>
 * </ul>
 *
 * <p>1.20.1 的入口是 {@code WorldRenderer#drawBox(MatrixStack, VertexConsumer, x1..z2, r,g,b,a)}：
 * 它自己会写 {@code vertex → color → normal → next}（javap 已核对），所以配
 * {@code RenderLayer.getLines()}（POSITION_COLOR_NORMAL）不会因为缺 normal 抛
 * 「Not filled all elements」。
 *
 * <p>诊断：手持时每 {@value #DIAG_PERIOD_MS} ms 打一行
 * {@code [lte] node marks: item=<hand> nodes=N drawn=D range=R}（items = 手上那件连接器，
 * nodes = 通过距离+视锥的节点数，drawn = 实际画出的数，range = 当前半径）。不手持时不打。
 */
@Environment(EnvType.CLIENT)
public final class ConnectorNodeHighlight {

    /** 每帧最多画多少个节点。视距 192 格时全量会有几万个，必须封顶。 */
    private static final int MAX_NODES = 4000;

    /**
     * 视锥粗筛阈值：{@code dot(相机朝向, 单位化(节点-相机)) < 此值} 的节点跳过。
     * 0.2 ≈ 78° 半角，比真实视锥（约 70° 水平半角的一半）宽得多，只挡掉身后和侧面很远的。
     */
    private static final double FRUSTUM_DOT = 0.2D;

    /** 这个距离（格）以内的节点不做视锥筛（正下方/正后方贴身节点也要能点）。 */
    private static final double NEAR_HOLD = 4.0D;
    private static final double NEAR_HOLD_SQ = NEAR_HOLD * NEAR_HOLD;

    /** 诊断行周期（ms）。 */
    private static final long DIAG_PERIOD_MS = 5000L;

    /** 轮廓略微外扩，压在方块边界上不和节点自己的贴图 z-fighting。 */
    private static final double INFLATE = 0.002D;

    /** 复用的收集缓冲：渲染在主线程，静态一份够用，省掉每帧 4000 个引用的分配。 */
    private static final Scratch SCRATCH = new Scratch(MAX_NODES);

    private static long lastDiagMs;

    private ConnectorNodeHighlight() {
    }

    /** 手上那件连接器（没拿就是 {@code null}）。 */
    private static final class Held {
        final ItemStack stack;
        final boolean signal;
        /** 诊断行里显示的名字。 */
        final String name;

        Held(final ItemStack stack, final boolean signal, final String name) {
            this.stack = stack;
            this.signal = signal;
            this.name = name;
        }
    }

    /**
     * 这个玩家手上（主手或副手）拿的连接器；没拿返回 {@code null}。
     *
     * <p>{@code signal} 只影响「已选节点画青色」这一件事 —— 那个选区挂在
     * {@code SignalLinkItem} 的物品 NBT 上，另外两件没有这个概念。
     */
    private static Held held(final PlayerEntity player) {
        final ItemStack main = player.getMainHandStack();
        final ItemStack off = player.getOffHandStack();
        final Item signal = LteCoreItems.signalLinker();
        if (signal != null) {
            if (main.isOf(signal)) {
                return new Held(main, true, "signal");
            }
            if (off.isOf(signal)) {
                return new Held(off, true, "signal");
            }
        }
        final Item projector = LteCoreItems.projectorConnector();
        if (projector != null) {
            if (main.isOf(projector)) {
                return new Held(main, false, "projector");
            }
            if (off.isOf(projector)) {
                return new Held(off, false, "projector");
            }
        }
        final Item pids = LteCoreItems.pidsConnector();
        if (pids != null) {
            if (main.isOf(pids)) {
                return new Held(main, false, "pids");
            }
            if (off.isOf(pids)) {
                return new Held(off, false, "pids");
            }
        }
        return null;
    }

    /** 挂到 {@code WorldRenderEvents.AFTER_TRANSLUCENT}；异常一律不外抛（渲染报错会黑屏）。 */
    public static void render(final WorldRenderContext context) {
        try {
            renderInner(context);
        } catch (Throwable ignored) {
            // 节点标记是辅助显示，坏了就当没画。
        }
    }

    private static void renderInner(final WorldRenderContext context) {
        final MinecraftClient client = MinecraftClient.getInstance();
        final PlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return;
        }
        final Held held = held(player);
        if (held == null) {
            return;
        }

        final MatrixStack matrices = context.matrixStack();
        if (matrices == null) {
            return;
        }
        final Vec3d camera = context.camera().getPos();

        // 半径 = 视距（区块）× 16；视距的取法逐版本不同，统一走 McCompat。
        final int viewChunks = McCompat.viewDistanceChunks();
        final double range = Math.max(16, viewChunks * 16);
        final double rangeSq = range * range;

        // 相机朝向（第三人称也成立，因为用的是相机而不是玩家实体）。
        // 不用 camera.getRotation()：它返回 org.joml.Quaternionf，而 JOML 没有出现在本工程的
        // 编译 classpath 里（只有 MC 内部可见），所以走 Camera#getYaw()/getPitch() 这对老接口。
        final Vec3d look = Vec3d.fromPolar(context.camera().getPitch(), context.camera().getYaw());

        // 已选节点（第一次右键进来的那个）单独用青色，避免和白框混在一起。
        final BlockPos selected = held.signal ? SignalLinkItem.selectedNode(held.stack) : null;

        final int nodes = collect(camera, look, rangeSq);

        final VertexConsumerProvider.Immediate buffers =
                client.getBufferBuilders().getEntityVertexConsumers();
        final VertexConsumer lines = buffers.getBuffer(RenderLayer.getLines());

        matrices.push();
        // 顶点用世界坐标，相机平移抵消掉（与 BeamGlow/ProtoBeamCone 同一套做法）。
        matrices.translate(-camera.x, -camera.y, -camera.z);
        final int drawn;
        try {
            drawn = drawNodes(matrices, lines, selected);
        } finally {
            matrices.pop();
        }
        // 全部节点都进了同一个 VertexConsumer，这里一次提交（不是每节点一次）。
        if (drawn > 0) {
            buffers.draw(RenderLayer.getLines());
        }
        diag(held.name, nodes, drawn, range);
    }

    /**
     * 扫一遍 {@code ClientData.RAILS}，把通过距离与视锥粗筛的节点丢进小顶堆，保留最近的
     * {@value #MAX_NODES} 个，并按距离升序排好。
     *
     * @return 通过筛选的节点总数（可能大于 {@value #MAX_NODES}）。
     */
    private static int collect(final Vec3d camera, final Vec3d look,
                               final double rangeSq) {
        final Scratch scratch = SCRATCH;
        scratch.clear();
        // 未 remap 的 MTR jar 里这个字段的泛型形参是 intermediary 名（class_2338），
        // 直接按 Map<BlockPos, …> 用会让 javac 去解析那个不存在的类，所以只看键集合。
        final Map<?, ?> rails = (Map<?, ?>) ClientData.RAILS;
        if (rails == null) {
            return 0;
        }
        final double lx = look.x;
        final double ly = look.y;
        final double lz = look.z;
        int seen = 0;
        for (final Object key : rails.keySet()) {
            if (!(key instanceof BlockPos node)) {
                continue;
            }
            // 方块中心到相机，而不是方块角：角上的偏差在 192 格尺度上能差出半个视锥。
            final double dx = node.getX() + 0.5D - camera.x;
            final double dy = node.getY() + 0.5D - camera.y;
            final double dz = node.getZ() + 0.5D - camera.z;
            final double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > rangeSq) {
                continue;
            }
            if (distSq > NEAR_HOLD_SQ) {
                // 视锥粗筛：只看方向点积，不做法线/平面距离那一套（要精确就得上 Matrix4f 反投影）。
                final double inv = 1.0D / Math.sqrt(distSq);
                if ((dx * inv) * lx + (dy * inv) * ly + (dz * inv) * lz < FRUSTUM_DOT) {
                    continue;
                }
            }
            scratch.offer(node, distSq);
            seen++;
        }
        scratch.sortAscending();
        return seen;
    }

    /** @return 实际画出的节点数。 */
    private static int drawNodes(final MatrixStack matrices, final VertexConsumer lines,
                                 final BlockPos selected) {
        final Scratch scratch = SCRATCH;
        final int count = scratch.size;
        for (int i = 0; i < count; i++) {
            final BlockPos node = scratch.pos[i];
            if (selected != null && node.equals(selected)) {
                box(matrices, lines, node, 0.25F, 0.90F, 1.00F, 0.90F);
            } else {
                box(matrices, lines, node, 1.00F, 1.00F, 1.00F, 0.55F);
            }
        }
        return count;
    }

    /** 每 5 秒一行；只在手持时被调到（不手持在前面就 return 了）。 */
    private static void diag(final String item, final int nodes, final int drawn, final double range) {
        final long now = System.currentTimeMillis();
        if (lastDiagMs != 0L && now - lastDiagMs < DIAG_PERIOD_MS) {
            return;
        }
        lastDiagMs = now;
        LteLog.LOGGER.info("[lte] node marks: item={} nodes={} drawn={} range={}",
                item, Integer.valueOf(nodes), Integer.valueOf(drawn), Integer.valueOf((int) range));
    }

    private static void box(final MatrixStack matrices, final VertexConsumer lines, final BlockPos pos,
                            final float red, final float green, final float blue, final float alpha) {
        final double x = pos.getX();
        final double y = pos.getY();
        final double z = pos.getZ();
        WorldRenderer.drawBox(matrices, lines,
                x - INFLATE, y - INFLATE, z - INFLATE,
                x + 1.0D + INFLATE, y + 1.0D + INFLATE, z + 1.0D + INFLATE,
                red, green, blue, alpha);
    }

    /**
     * 「最近的 N 个」用的定长小顶堆（键 = 距离平方，堆顶是<b>最远</b>的那个，
     * 所以新节点只要比堆顶近就替换掉它）。
     *
     * <p>为什么不用 {@code ArrayList + sort}：视距 192 格时一个路网能轻松过万节点，
     * 每帧装箱 + 排序会稳定掉帧；定长数组 + 手写堆全程零分配。
     */
    private static final class Scratch {

        final BlockPos[] pos;
        final double[] distSq;
        int size;

        Scratch(int capacity) {
            this.pos = new BlockPos[capacity];
            this.distSq = new double[capacity];
        }

        void clear() {
            // 不清元素引用也行（size=0 就够了），但清掉能让 GC 早点回收旧世界的节点。
            java.util.Arrays.fill(pos, null);
            size = 0;
        }

        void offer(final BlockPos node, final double dist) {
            if (size < pos.length) {
                pos[size] = node;
                distSq[size] = dist;
                siftUp(size++);
                return;
            }
            if (dist < distSq[0]) {
                pos[0] = node;
                distSq[0] = dist;
                siftDown(0);
            }
        }

        /** 堆排序：反复把堆顶（最远）换到末尾，结果按距离升序。 */
        void sortAscending() {
            int end = size - 1;
            while (end > 0) {
                swap(0, end);
                end--;
                siftDownWithin(0, end + 1);
            }
        }

        private void siftUp(int index) {
            int i = index;
            while (i > 0) {
                final int parent = (i - 1) >> 1;
                if (distSq[parent] >= distSq[i]) {
                    return;
                }
                swap(i, parent);
                i = parent;
            }
        }

        private void siftDown(int index) {
            siftDownWithin(index, size);
        }

        private void siftDownWithin(int index, final int limit) {
            int i = index;
            while (true) {
                final int left = (i << 1) + 1;
                final int right = left + 1;
                int biggest = i;
                if (left < limit && distSq[left] > distSq[biggest]) {
                    biggest = left;
                }
                if (right < limit && distSq[right] > distSq[biggest]) {
                    biggest = right;
                }
                if (biggest == i) {
                    return;
                }
                swap(i, biggest);
                i = biggest;
            }
        }

        private void swap(final int a, final int b) {
            final BlockPos p = pos[a];
            pos[a] = pos[b];
            pos[b] = p;
            final double d = distSq[a];
            distSq[a] = distSq[b];
            distSq[b] = d;
        }
    }
}
