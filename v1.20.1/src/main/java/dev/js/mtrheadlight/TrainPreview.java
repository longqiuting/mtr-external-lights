package dev.js.mtrheadlight;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.js.mtrheadlight.VehicleLightOverrides.Beam;
import mtr.client.ClientData;
import mtr.client.TrainClientRegistry;
import mtr.client.TrainProperties;
import mtr.data.TrainClient;
import mtr.model.ModelTrainBase;
import mtr.render.JonModelTrainRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import net.minecraft.util.math.RotationAxis;

/**
 * 在 GUI 里画一辆真实的 MTR 列车模型，并把鼠标位置反投影成拾取。
 *
 * <h2>结论：可行，而且 MTR 自己就是这么做的</h2>
 * <pre>
 * mtr.screen.ResourcePackCreatorScreen
 *   public static void render(MatrixStack)
 *     ...
 *     matrices.push();
 *     matrices.translate(0, 0, -scale);              // 只是在 GUI 的 modelview 上再往前推
 *     UtilitiesClient.rotateYDegrees(matrices, 90);
 *     UtilitiesClient.rotateXDegrees(matrices, 180);
 *     UtilitiesClient.rotateY(matrices, yaw);
 *     UtilitiesClient.rotateZ(matrices, roll);
 *     creatorProperties.render(matrices, car, cars, head1IsFront, doorL, doorR, opening, light);
 *     matrices.pop();
 * </pre>
 * <b>MTR 的资源包编辑器不换投影矩阵</b>：它直接用 GUI 的正交投影，把模型塞在 GUI 像素坐标里
 * 画出来。所以"在配置界面里渲染列车模型"不是新玩法，是被上游验证过的路径。
 *
 * <p>真正的模型入口（{@code javap} 证据）：
 * <pre>
 * mtr.model.ModelTrainBase
 *   public final void render(MatrixStack, VertexConsumerProvider, NameColorDataBase, Identifier,
 *                            int light, float doorLeftX, float doorRightX, boolean doorOpening,
 *                            int carIndex, int trainCars, boolean reversed, boolean onRoute,
 *                            boolean translucentBatch, boolean detail, boolean atStation)
 * </pre>
 * 它<b>不读 world / 不读 camera / 不读 EntityRenderDispatcher</b>，只往传入的 provider 里按
 * {@code MoreRenderLayers.getExterior(getEntityCutout(tex))} 取 buffer，光源等级是参数，
 * ALWAYS_ON_LIGHTS 阶段内部写死 {@code 0xF000F0}。世界相关的逻辑全在
 * {@code JonModelTrainRenderer#renderCar}（相机相对坐标、方块光照查询）——<b>绕过 renderCar
 * 直接调 model.render 就绕开了整个世界上下文</b>。
 *
 * <h2>坐标系与两个“只需改一行”的常量</h2>
 * 模型 part 的坐标就是 {@code DynamicTrainModel.partsInfo} 的 {@code PartInfo.origin}（单位：格），
 * 和光束 {@code pos} 数值同源。但两者各有一处符号不确定（bbmodel 的 Y 朝向、光束 pos[1] 是
 * “离轨面高度”），实机上只能靠一次截图定标，所以集中成两个常量：
 * <ul>
 *   <li>{@link #MODEL_EXTRA_FLIP_X180} - 车如果上下颠倒，翻转它。</li>
 *   <li>{@link #BEAM_Y_SIGN} - 光束如果和车灯位置上下镜像，翻转它。</li>
 * </ul>
 * 除这两个常量外，其余全部是编译期确定、和渲染器同源的。
 */
final class TrainPreview {

    /** GUI 里给模型的光源等级：0xF000F0 = 全亮。用世界光照会让预览在夜里全黑。 */
    private static final int FULL_BRIGHT = 0xF000F0;
    /** 是否渲染细节（PIDS 文字 / 转向架）。true 时 render 会走 renderTextDisplays 分支。 */
    private static boolean detail = true;
    private static boolean detailFailed = false;

