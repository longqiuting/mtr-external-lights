package dev.js.mtrheadlight;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;

/**
 * 一条车灯光束的几何 —— 灯口位置、方向、长度、离地高度、颜色。
 *
 * <p><b>光带和光柱唯一的几何来源。</b>{@link BeamGlow} 的加色光带和 {@link ProtoBeamCone} 的
 * 体积光柱都从这里取 {@code base/axis/length}，谁都不再自己算一遍：只要两边调的是同一个
 * {@link #of}，位置就不可能差一格。{@link ProtoBeamCone} 的 {@code beamConeSource = GLOW}
 * 对照模式就是靠这一点工作的（见 HeadlightConfig）。
 *
 * <pre>
 * origin = railPoint + dir * offset          // 车端面往前推 offset，y 仍带坡度
 * axis   = dir                               // 该车自己的单位方向（车身弦，含坡度）
 * length = 配置的光束长度
 * height = beamLift + 该端的 beamHead/TailHeight
 * </pre>
 *
 * <p>{@link EndLight#of} 是<b>动态光源</b>（LambDynamicLights / 轨道烘焙）那一侧的同类计算，
 * 它把 {@link HeadlightConfig#heightOffset} 直接合进灯点；光带/光柱的 y 则来自地面（光带贴地），
 * 所以这里只保留"离地多少"这一个是共享的量，取 y 的规则在 {@link BeamGlow#startHeight}。
 */
final class BeamGeom {
    /** 灯口：车端面的轨道点沿该车切线推出 {@code offset}，y 未加离地高度。 */
    final Vec3d origin;
    /** 出灯方向：单位向量，仍带坡度。 */
    final Vec3d axis;
    /** 光束长度（格）。 */
    final double length;
    /** 离地高度：{@code beamLift + beamHeadHeight/beamTailHeight}。 */
    final double height;
    /** 光束颜色 {@code 0xRRGGBB}。 */
    final int rgb;
    /**
     * {@code true} = {@link #origin} 已经是完整的世界坐标顶点（MODEL 源：灯位槽自己算好了横向偏移和
     * 高度），{@link #apex} 原样返回；{@code false} = {@link #origin} 只是车端面轨道点，y 由
     * {@link BeamGlow#startHeight} + {@link #height} 现算（GLOW 源，与光带共用）。
     */
    private final boolean absolute;

    private BeamGeom(final Vec3d origin, final Vec3d axis, final double length,
                     final double height, final int rgb, final boolean absolute) {
        this.origin = origin;
        this.axis = axis;
        this.length = length;
        this.height = height;
        this.rgb = rgb;
        this.absolute = absolute;
    }

    /**
     * @param railPoint 该车端面的轨道点（{@link TrainPose#front()} / {@link TrainPose#rear()}）
     * @param dir       该车自己的外向单位方向（<b>车身弦</b>，见 {@link BeamGlow#endDir}；含坡度）
     * @param offset    端面到灯口的距离（配置里已钳制的 head/tailOffset）
     * @param length    光束长度
     * @param height    {@code beamLift + 该端 height}
     * @param rgb       颜色
     */
    static BeamGeom of(final Vec3d railPoint, final Vec3d dir, final double offset,
                       final double length, final double height, final int rgb) {
        final Vec3d origin = new Vec3d(railPoint.x + dir.x * offset,
                railPoint.y + dir.y * offset, railPoint.z + dir.z * offset);
        return new BeamGeom(origin, dir, length, height, rgb, false);
    }

    /**
     * 顶点已经是世界坐标的几何（MODEL 源）：灯位槽把横向偏移、内缩和高度都算进了 {@code apex}，
     * 所以这里不再重算 y，也不再读地面高度。光带仍然只走 {@link #of}，因此这个工厂只属于
     * MODEL 那条路；两条路拿的是同一个 {@code BeamGeom} 类型、同一套 {@code apex/axis/length/rgb}
     * 字段，壳层绘制只认这些字段。
     */
    static BeamGeom at(final Vec3d apex, final Vec3d dir, final double length, final int rgb) {
        return new BeamGeom(apex, dir, length, 0.0D, rgb, true);
    }

    /**
     * 光柱顶点：灯口中心，y 用光带第一排顶点的同一个规则（{@link BeamGlow#startHeight} + 离地
     * 高度）。光带的 {@code t = 0} 中心线顶点就是这个点，所以两者在灯口处逐位重合。
     */
    Vec3d apex(final ClientWorld world) {
        if (absolute) {
            return origin;
        }
        return new Vec3d(origin.x, BeamGlow.startHeight(world, origin) + height, origin.z);
    }
}
