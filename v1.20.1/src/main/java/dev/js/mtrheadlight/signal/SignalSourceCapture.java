package dev.js.mtrheadlight.signal;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * 渲染一帧时的「这盏灯该抄哪盏灯」快照，由 {@code RenderSignalBaseMixin} 在
 * {@code getNodePos} 里填好、在 {@code getOccupiedAspect} 的参数改写里用掉。
 *
 * <p>为什么要这个中转：MTR 的 {@code getOccupiedAspect(BlockPos, float)} 只拿「节点 + 角度」，
 * 拿不到「这是哪一格信号灯」，而判断 lamp 链接恰恰需要信号灯坐标。{@code getNodePos} 是唯一
 * 同时看得到信号灯坐标、朝向和方块视图的位置，所以在那里把结论算好、挂在当前线程上。
 *
 * <p>用 {@link ThreadLocal} 而不是字段：渲染可能在客户端主线程之外（区块重建线程）跑；绑定表本身
 * 是线程安全的，这里的状态也必须跟着线程走。
 */
public final class SignalSourceCapture {

    /** 正在渲染的这盏灯自己的朝向（MTR 传进 {@code getNodePos} 的那个）。 */
    public final Direction facing;

    /** 源灯（被抄的那盏）的朝向。 */
    public final Direction sourceFacing;

    /** 源灯要用的节点坐标；{@code null} = 源灯自己也没节点，放弃同步（让它保持 MTR 原行为）。 */
    public final BlockPos node;

    public SignalSourceCapture(final Direction facing, final Direction sourceFacing, final BlockPos node) {
        this.facing = facing;
        this.sourceFacing = sourceFacing;
        this.node = node;
    }
}