    /** 车上下颠倒了就翻这个（默认false：照抄 MTR 编辑器的 rotateX(180)）。 */
    private static final boolean MODEL_EXTRA_FLIP_X180 = false;
    /** 光束 pos 的 Y 和模型几何的 Y 方向相反时，翻这个。 */
    private static final float BEAM_Y_SIGN = 1.0F;

    /** 一个模型在视口里的默认像素密度（每格多少像素）。 */
    static final float DEFAULT_SCALE = 22.0F;
    static final float MIN_SCALE = 4.0F;
    static final float MAX_SCALE = 160.0F;

    private TrainPreview() {
    }

    // ---------------------------------------------------------------------------------------------
    // 取模型
    // ---------------------------------------------------------------------------------------------

    /** 一辆车画出来需要的东西。 */
    static final class Subject {
        final String id;
        /** 贴图命名空间 - MTR 没有暴露“资源包名”，这是最接近的稳定标识。 */
        final String pack;
        final Identifier texture;
        final ModelTrainBase model;
        final VehicleLights.Layout layout;

        private Subject(final String id, final String pack, final Identifier texture,
                        final ModelTrainBase model, final VehicleLights.Layout layout) {
            this.id = id;
            this.pack = pack;
            this.texture = texture;
            this.model = model;
            this.layout = layout;
        }

        double centreY() {
            return this.layout == null ? 1.1D : (this.layout.minY + this.layout.maxY) * 0.5D;
        }

        /** 按车长给一个“一开始就能看全”的像素密度。 */
        float fitScale(final int viewportHeight) {
            final double span = this.layout == null ? 20.0D
                    : Math.max(this.layout.length, this.layout.width * 2.0D + 4.0D);
            return (float) Math.max(MIN_SCALE,
                    Math.min(MAX_SCALE, viewportHeight * 0.55D / Math.max(4.0D, span)));
        }
    }

