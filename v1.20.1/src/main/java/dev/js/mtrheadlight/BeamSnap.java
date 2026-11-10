package dev.js.mtrheadlight;

import dev.js.mtrheadlight.VehicleLightOverrides.Beam;

/**
 * 轴向吸附：把一束光的 {@code rot} 一键对齐到六个正交方向，外加移动时的 0.5 格步进。
 *
 * <h2>角度表（和 {@code TrainPreview#direction} 同一套公式）</h2>
 * <pre>
 * dir = (-sin(yaw)·cos(pitch), -sin(pitch), -cos(yaw)·cos(pitch))
 *
 * 方向      yaw    pitch    dir
 * 正前方      0       0     ( 0, 0, -1)   车头方向，MTR 资源包约定车头朝 -Z
 * 正后方    180       0     ( 0, 0, +1)   车尾方向 = 车头方向取反
 * 正上方      0     -90     ( 0,+1, 0)    注意 pitch 是"抬头为负"（和旋转工具同向）
 * 正下方      0     +90     ( 0,-1, 0)
 * 左        +90       0     (-1, 0, 0)    车体左侧 = 模型 -X
 * 右        -90       0     (+1, 0, 0)    车体右侧 = 模型 +X
 * </pre>
 *
 * <p>「左 / 右」按车体算：车头朝 -Z、Y 向上时，右手定则给出车体右方 = {@code forward × up} = +X，
 * 与 world 端 {@code ProtoBeamCone#emitSlot} 里 {@code right = flat × up}、{@code apex += right·lateral}
 * 是同一个约定（{@code Slot.lateral} 就是模型的 {@code x - latCentre}）。
 *
 * <p>roll（{@code rot[2]}）一律归零：吸附是"摆正方向"，光束是旋转对称的圆锥，roll 没有意义。
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

    /** 每个方向的 {@code [yaw, pitch]}（度），下标与 {@link #FRONT}..{@link #RIGHT} 一致。 */
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

    static double yaw(final int dir) {
        return ANGLES[dir < 0 || dir >= COUNT ? FRONT : dir][0];
    }

    static double pitch(final int dir) {
        return ANGLES[dir < 0 || dir >= COUNT ? FRONT : dir][1];
    }

    /**
     * 把这一束的 {@code rot} 直接设成该方向的 {@code [yaw, pitch, 0]}。
     *
     * <p>只改 {@code rot}：{@code pos} / 长度 / 颜色都不动 —— 吸附是"摆方向"，不是"重新摆放"。
     *
     * @return 设进去的 rot（给状态行显示角度用）
     */
    static double[] apply(final Beam beam, final int dir) {
        final double[] rot = new double[]{yaw(dir), pitch(dir), 0.0D};
        if (beam != null) {
            beam.rot = rot;
        }
        return rot;
    }

    /** 就近取整到 {@link #MOVE_STEP} 的整数倍（0.5 格步进）。 */
    static double stepped(final double value) {
        return Math.round(value / MOVE_STEP) * MOVE_STEP;
    }
}
