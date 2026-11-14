package dev.js.mtrheadlight;

/**
 * 三轴抓手（Blockbench 式 gizmo）的<b>纯数学</b>：步进表、量化、局部轴、环法线、翻转、90° 旋转。
 *
 * <p>这个类<b>不 import 任何 Minecraft / JOML</b>：所有公式都能被
 * {@code tools/beam-gizmo-check.js} 原样复算一遍（那是验收用的自检脚本，见类尾
 * {@link #selfCheck()} 里同一组断言）。改动这里的公式时两边要一起改。
 *
 * <h2>坐标系（和 {@code TrainPreview#direction} 同一套）</h2>
 * <pre>
 * 模型空间：x 横向 / y 向上 / z 沿车长（车头 -Z）。单位 = 格。
 * rot = [yaw, pitch, roll]（度）：
 *     head = (-sin yaw·cos pitch, -sin pitch, -cos yaw·cos pitch)
 *     tail = -head                                  // 尾灯 = 头灯方向取反
 * </pre>
 * roll 在渲染里没用（光柱是旋转对称的），但它是导出字段、也是 Z 环拧的那一个数，
 * 所以照样维护。
 *
 * <h2>三个环各自改哪一格</h2>
 * <pre>
 * 环    法线（模型空间）                 改的 rot 分量   拖动方向的手感
 * X 红  localX = (cos yaw, 0, -sin yaw)  rot[1] pitch   抬头 / 低头
 * Y 绿  (0, 1, 0)                        rot[0] yaw     左右转
 * Z 蓝  head 方向（光束自己的轴）        rot[2] roll    绕光束自转
 * </pre>
 * 每个环的法线在<b>它自己那一次拖动里是常量</b>（改 pitch 不动 yaw，改 roll 不动方向），
 * 所以拖动过程中环平面的基向量不会跳，{@code Δθ} 是连续的。
 *
 * <p>环角度的<b>符号</b>：{@link #ringSign} 给出「环平面内角度增加 → rot 分量增加」的系数。
 * 它是从公式里解出来的，不是拍的：绕 localX 转 +θ 等于 pitch <b>减</b> θ
 * （因为 head 的 pitch 是“抬头为负”），所以 X 环是 -1，Y/Z 是 +1。
 * {@link #selfCheck()} 用 Rodrigues 公式逐轴验证这一条。
 */
final class BeamGizmo {

    /** 轴下标：X = 0 / Y = 1 / Z = 2（和 {@code TrainPreview.AXIS_*} 一致）。 */
    static final int AXIS_X = 0;
    static final int AXIS_Y = 1;
    static final int AXIS_Z = 2;

    // --- 步进表 -------------------------------------------------------------------------------
    /** 默认移动步进：1/16 格。 */
    static final double MOVE_STEP = 0.0625D;
    /** Shift 精细移动：1/64 格。 */
    static final double MOVE_STEP_FINE = 0.015625D;
    /** 默认旋转步进：2.5°。 */
    static final double ROT_STEP = 2.5D;
    /** Shift 精细旋转：0.5°。 */
    static final double ROT_STEP_FINE = 0.5D;

    // --- 屏幕尺寸（px）→ 世界尺寸（格）--------------------------------------------------------
    /** 环半径（屏幕像素）：视口里看起来永远是这么大。 */
    static final double RADIUS_PX = 44.0D;
    /** 移动轴杆长度（屏幕像素）。 */
    static final double SHAFT_PX = 48.0D;
    /** 缩放极端时夹一下：太小点不到，太大盖住整台车。 */
    static final double MIN_SIZE = 0.35D;
    static final double MAX_SIZE = 32.0D;

    // --- 命中阈值（屏幕上多少像素内算点中）----------------------------------------------------
    /** 环：7px。环是 1px 线，太窄不好点，太宽会和轴抢。 */
    static final double RING_TOL_PX = 7.0D;
    /** 轴杆：6px（三根轴挤在一个点上，比环更严）。 */
    static final double AXIS_TOL_PX = 6.0D;

