package dev.js.mtrheadlight;

import dev.js.mtrheadlight.VehicleLightOverrides.Beam;

/**
 * 轴向吸附：把一束光的 {@code rot} 一键对齐到六个正交方向，外加移动时的 0.5 格步进。
 *
 * <h2>角度表（和 {@code TrainPreview#direction} 同一套公式）</h2>
 * <pre>
 * dir = (-sin(yaw)·cos(pitch), -sin(pitch), -cos(yaw)·cos(pitch))      // 头灯
 * dir = -dir(头灯)                                                     // 尾灯
 *
 * 方向      yaw    pitch    dir（头灯）     dir（尾灯）
 * 正前方      0       0     ( 0, 0, -1)     ( 0, 0, +1)   车头方向，MTR 资源包约定车头朝 -Z
 * 正后方    180       0     ( 0, 0, +1)     ( 0, 0, -1)   车尾方向 = 车头方向取反
 * 正上方      0     -90     ( 0,+1, 0)      ( 0,-1, 0)    注意 pitch 是“抬头为负”（和旋转工具同向）
 * 正下方      0     +90     ( 0,-1, 0)      ( 0,+1, 0)
 * 左        +90       0     (-1, 0, 0)      (+1, 0, 0)    车体左侧 = 模型 -X
 * 右        -90       0     (+1, 0, 0)      (-1, 0, 0)    车体右侧 = 模型 +X
 * </pre>
 *
 * <p><b>尾灯那一列是“同一个世界方向的另一份 rot”，不能直接照抄头灯的 yaw</b>：尾灯的出厂方向
 * 已经是车头取反，所以想让尾灯朝前，rot 要写 180°；想让尾灯朝上，pitch 要写 +90°（头灯是 -90°）。
 * 早期版本对两种类型写同一份 yaw，结果“点前却朝后”的尾灯就这么来的 —— 现在由
 * {@link #apply} 统一算，调用方只说要哪个世界方向。
 *
 * <p>「左 / 右」按车体算：车头朝 -Z、Y 向上时，右手定则给出车体右方 = {@code forward × up} = +X，
 * 与 world 端 {@code ProtoBeamCone#emitSlot} 里 {@code right = flat × up}、{@code apex += right·lateral}
 * 是同一个约定（{@code Slot.lateral} 就是模型的 {@code x - latCentre}）。
 *
 * <p>roll（{@code rot[2]}）一律归零：吸附是“摆正方向”，光束是旋转对称的圆锥，roll 没有意义。
 */
final class BeamSnap {

    static final int FRONT = 0;
    static final int BACK = 1;
    static final int UP = 2;
    static final int DOWN = 3;
    static final int LEFT = 4;
    static final int RIGHT = 5;
    static final int COUNT = 6;

    /** 移动吸附步长（格）：按住 Shift 时位置就近取整到它的整数倍。 */
    static final double MOVE_STEP = 0.5D;

    /** 每个方向的 <b>头灯</b> {@code [yaw, pitch]}（度），下标与 {@link #FRONT}..{@link #RIGHT} 一致。 */
    private static final double[][] ANGLES = {
            {0.0D, 0.0D},        // 前 -Z
            {180.0D, 0.0D},      // 后 +Z
            {0.0D, -90.0D},      // 上 +Y
            {0.0D, 90.0D},       // 下 -Y
            {90.0D, 0.0D},       // 左 -X
            {-90.0D, 0.0D},      // 右 +X
    };

    /** 按钮 / 状态行用的语言键。 */
    private static final String[] KEYS = {
            "beam3d.snap.front", "beam3d.snap.back", "beam3d.snap.up",
            "beam3d.snap.down", "beam3d.snap.left", "beam3d.snap.right",
    };

    private BeamSnap() {
    }

    static String key(final int dir) {
        return KEYS[dir < 0 || dir >= COUNT ? FRONT : dir];
    }

    /** 该方向的头灯 yaw（度）。尾灯的 rot 不同，见 {@link #apply}。 */
    static double yaw(final int dir) {
        return ANGLES[dir < 0 || dir >= COUNT ? FRONT : dir][0];
    }

    /** 该方向的头灯 pitch（度）。尾灯取反，见 {@link #apply}。 */
    static double pitch(final int dir) {
        return ANGLES[dir < 0 || dir >= COUNT ? FRONT : dir][1];
    }

    /** 该方向的世界单位向量（头灯口径）。 */
    static double[] direction(final int dir) {
        final double yawRad = Math.toRadians(yaw(dir));
        final double pitchRad = Math.toRadians(pitch(dir));
        final double cosPitch = Math.cos(pitchRad);
        return new double[]{-Math.sin(yawRad) * cosPitch, -Math.sin(pitchRad),
                -Math.cos(yawRad) * cosPitch};
    }

    /**
     * 把这一束的 {@code rot} 设成 <b>让它在世界里指向该方向</b> 的那组角度 ——
     * {@link TrainPreview#direction} 的逆运算，并且按类型分开算（尾灯先取反目标方向）。
     *
     * <p>只改 {@code rot}：{@code pos} / 长度 / 颜色都不动 —— 吸附是"摆方向"，不是"重新摆放"。
     *
     * @return 设进去的 rot（给状态行显示角度用）
     */
    static double[] apply(final Beam beam, final int dir) {
        final double[] target = direction(dir);
        // 尾灯的出厂方向 = 车头取反，所以“想让尾灯指向 target” 等价于“头灯指向 -target”。
        final boolean head = BeamSession.isHead(beam);
        final double tx = head ? target[0] : -target[0];
        final double ty = head ? target[1] : -target[1];
        final double tz = head ? target[2] : -target[2];
        // 由 dir = (-sin y·cos p, -sin p, -cos y·cos p) 反解：sin p = -ty，yaw = atan2(-tx, -tz)。
        final double outPitch = Math.toDegrees(Math.asin(clamp(-ty, -1.0D, 1.0D)));
        final double cosPitch = Math.cos(Math.toRadians(outPitch));
        // 正上 / 正下时 cos p = 0，yaw 无意义（把它留 0，免得 atan2 给出 180° 这种噪声）。
        final double outYaw = cosPitch < 1.0E-4D ? 0.0D
                : Math.toDegrees(Math.atan2(-tx, -tz));
        final double[] rot = new double[]{wrap(outYaw), wrap(outPitch), 0.0D};
        if (beam != null) {
            beam.rot = rot;
        }
        return rot;
    }

    /** 就近取整到 {@link #MOVE_STEP} 的整数倍（0.5 格步进）。 */
    static double stepped(final double value) {
        return Math.round(value / MOVE_STEP) * MOVE_STEP;
    }

    private static double clamp(final double value, final double min, final double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 归到 (-180, 180]。 */
    private static double wrap(final double degrees) {
        double d = degrees % 360.0D;
        if (d > 180.0D) {
            d -= 360.0D;
        }
        if (d <= -180.0D) {
            d += 360.0D;
        }
        return d;
    }
}