    /**
     * 找车型的模型：先看世界里在跑的列车（模型一定已加载），再退回注册表原型
     * （车开远被同步剔除后仍能编辑）。
     */
    static Subject find(final String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            for (final TrainClient train : ClientData.TRAINS) {
                if (train == null) {
                    continue;
                }
                final String tid = train.baseTrainType != null && !train.baseTrainType.isBlank()
                        ? train.baseTrainType : train.trainId;
                if (id.equalsIgnoreCase(tid)) {
                    final Subject found = of(train.trainRenderer, id);
                    if (found != null) {
                        return found;
                    }
                }
            }
        } catch (final Throwable ignored) {
            // 未进存档 / TRAINS 未同步
        }
        try {
            final TrainProperties properties = TrainClientRegistry.getTrainProperties(id);
            if (properties != null) {
                return of(properties.renderer, id);
            }
        } catch (final Throwable t) {
            LteLog.LOGGER.warn("[lte] preview: no model for {}", id, t);
        }
        return null;
    }

    private static Subject of(final Object renderer, final String id) {
        if (!(renderer instanceof JonModelTrainRenderer jon) || jon.model == null) {
            return null;
        }
        final Identifier texture = textureOf(jon.textureId);
        return new Subject(id, texture.getNamespace(), texture, jon.model,
                BeamSession.layoutOf(id));
    }

    /**
     * 只取贴图命名空间，不碰模型布局 —— 车型列表一次要画十几行，逐行
     * {@link #find} 会把每个模型都解析一遍（布局缓存之外的额外开销）。
     */
    static String packOf(final String id) {
        if (id == null || id.isBlank()) {
            return "?";
        }
        try {
            final TrainProperties properties = TrainClientRegistry.getTrainProperties(id);
            if (properties != null && properties.renderer instanceof JonModelTrainRenderer jon) {
                return textureOf(jon.textureId).getNamespace();
            }
        } catch (Throwable ignored) {
            // 注册表还没有这个 id
        }
        return "?";
    }

    /**
     * {@code JonModelTrainRenderer#resolveTexture} 在贴图缺失的分支里会读
     * {@code this.train.baseTrainType}（注册表原型的 train 是 null，直接调会 NPE），
     * 所以这里自己做同一件事：{@code textureId + ".png"}，不存在就退回透明贴图。
     */
    private static Identifier textureOf(final String textureId) {
        final Identifier transparent = new Identifier("mtr:textures/block/transparent.png");
        if (textureId == null || textureId.isBlank()) {
            return transparent;
        }
        try {
            final Identifier id = new Identifier(textureId + ".png");
            if (MinecraftClient.getInstance().getResourceManager().getResource(id).isPresent()) {
                return id;
            }
        } catch (final Throwable ignored) {
            // 非法路径（包里写错）
        }
        return transparent;
    }

    // ---------------------------------------------------------------------------------------------
    // 相机
    // ---------------------------------------------------------------------------------------------

    /**
     * 正交轨道相机。用正交而不是透视：MTR 的资源包编辑器就是正交，模型比例不失真，
     * 而且不用碰投影矩阵（动了投影就要自己清深度、还原状态，风险大得多）。
     */
    static final class Camera {
        float yaw = 34.0F;
        float pitch = 16.0F;
        float scale = DEFAULT_SCALE;
        float panX;
        float panY;
        float targetY = 1.1F;

        void orbit(final double dx, final double dy) {
            this.yaw += (float) dx * 0.6F;
            this.pitch = (float) Math.max(-89.0D, Math.min(89.0D, this.pitch + dy * 0.6D));
        }

        void pan(final double dx, final double dy) {
            this.panX += (float) dx;
            this.panY += (float) dy;
        }

        void zoom(final double amount) {
            this.scale = (float) Math.max(MIN_SCALE,
                    Math.min(MAX_SCALE, this.scale * Math.pow(1.12D, amount)));
        }

        /** 模型空间（格）→ 视空间（格，Y 向上）。不含像素缩放和平移。 */
        Matrix4f rotation() {
            final Matrix4f out = new Matrix4f();
            out.rotate(RotationAxis.POSITIVE_X.rotationDegrees(this.pitch));
            out.rotate(RotationAxis.POSITIVE_Y.rotationDegrees(this.yaw));
            out.rotate(RotationAxis.POSITIVE_Y.rotationDegrees(180.0F));
            out.rotate(RotationAxis.POSITIVE_X.rotationDegrees(
                    MODEL_EXTRA_FLIP_X180 ? 0.0F : 180.0F));
            out.translate(0.0F, -this.targetY, 0.0F);
            return out;
        }

        /** 视空间（格）→ 视口像素。屏幕 Y 向下，所以这里要翻一次。 */
        Matrix4f toPixels(final int centreX, final int centreY) {
            return new Matrix4f()
                    .translate(centreX + this.panX, centreY + this.panY, 0.0F)
                    .scale(this.scale, -this.scale, this.scale);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 绘制
    // ---------------------------------------------------------------------------------------------

    /**
     * 在 {@code [x0,y0)-[x1,y1)} 里画车 + 光束线框。
     *
     * <p>关键点：<b>不动投影矩阵</b>。GUI 的 modelview 上已经有一次 {@code translate(0,0,-11000)}
     * 把内容推进正交视锥，我们只要把自己的矩阵压上去，按像素坐标摆模型即可。
     */
    static void draw(final Subject subject, final DrawContext context, final Camera camera,
                     final int x0, final int y0, final int x1, final int y1,
                     final java.util.List<Beam> beams, final int selected) {
        final int centreX = (x0 + x1) / 2;
        final int centreY = (y0 + y1) / 2;

        context.enableScissor(x0, y0, x1, y1);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        final VertexConsumerProvider.Immediate immediate =
                MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers();

        if (subject == null || subject.model == null) {
            // 没有 JonModelTrainRenderer（不是动车 / 资源包写错）：直接走包围盒兜底。
            drawBounds(subject, immediate, camera, centreX, centreY);
            drawBeams(immediate, camera, centreX, centreY, beams, selected);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            context.disableScissor();
            return;
        }

        final MatrixStack matrices = new MatrixStack();
        matrices.multiplyPositionMatrix(camera.toPixels(centreX, centreY));
        matrices.multiplyPositionMatrix(camera.rotation());
        /*
         * MTR 的 jar 是按 <b>intermediary 名</b> 编译的（{@code class_4587} = MatrixStack、
         * class_4597 = VertexConsumerProvider、class_2960 = Identifier）。本项目同时挂 named 与
         * intermediary 的 MC jar，所以这三处必须走 (Object) 桥接，否则报“MatrixStack 无法转换为
         * class_4587”。运行期（生产客户端）两者是同一个类，桥接没有额外成本。
         */
        boolean modelDrawn = true;
        try {
            subject.model.render(
                    (net.minecraft.class_4587) (Object) matrices,
                    (net.minecraft.class_4597) (Object) immediate,
                    null,
                    (net.minecraft.class_2960) (Object) subject.texture,
                    FULL_BRIGHT, 0.0F, 0.0F, false, 0, 1, true, false, false, detail, false);
        } catch (final Throwable t) {
            modelDrawn = false;
            if (detail && !detailFailed) {
                detailFailed = true;
                detail = false;
                LteLog.LOGGER.warn("[lte] preview: detail render failed, retrying without it", t);
            } else {
                LteLog.LOGGER.warn("[lte] preview: model render failed, drawing the box instead", t);
            }
        }
        immediate.draw();

        // 降级路径：拿不到真实模型（或它在 GUI 里画不出来）时画包围盒 + 光束，编辑器仍然可用。
        if (!modelDrawn) {
            drawBounds(subject, immediate, camera, centreX, centreY);
        }
        drawBeams(immediate, camera, centreX, centreY, beams, selected);

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        context.disableScissor();
    }

    /** 车体包围盒线框。没有模型时的兜底，坐标来自 {@code partsInfo} 的统计结果。 */
    private static void drawBounds(final Subject subject, final VertexConsumerProvider.Immediate immediate,
                                   final Camera camera, final int centreX, final int centreY) {
        final VehicleLights.Layout layout = subject == null ? null : subject.layout;
        final double halfLength = (layout == null ? 18.0D : Math.max(4.0D, layout.length)) * 0.5D;
        final double halfWidth = (layout == null ? 1.5D : Math.max(0.6D, layout.width * 0.5D));
        final float low = (float) (layout == null ? 0.0D : layout.minY);
        final float high = (float) (layout == null ? 3.2D : layout.maxY);
        final VertexConsumer lines = immediate.getBuffer(RenderLayer.getLines());
        final Matrix4f matrix = new Matrix4f(camera.toPixels(centreX, centreY))
                .mul(beamRotate(camera));
        final double[][] corners = {
                {-halfWidth, -halfLength}, {halfWidth, -halfLength},
                {halfWidth, halfLength}, {-halfWidth, halfLength}
        };
        for (int i = 0; i < 4; i++) {
            final double sx = corners[i][0];
            final double sz = corners[i][1];
            final double nx = corners[(i + 1) % 4][0];
            final double nz = corners[(i + 1) % 4][1];
            line(lines, matrix, sx, low, sz, sx, high, sz, 0.55F, 0.85F, 1.0F, 0.8F);
            line(lines, matrix, sx, low, sz, nx, low, nz, 0.55F, 0.85F, 1.0F, 0.8F);
            line(lines, matrix, sx, high, sz, nx, high, nz, 0.55F, 0.85F, 1.0F, 0.8F);
        }
        immediate.draw();
    }

    /** 光束 = 起点 + 主轴 + 末端环 + 4 条母线。线框足够看清朝向，不做体积光。 */
    private static void drawBeams(final VertexConsumerProvider.Immediate immediate,
                                  final Camera camera, final int centreX, final int centreY,
                                  final java.util.List<Beam> beams, final int selected) {
        if (beams == null || beams.isEmpty()) {
            return;
        }
        final VertexConsumer lines = immediate.getBuffer(RenderLayer.getLines());
        final Matrix4f pixels = camera.toPixels(centreX, centreY);
        final Matrix4f matrix = new Matrix4f(pixels).mul(beamRotate(camera));
        for (int i = 0; i < beams.size(); i++) {
            final Beam beam = beams.get(i);
            if (beam == null || beam.pos == null) {
                continue;
            }
            final double[] pos = beam.pos;
            final double[] dir = direction(beam.rot);
            final double length = beam.length == null ? 8.0D : beam.length.doubleValue();
            final double radius = beam.width == null ? 0.4D : beam.width.doubleValue() * 0.5D;
            final int rgb = beam.color == null ? 0xFFFFFF : beam.color.intValue() & 0xFFFFFF;
            final float r = ((rgb >> 16) & 0xFF) / 255.0F;
            final float g = ((rgb >> 8) & 0xFF) / 255.0F;
            final float b = (rgb & 0xFF) / 255.0F;
            final float alpha = i == selected ? 1.0F : 0.6F;

            final double ex = pos[0] + dir[0] * length;
            final double ey = pos[1] + dir[1] * length;
            final double ez = pos[2] + dir[2] * length;
            line(lines, matrix, pos[0], pos[1], pos[2], ex, ey, ez, r, g, b, alpha);

            final double[] up = Math.abs(dir[1]) > 0.95D
                    ? new double[]{1.0D, 0.0D, 0.0D} : new double[]{0.0D, 1.0D, 0.0D};
            final double[] right = normalise(cross(dir, up));
            final double[] realUp = normalise(cross(right, dir));
            final int steps = 10;
            double px = 0.0D;
            double py = 0.0D;
            double pz = 0.0D;
            for (int step = 0; step <= steps; step++) {
                final double angle = Math.PI * 2.0D * step / steps;
                final double cx = ex + (right[0] * Math.cos(angle)
                        + realUp[0] * Math.sin(angle)) * radius;
                final double cy = ey + (right[1] * Math.cos(angle)
                        + realUp[1] * Math.sin(angle)) * radius;
                final double cz = ez + (right[2] * Math.cos(angle)
                        + realUp[2] * Math.sin(angle)) * radius;
                if (step > 0) {
                    line(lines, matrix, px, py, pz, cx, cy, cz, r, g, b, alpha);
                }
                if (step == 0 || step == 2 || step == 5 || step == 7) {
                    line(lines, matrix, pos[0], pos[1], pos[2], cx, cy, cz, r, g, b, alpha * 0.55F);
                }
                px = cx;
                py = cy;
                pz = cz;
            }
        }
        immediate.draw();
    }

    private static void line(final VertexConsumer out, final Matrix4f matrix,
                             final double x0, final double y0, final double z0,
                             final double x1, final double y1, final double z1,
                             final float r, final float g, final float b, final float alpha) {
        out.vertex(matrix, (float) x0, (float) y0, (float) z0)
                .color(r, g, b, alpha).normal(0.0F, 1.0F, 0.0F).next();
        out.vertex(matrix, (float) x1, (float) y1, (float) z1)
                .color(r, g, b, alpha).normal(0.0F, 1.0F, 0.0F).next();
    }

    /** 光束与模型只差一个 X 镜像（数据层把模型 +lateral 映射成世界 -right）± Y 号。 */
    static Matrix4f beamRotate(final Camera camera) {
        return new Matrix4f(camera.rotation()).scale(-1.0F, BEAM_Y_SIGN, 1.0F);
    }

    // ---------------------------------------------------------------------------------------------
    // 拾取
    // ---------------------------------------------------------------------------------------------

    static final class Hit {
        final int index;
        final double distance;

        Hit(final int index, final double distance) {
            this.index = index;
            this.distance = distance;
        }
    }

    /**
     * 正交投影下拾取是精确的 2D 问题：把光束两端用同一个矩阵投到像素坐标，再算点到线段的距离。
     * 没有逆投影、没有射线求交的数值误差，而且和画出来的位置逐像素一致。
     */
    static Hit pick(final Camera camera, final double mouseX, final double mouseY,
                    final int x0, final int y0, final int x1, final int y1,
                    final java.util.List<Beam> beams) {
        if (beams == null || beams.isEmpty()) {
            return null;
        }
        final int centreX = (x0 + x1) / 2;
        final int centreY = (y0 + y1) / 2;
        final Matrix4f matrix = new Matrix4f(camera.toPixels(centreX, centreY))
                .mul(beamRotate(camera));
        final double threshold = Math.max(6.0D, camera.scale * 0.45D);

        Hit best = null;
        for (int i = 0; i < beams.size(); i++) {
            final Beam beam = beams.get(i);
            if (beam == null || beam.pos == null) {
                continue;
            }
            final double[] dir = direction(beam.rot);
            final double length = beam.length == null ? 8.0D : beam.length.doubleValue();
            final float ax = matrix.m00() * (float) beam.pos[0]
                    + matrix.m10() * (float) beam.pos[1]
                    + matrix.m20() * (float) beam.pos[2] + matrix.m30();
            final float ay = matrix.m01() * (float) beam.pos[0]
                    + matrix.m11() * (float) beam.pos[1]
                    + matrix.m21() * (float) beam.pos[2] + matrix.m31();
            final double ex = beam.pos[0] + dir[0] * length;
            final double ey = beam.pos[1] + dir[1] * length;
            final double ez = beam.pos[2] + dir[2] * length;
            final float bx = matrix.m00() * (float) ex + matrix.m10() * (float) ey
                    + matrix.m20() * (float) ez + matrix.m30();
            final float by = matrix.m01() * (float) ex + matrix.m11() * (float) ey
                    + matrix.m21() * (float) ez + matrix.m31();
            final double d = pointSegment(mouseX, mouseY, ax, ay, bx, by);
            if (d <= threshold && (best == null || d < best.distance)) {
                best = new Hit(i, d);
            }
        }
        return best;
    }

    private static double pointSegment(final double px, final double py,
                                       final double ax, final double ay,
                                       final double bx, final double by) {
        final double vx = bx - ax;
        final double vy = by - ay;
        final double lengthSquared = vx * vx + vy * vy;
        final double t = lengthSquared < 1.0E-9D ? 0.0D
                : Math.max(0.0D, Math.min(1.0D,
                ((px - ax) * vx + (py - ay) * vy) / lengthSquared));
        final double dx = px - (ax + vx * t);
        final double dy = py - (ay + vy * t);
        return Math.sqrt(dx * dx + dy * dy);
    }

    // ---------------------------------------------------------------------------------------------
    // 向量小工具
    // ---------------------------------------------------------------------------------------------

    /** {@code rot = [yaw, pitch, roll]}（度）→ 单位方向。零旋转 = 车头方向 +Z。 */
    static double[] direction(final double[] rot) {
        final double yaw = Math.toRadians(rot == null ? 0.0D : rot[0]);
        final double pitch = Math.toRadians(rot == null ? 0.0D : rot[1]);
        final double cosPitch = Math.cos(pitch);
        return new double[]{-Math.sin(yaw) * cosPitch, -Math.sin(pitch),
                Math.cos(yaw) * cosPitch};
    }

    private static double[] cross(final double[] a, final double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] normalise(final double[] v) {
        final double len = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return len < 1.0E-9D ? new double[]{1.0D, 0.0D, 0.0D}
                : new double[]{v[0] / len, v[1] / len, v[2] / len};
    }
}