    /** 环被侧看时（法线几乎垂直于屏幕）平面求交会退化，改用屏幕折线；这个阈值是 |n·u|。 */
    static final double PLANE_MIN_COS = 0.15D;
    /** 折线兜底用的分段数（也是画环时的分段数，保证“画到哪就点到哪”）。 */
    static final int RING_SEGMENTS = 48;

    private BeamGizmo() {
    }

    // ---------------------------------------------------------------------------------------------
    // 步进
    // ---------------------------------------------------------------------------------------------

    static double moveStep(final boolean fine) {
        return fine ? MOVE_STEP_FINE : MOVE_STEP;
    }

    static double rotStep(final boolean fine) {
        return fine ? ROT_STEP_FINE : ROT_STEP;
    }

    /**
     * 吸附到「锚点 + k·步长」：{@code anchor + round((value − anchor) / step) · step}。
     *
     * <p>量化<b>永远相对按下的那一刻</b>，不是相对上一帧 —— 每帧各自取整会把
     * 0.02 格这种小位移整个吃掉（光束永远不动），这两个坑都踩过。
     */
    static double quantize(final double anchor, final double value, final double step) {
        if (!(step > 0.0D) || !Double.isFinite(value)) {
            return value;
        }
        return anchor + Math.round((value - anchor) / step) * step;
    }

    /** 一共走了几格 / 几个步进（状态行里的 {@code 5×1/16}）。 */
    static int steps(final double anchor, final double value, final double step) {
        if (!(step > 0.0D)) {
            return 0;
        }
        return (int) Math.round((value - anchor) / step);
    }

    /** 步长的文字形式（{@code 1/16} / {@code 1/64}），状态行用。 */
    static String stepLabel(final double step) {
        if (Math.abs(step - MOVE_STEP_FINE) < 1.0E-9D) {
            return "1/64";
        }
        if (Math.abs(step - MOVE_STEP) < 1.0E-9D) {
            return "1/16";
        }
        if (Math.abs(step - ROT_STEP_FINE) < 1.0E-9D) {
            return "0.5°";
        }
        if (Math.abs(step - ROT_STEP) < 1.0E-9D) {
            return "2.5°";
        }
        return String.valueOf(step);
    }

    // ---------------------------------------------------------------------------------------------
    // 尺寸：正交相机下「距离」就是像素密度（px/格），世界尺寸 = px / scale
    // ---------------------------------------------------------------------------------------------

    static double ringSize(final float scale) {
        return clamp(RADIUS_PX / Math.max(1.0E-3D, scale), MIN_SIZE, MAX_SIZE);
    }

    static double shaftSize(final float scale) {
        return clamp(SHAFT_PX / Math.max(1.0E-3D, scale), MIN_SIZE, MAX_SIZE);
    }

    // ---------------------------------------------------------------------------------------------
    // 轴 / 方向 / 角度
    // ---------------------------------------------------------------------------------------------

    static String nameOf(final int axis) {
        return axis == AXIS_X ? "X" : axis == AXIS_Y ? "Y" : "Z";
    }

    /** 模型空间的坐标轴单位向量（X/Y/Z）。 */
    static double[] unit(final int axis) {
        switch (axis) {
            case AXIS_X:
                return new double[]{1.0D, 0.0D, 0.0D};
            case AXIS_Y:
                return new double[]{0.0D, 1.0D, 0.0D};
            default:
                return new double[]{0.0D, 0.0D, 1.0D};
        }
    }

    /** 头灯口径的方向（尾灯自己取反，见 {@link #isTailDirection} 的说明）。 */
    static double[] headDirection(final double[] rot) {
        final double yaw = Math.toRadians(rot == null ? 0.0D : rot[0]);
        final double pitch = Math.toRadians(rot == null ? 0.0D : rot[1]);
        final double cosPitch = Math.cos(pitch);
        return new double[]{-Math.sin(yaw) * cosPitch, -Math.sin(pitch),
                -Math.cos(yaw) * cosPitch};
    }

    /**
     * {@link #headDirection} 的逆：给定一个<b>头灯口径</b>的单位向量，反解 {@code [yaw, pitch, roll]}。
     *
     * <p>pitch 只覆盖 [-90, 90]：超出这一段时解出来的是一组等价的 (180−p, yaw+180)，
     * 方向仍然完全一致（{@link #selfCheck()} 验证的是「方向一致」而不是「角度一致」）。
     *
     * @param roll 想保留的 roll（翻转 / 90° 旋转不动它）
     */
    static double[] rotFromHead(final double[] dir, final double roll) {
        final double len = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
        if (!(len > 1.0E-9D)) {
            return new double[]{0.0D, 0.0D, wrap(roll)};
        }
        final double x = dir[0] / len;
        final double y = dir[1] / len;
        final double z = dir[2] / len;
        final double pitch = Math.toDegrees(Math.asin(clamp(-y, -1.0D, 1.0D)));
        final double cosPitch = Math.cos(Math.toRadians(pitch));
        final double yaw = cosPitch < 1.0E-6D ? 0.0D : Math.toDegrees(Math.atan2(-x, -z));
        return new double[]{wrap(yaw), pitch, wrap(roll)};
    }

    /**
     * 环的法线（模型空间单位向量）—— 也是「绕哪根轴转」。
     *
     * <ul>
     *   <li>X：localX = {@code (cos yaw, 0, −sin yaw)}（偏航之后的横向轴）</li>
     *   <li>Y：{@code (0, 1, 0)}</li>
     *   <li>Z：光束自己的方向（{@link #headDirection}），所以 Z 环是「绕光束自转」</li>
     * </ul>
     */
    static double[] ringNormal(final int axis, final double[] rot) {
        switch (axis) {
            case AXIS_X: {
                final double yaw = Math.toRadians(rot == null ? 0.0D : rot[0]);
                return new double[]{Math.cos(yaw), 0.0D, -Math.sin(yaw)};
            }
            case AXIS_Y:
                return new double[]{0.0D, 1.0D, 0.0D};
            default:
                return normalise(headDirection(rot));
        }
    }

    /**
     * 环 → 它改的 {@code rot} 分量下标：<b>X 环 = pitch(1)、Y 环 = yaw(0)、Z 环 = roll(2)</b>。
     *
     * <p>注意不是「轴下标就是分量下标」：X 环拧的是俯仰，Y 环拧的是偏航。这张表是
     * {@link #ringNormal} 的配套（法线 = 绕哪根轴转，分量 = 哪个数跟着变），
     * {@link #selfCheck()} 会验证它与文档一致。
     */
    static int rotIndex(final int axis) {
        return axis == AXIS_X ? 1 : axis == AXIS_Y ? 0 : 2;
    }

    /** 环平面内角度 → rot 分量的符号（X 环 = −1，Y/Z 环 = +1，推导见类注释）。 */
    static int ringSign(final int axis) {
        return axis == AXIS_X ? -1 : 1;
    }

    /** 环平面的一组正交基：{u, v}，u ⊥ v ⊥ n。 */
    static double[][] planeBasis(final double[] normal) {
        final double[] n = normalise(normal);
        final double[] ref = Math.abs(n[1]) > 0.9D
                ? new double[]{1.0D, 0.0D, 0.0D} : new double[]{0.0D, 1.0D, 0.0D};
        final double[] u = normalise(cross(ref, n));
        final double[] v = normalise(cross(n, u));
        return new double[][]{u, v};
    }

    /** Rodrigues：把 {@code v} 绕单位轴 {@code axis} 转 {@code degrees}（右手定则）。 */
    static double[] rotate(final double[] v, final double[] axis, final double degrees) {
        final double[] k = normalise(axis);
        final double a = Math.toRadians(degrees);
        final double cos = Math.cos(a);
        final double sin = Math.sin(a);
        final double[] kxv = cross(k, v);
        final double kdv = k[0] * v[0] + k[1] * v[1] + k[2] * v[2];
        return new double[]{
                v[0] * cos + kxv[0] * sin + k[0] * kdv * (1.0D - cos),
                v[1] * cos + kxv[1] * sin + k[1] * kdv * (1.0D - cos),
                v[2] * cos + kxv[2] * sin + k[2] * kdv * (1.0D - cos),
        };
    }

    /**
     * 关于<b>过锚点、法线为模型轴</b>的平面做镜像：{@code v − 2(v·e)·e}。
     *
     * <p>翻转的是<b>方向</b>；{@code pos} 在锚点上，同一个公式作用上去是恒等（灯不会飞到
     * 地图另一头 —— 这就是「关于光束锚点的局部轴，而不是世界原点」的含义）。
     */
    static double[] mirror(final double[] v, final int axis) {
        final double[] e = unit(axis);
        final double d = 2.0D * (v[0] * e[0] + v[1] * e[1] + v[2] * e[2]);
        return new double[]{v[0] - d * e[0], v[1] - d * e[1], v[2] - d * e[2]};
    }

    /** 点 {@code p} 关于「过 {@code pivot}、法线 {@code n}」的平面镜像（pos 也用这个公式）。 */
    static double[] mirrorPoint(final double[] p, final double[] pivot, final int axis) {
        final double[] e = unit(axis);
        final double d = (p[0] - pivot[0]) * e[0] + (p[1] - pivot[1]) * e[1]
                + (p[2] - pivot[2]) * e[2];
        return new double[]{p[0] - 2.0D * d * e[0], p[1] - 2.0D * d * e[1],
                p[2] - 2.0D * d * e[2]};
    }

    // ---------------------------------------------------------------------------------------------
    // 自检（同 tools/beam-gizmo-check.js）
    // ---------------------------------------------------------------------------------------------

    /**
     * 纯数学自检，返回空串 = 全过；否则是逐条失败说明（由编辑器构造时写进日志）。
     *
     * <ol>
     *   <li>量化结果永远落在「锚点 + k·步长」上；</li>
     *   <li>某一根环的角度变化<b>只改一个 rot 分量</b>，另两个逐位不变，且方向变化等于
     *       绕该环法线的 Rodrigues 旋转；</li>
     *   <li>翻转只反一个方向分量，锚点（pos）不动；</li>
     *   <li>90° 旋转把方向转到相邻轴上（1/16 格步进不会引入斜向抖动）；</li>
     *   <li>环半径 / 轴杆长度的屏幕尺寸与缩放无关。</li>
     * </ol>
     */
    static String selfCheck() {
        final StringBuilder bad = new StringBuilder();
        final double anchorPos = 0.3125D;
        // 1) 量化：结果 = 锚点 + k·步长
        final double[] stepsToTry = {MOVE_STEP, MOVE_STEP_FINE, ROT_STEP, ROT_STEP_FINE};
        for (final double step : stepsToTry) {
            for (int k = -8; k <= 8; k++) {
                final double target = anchorPos + k * step + step * 0.31D;
                final double q = quantize(anchorPos, target, step);
                final double grid = anchorPos + Math.round((q - anchorPos) / step) * step;
                if (Math.abs(q - grid) > 1.0E-12D) {
                    bad.append("quantize off-grid: step=").append(step)
                            .append(" k=").append(k).append("; ");
                }
                if (Math.abs(q - target) > step * 0.5D + 1.0E-12D) {
                    bad.append("quantize too far: step=").append(step)
                            .append(" k=").append(k).append("; ");
                }
            }
        }
        // 2) 环：只改一个分量 + 方向与 Rodrigues 一致
        final double[] base = {20.0D, 10.0D, 5.0D};
        final double delta = 3.0D * ROT_STEP;
        if (rotIndex(AXIS_X) != 1 || rotIndex(AXIS_Y) != 0 || rotIndex(AXIS_Z) != 2) {
            bad.append("rotIndex table does not match the doc (X=pitch, Y=yaw, Z=roll); ");
        }
        for (int axis = 0; axis < 3; axis++) {
            final int component = rotIndex(axis);
            final double[] next = base.clone();
            next[component] = base[component] + ringSign(axis) * delta;
            for (int other = 0; other < 3; other++) {
                if (other != component && next[other] != base[other]) {
                    bad.append("ring ").append(nameOf(axis)).append(" leaked into rot[")
                            .append(other).append("]; ");
                }
            }
            final double[] before = headDirection(base);
            final double[] after = headDirection(next);
            final double[] expect = rotate(before, ringNormal(axis, base), delta);
            if (dist(after, expect) > 1.0E-6D) {
                bad.append("ring ").append(nameOf(axis)).append(" direction mismatch ")
                        .append(dist(after, expect)).append("; ");
            }
        }
        // 3) 翻转：只反一个分量，锚点不动
        for (int axis = 0; axis < 3; axis++) {
            final double[] dir = headDirection(base);
            final double[] flipped = mirror(dir, axis);
            for (int other = 0; other < 3; other++) {
                if (other != axis && Math.abs(flipped[other] - dir[other]) > 1.0E-12D) {
                    bad.append("mirror ").append(nameOf(axis)).append(" leaked into ")
                            .append(other).append("; ");
                }
            }
            if (Math.abs(flipped[axis] + dir[axis]) > 1.0E-12D) {
                bad.append("mirror ").append(nameOf(axis)).append(" did not negate; ");
            }
            final double[] pivot = {1.5D, 2.0D, -3.5D};
            final double[] moved = mirrorPoint(pivot, pivot, axis);
            if (dist(moved, pivot) > 1.0E-12D) {
                bad.append("mirror ").append(nameOf(axis)).append(" moved the anchor; ");
            }
            final double[] rot = rotFromHead(flipped, 0.0D);
            if (dist(headDirection(rot), flipped) > 1.0E-6D) {
                bad.append("rotFromHead round-trip failed on ").append(nameOf(axis)).append("; ");
            }
        }
        // 4) 90° 旋转：方向到相邻轴
        final double[] front = headDirection(new double[]{0.0D, 0.0D, 0.0D});
        final double[] up = rotate(front, unit(AXIS_X), 90.0D);
        if (dist(up, new double[]{0.0D, 1.0D, 0.0D}) > 1.0E-9D) {
            bad.append("rotate X 90 of -Z is ").append(fmt(up)).append(" (want +Y); ");
        }
        final double[] right = rotate(front, unit(AXIS_Y), -90.0D);
        if (dist(right, new double[]{1.0D, 0.0D, 0.0D}) > 1.0E-9D) {
            bad.append("rotate Y -90 of -Z is ").append(fmt(right)).append(" (want +X); ");
        }
        final double[] rotUp = rotFromHead(up, 0.0D);
        if (Math.abs(rotUp[1] + 90.0D) > 1.0E-6D) {
            bad.append("pitch of straight-up is ").append(rotUp[1]).append(" (want -90); ");
        }
        // 5) 屏幕尺寸恒定
        final float[] scales = {6.0F, 22.0F, 90.0F};
        for (final float scale : scales) {
            final double px = ringSize(scale) * scale;
            if (Math.abs(px - RADIUS_PX) > 1.0E-3D && ringSize(scale) > MIN_SIZE
                    && ringSize(scale) < MAX_SIZE) {
                bad.append("ring px at scale ").append(scale).append(" is ").append(px).append("; ");
            }
        }
        return bad.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // 小工具
    // ---------------------------------------------------------------------------------------------

    static double[] cross(final double[] a, final double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]};
    }

    static double[] normalise(final double[] v) {
        final double len = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return len < 1.0E-9D ? new double[]{0.0D, 0.0D, 1.0D}
                : new double[]{v[0] / len, v[1] / len, v[2] / len};
    }

    static double dist(final double[] a, final double[] b) {
        final double dx = a[0] - b[0];
        final double dy = a[1] - b[1];
        final double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static String fmt(final double[] v) {
        return String.format(java.util.Locale.ROOT, "(%.3f, %.3f, %.3f)", v[0], v[1], v[2]);
    }

    static double clamp(final double value, final double min, final double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 归到 (-180, 180]。 */
    static double wrap(final double degrees) {
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
