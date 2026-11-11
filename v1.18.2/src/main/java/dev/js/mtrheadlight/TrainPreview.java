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
import dev.js.mtrheadlight.clientgui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
// 1.19.2 原版不带 JOML（org.joml 是 1.19.3 才进依赖树的），换成同形的本地垫片。
import dev.js.mtrheadlight.clientgui.Matrix4f;
import dev.js.mtrheadlight.clientgui.Quaternionf;

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
 * <h2>坐标系与两个“只需改一行”的开关</h2>
 * 模型 part 的坐标就是 {@code DynamicTrainModel.partsInfo} 的 {@code PartInfo.origin}（单位：格）；
 * 光束 {@code pos} 与它在同一套数值里，所以两者必须经过<b>同一个</b>矩阵（{@link #beamRotate}），
 * 否则一翻转就会互相错开。整台车摆不正时只动下面两个开关：
 * <ul>
 *   <li>{@link #MODEL_FLIP_Y180} —— 绕 Y 轴 180°（前后对调 + 左右镜像）。</li>
 *   <li>{@link #MODEL_FLIP_Z180} —— 绕 Z 轴 180°（上下颠倒 + 左右镜像）。当前值见该常量。</li>
 * </ul>
 * 哪个开关是“对的”，不靠眼估：MTR 自己的世界渲染器
 * （{@code JonModelTrainRenderer#renderCar}）画车体前做的是
 * {@code translate(pos) · Ry(π+Θ) · Rx(π+pc)}，其中 Θ 是车身偏航、pc 是坡度；
 * 预览要的车头朝向就是 Θ = 0，此时
 * <pre>
 *   Ry(π)·Rx(π) = Rz(π)      // 两个 180° 的乘积是一次 Rz(180)，不是恒等
 * </pre>
 * 所以“和游戏里看到的一样”这件事，在 GUI 里等价于 <b>Rz(180)</b> = {@link #MODEL_FLIP_Z180}。
 * 关掉它，模型就是世界渲染器<b>反</b>过来的那副朝向 —— 也就是上下颠倒（人眼最先看出来的那一半）。
 */
final class TrainPreview {

    /** GUI 里给模型的光源等级：0xF000F0 = 全亮。用世界光照会让预览在夜里全黑。 */
    private static final int FULL_BRIGHT = 0xF000F0;
    /** 是否渲染细节（PIDS 文字 / 转向架）。true 时 render 会走 renderTextDisplays 分支。 */
    private static boolean detail = true;
    private static boolean detailFailed = false;

    /**
     * 模型空间 = MTR 资源包空间：{@code x} 横向、{@code y} 向上、{@code z} 沿车长且
     * <b>车头朝 -Z</b>（{@code docs/builtin-light-table.md}：「{@code z} 沿车长（负 = 车头端）」，
     * 那张表是从真实模型渲染出的顶点量出来的，和 {@code VehicleLights} / {@code TrainPose} 同一套坐标）。
     *
     * <p>但模型<b>不能原样</b>贴上来：MTR 的 {@code JonModelTrainRenderer#renderCar} 在
     * {@code model.render} 之前压了 {@code Ry(π+Θ)·Rx(π+pc)}，而 {@code Ry(π)·Rx(π) = Rz(π)}，
     * 也就是说模型的建模朝向相对世界转了 180°（文档里那副“上下颠倒”的画面）。
     * GUI 预览里没有这一步，必须自己补上，否则整台车倒着画、光束也跟着倒，看起来“放反了”。
     *
     * <p>两个开关都是绕轴 180°（不会把模型拉变形），一次只开一个：
     * <ul>
     *   <li>{@link #MODEL_FLIP_Y180} —— {@code Ry(π)}：<b>前后对调</b> + 左右镜像。某个包的约定
     *       真的是车头 +Z 时才用（它不改变“上下”）。</li>
     *   <li>{@link #MODEL_FLIP_Z180} —— {@code Rz(π)}：<b>上下翻转</b> + 左右镜像，
     *       等于 MTR 世界渲染器在 Θ=0 时的 {@code Ry(π)·Rx(π)}。<b>当前默认开</b>。</li>
     * </ul>
     * 两个都开 = {@code Rx(π)}（上下 + 前后一起翻），只有在包连车头都反了时才用。
     */
    private static final boolean MODEL_FLIP_Y180 = false;
    private static final boolean MODEL_FLIP_Z180 = true;
    /**
     * 光束 {@code pos[0]} 与模型 {@code x} 是否互为相反数。两边用的是同一套坐标
     * （{@code Slot.lateral} 就是模型的 {@code x - latCentre}），所以默认 {@code false}；
     * 旧版本这里是 {@code scale(-1,1,1)}，那是配合旧的 {@code Rz(180)} 才成立的补偿。
     */
    private static final boolean BEAM_MIRROR_X = false;

    /** 一个模型在视口里的默认像素密度（每格多少像素）。 */
    static final float DEFAULT_SCALE = 22.0F;
    static final float MIN_SCALE = 4.0F;
    static final float MAX_SCALE = 160.0F;
    /** 编组上限：预览最多画这么多节（再多就只看到两头，没意义）。 */
    static final int MAX_CARS = 8;
    /** 模型里量不出车长时的默认车厢间距（格）。 */
    static final double FALLBACK_PITCH = 10.0D;

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
            return fitScale(viewportHeight, 1);
        }

        /**
         * 同上，但按 {@code cars} 节的<b>整列编组</b>取景：间距由 {@link #carPitch} 给出，
         * 所以 3 节车厢时自动缩小到能看全整列。
         */
        float fitScale(final int viewportHeight, final int cars) {
            final int carCount = Math.max(1, Math.min(MAX_CARS, cars));
            final double single = this.layout == null ? 20.0D
                    : Math.max(this.layout.length, this.layout.width * 2.0D + 4.0D);
            final double span = single + (carCount - 1) * carPitch(this);
            return (float) Math.max(MIN_SCALE,
                    Math.min(MAX_SCALE, viewportHeight * 0.55D / Math.max(4.0D, span)));
        }
    }

    /**
     * 包内模型（未经 MTR 注册）也能画：模型与贴图都由 {@link CustomPack} 从包里读出来，
     * 这里只负责把它们装进普通的 {@link Subject}。贴图拿不到时退回透明贴图（看不见但能编辑）。
     */
    static Subject imported(final String id, final String pack, final Identifier texture,
                            final ModelTrainBase model, final VehicleLights.Layout layout) {
        if (model == null) {
            return null;
        }
        final Identifier resolved = texture == null
                ? new Identifier("mtr:textures/block/transparent.png") : texture;
        return new Subject(id, pack, resolved, model, layout);
    }

    /**
     * 一节车厢在编组方向上的长度（格）——多节预览的间距就是它。
     *
     * <p>依据：{@link VehicleLights.Layout#sectionLen} 是模型沿长度轴的实际几何跨度
     * （{@code span / sections}，一个 bbmodel 里塞了几节车时会自动拆开），
     * 比 {@code length} 字段（作者写在 model_properties 里的值，可能没写）更可靠：
     * 先 {@code sections>1 ? sectionLen}，再试 {@code sectionLen}，再试 {@code length}，
     * 都量不出来才用 {@link #FALLBACK_PITCH}。
     */
    static double carPitch(final Subject subject) {
        final VehicleLights.Layout layout = subject == null ? null : subject.layout;
        double pitch = 0.0D;
        if (layout != null) {
            // sectionLen == 几何跨度 / sections；一个 bbmodel 里塞了几节车时它已经是「一节」的长度。
            pitch = layout.sectionLen;
            if (!(pitch > 0.5D)) {
                pitch = layout.length;
            }
        }
        return pitch > 0.5D ? pitch : FALLBACK_PITCH;
    }

    /** 模型空间的长度轴（单位向量，只有 X / Z 两个分量）：{@code Layout.lengthAxis} 说了算。 */
    private static double[] alongAxis(final Subject subject) {
        final VehicleLights.Layout layout = subject == null ? null : subject.layout;
        final boolean alongX = layout != null && "X".equalsIgnoreCase(layout.lengthAxis);
        return alongX ? new double[]{1.0D, 0.0D} : new double[]{0.0D, 1.0D};
    }

    /**
     * 编组里每节车厢的模型空间偏移 {@code [ox, oz]}（格），以视口中心对称：
     * 1 节是 {@code {0,0}}，3 节是 {@code -pitch / 0 / +pitch}。
     *
     * <p>画车、画光束、画坐标轴手柄、拾取轴，全都从这里取同一个数组 —— 手柄和它所属的那节车
     * 不可能错位（以前这段计算在 {@link #draw} 里内联，加手柄时很容易再抄一份算出别的数）。
     */
    static double[][] carOffsets(final Subject subject, final int cars) {
        final int count = Math.max(1, Math.min(MAX_CARS, cars));
        final double pitch = carPitch(subject);
        final double[] axis = alongAxis(subject);
        final double[][] out = new double[count][2];
        for (int car = 0; car < count; car++) {
            final double offset = count == 1 ? 0.0D : (car - (count - 1) * 0.5D) * pitch;
            out[car][0] = axis[0] * offset;
            out[car][1] = axis[1] * offset;
        }
        return out;
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
            // 1.18.2 的 ResourceManager#getResource 直接返回 Resource（找不到抛 IOException），
            // 没有 1.19.2 的 Optional<Resource>；containsResource 是四个版本都在的那个探针。
            if (MinecraftClient.getInstance().getResourceManager().containsResource(id)) {
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

        /**
         * 模型空间（格）→ 视空间（格，Y 向上）。不含像素缩放和平移。
         *
         * <p>顺序是 {@code Rx(pitch)·Ry(yaw)·[翻转]}：JOML 的 {@code rotate} 是右乘，所以翻转是
         * <b>最内层</b>（先翻模型自身，再绕它转视角）。翻转永远在模型空间里做，光束
         * （{@link #beamRotate}）、拾取、拖拽全用这一个矩阵，光标不会和画面对不上。
         */
        Matrix4f rotation() {
            final Matrix4f out = new Matrix4f();
            out.rotate(new Quaternionf().rotateX((float) Math.toRadians(this.pitch)));
            out.rotate(new Quaternionf().rotateY((float) Math.toRadians(this.yaw)));
            if (MODEL_FLIP_Y180) {
                out.rotate(new Quaternionf().rotateY((float) Math.toRadians(180.0F)));
            }
            if (MODEL_FLIP_Z180) {
                out.rotate(new Quaternionf().rotateZ((float) Math.toRadians(180.0F)));
            }
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
        draw(subject, context, camera, x0, y0, x1, y1, beams, selected, 1);
    }

    /**
     * 在 {@code [x0,y0)-[x1,y1)} 里画车 + 光束线框。
     *
     * <p>多节：同一个模型按 {@link #carPitch} 的间距沿 {@link #alongAxis} 重复摆放
     * （间距 = 模型量出来的一节车长，量不到时 10 格），每节都以编组中心对称分布，
     * 并各自带一套光束线框 —— 光束是车体局部坐标，所以编组里每节车的灯位自然一致。
     * {@code carIndex / trainCars} 也照实传给模型，MTR 的模型会据此挑「头车 / 中间车 / 尾车」的部件。
     */
    static void draw(final Subject subject, final DrawContext context, final Camera camera,
                     final int x0, final int y0, final int x1, final int y1,
                     final java.util.List<Beam> beams, final int selected, final int cars) {
        draw(subject, context, camera, x0, y0, x1, y1, beams, selected, cars, false, -1);
    }

    /**
     * 同上，外加选中光束的 XYZ 坐标轴手柄。
     *
     * @param gizmo      true 时在选中光束的 {@code pos} 上画三根 2 格的坐标轴（X 红 / Y 绿 / Z 蓝）
     * @param highlightAxis 需要高亮的那一根（{@link #AXIS_X}../{@link #AXIS_Z}），-1 = 都不高亮
     */
    static void draw(final Subject subject, final DrawContext context, final Camera camera,
                     final int x0, final int y0, final int x1, final int y1,
                     final java.util.List<Beam> beams, final int selected, final int cars,
                     final boolean gizmo, final int highlightAxis) {
        final int centreX = (x0 + x1) / 2;
        final int centreY = (y0 + y1) / 2;
        final int carCount = Math.max(1, Math.min(MAX_CARS, cars));
        final double[] gizmoAt = gizmo && beams != null && selected >= 0 && selected < beams.size()
                && beams.get(selected) != null ? beams.get(selected).pos : null;

        context.enableScissor(x0, y0, x1, y1);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        final VertexConsumerProvider.Immediate immediate =
                MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers();

        if (subject == null || subject.model == null) {
            // 没有 JonModelTrainRenderer（不是动车 / 资源包写错）：直接走包围盒兜底。
            drawBounds(subject, immediate, camera, centreX, centreY, 0.0D, 0.0D);
            drawBeams(immediate, camera, centreX, centreY, beams, selected, 0.0D, 0.0D);
            if (gizmoAt != null) {
                drawAxes(immediate, camera, centreX, centreY, gizmoAt, 0.0D, 0.0D, highlightAxis);
            }
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            context.disableScissor();
            return;
        }

        final double[][] offsets = carOffsets(subject, carCount);
        for (int car = 0; car < carCount; car++) {
            final double ox = offsets[car][0];
            final double oz = offsets[car][1];

            final MatrixStack matrices = new MatrixStack();
            matrices.multiplyPositionMatrix(camera.toPixels(centreX, centreY).toMc());
            matrices.multiplyPositionMatrix(camera.rotation().toMc());
            // 在模型空间里平移（在 rotation 之后压栈 = 先转后移，等价于模型自身沿长度轴挪动）。
            matrices.translate((float) ox, 0.0F, (float) oz);
            /*
             * MTR 在编译期用的是 yarn 名（libs/MTR-...-named.jar，见 tools/remap-mtr-named.sh），
             * 所以它的 render(...) 直接收 MatrixStack / VertexConsumerProvider / Identifier，
             * 不再需要 (Object) 桥接。
             */
            boolean drawn = true;
            try {
                subject.model.render(
                        matrices,
                        immediate,
                        null,
                        subject.texture,
                        FULL_BRIGHT, 0.0F, 0.0F, false, car, carCount, true, false, false, detail, false);
            } catch (final Throwable t) {
                drawn = false;
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
            if (!drawn) {
                drawBounds(subject, immediate, camera, centreX, centreY, ox, oz);
            }
            drawBeams(immediate, camera, centreX, centreY, beams, selected, ox, oz);
            if (gizmoAt != null) {
                drawAxes(immediate, camera, centreX, centreY, gizmoAt, ox, oz, highlightAxis);
            }
        }

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        context.disableScissor();
    }

    /** 车体包围盒线框。没有模型时的兜底，坐标来自 {@code partsInfo} 的统计结果。 */
    private static void drawBounds(final Subject subject, final VertexConsumerProvider.Immediate immediate,
                                   final Camera camera, final int centreX, final int centreY,
                                   final double ox, final double oz) {
        final VehicleLights.Layout layout = subject == null ? null : subject.layout;
        final double halfLength = (layout == null ? 18.0D : Math.max(4.0D, layout.length)) * 0.5D;
        final double halfWidth = (layout == null ? 1.5D : Math.max(0.6D, layout.width * 0.5D));
        final float low = (float) (layout == null ? 0.0D : layout.minY);
        final float high = (float) (layout == null ? 3.2D : layout.maxY);
        final VertexConsumer lines = immediate.getBuffer(RenderLayer.getLines());
        final Matrix4f matrix = new Matrix4f(camera.toPixels(centreX, centreY))
                .mul(beamRotate(camera));
        matrix.translate((float) ox, 0.0F, (float) oz);
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
                                  final java.util.List<Beam> beams, final int selected,
                                  final double ox, final double oz) {
        if (beams == null || beams.isEmpty()) {
            return;
        }
        final VertexConsumer lines = immediate.getBuffer(RenderLayer.getLines());
        final Matrix4f pixels = camera.toPixels(centreX, centreY);
        final Matrix4f matrix = new Matrix4f(pixels).mul(beamRotate(camera));
        // 同一束在编组里的每一节车都要画一遍：光束坐标是车体局部的。
        matrix.translate((float) ox, 0.0F, (float) oz);
        for (int i = 0; i < beams.size(); i++) {
            final Beam beam = beams.get(i);
            if (beam == null || beam.pos == null) {
                continue;
            }
            final double[] pos = beam.pos;
            final double[] dir = direction(beam);
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
        final net.minecraft.util.math.Matrix4f mc = matrix.toMc();
        out.vertex(mc, (float) x0, (float) y0, (float) z0)
                .color(r, g, b, alpha).normal(0.0F, 1.0F, 0.0F).next();
        out.vertex(mc, (float) x1, (float) y1, (float) z1)
                .color(r, g, b, alpha).normal(0.0F, 1.0F, 0.0F).next();
    }

    /**
     * 光束用的矩阵：与模型<b>同一个</b>模型空间（x 横向 / y 向上 / z 车头 -Z），不再做 X 镜像。
     * 旧版本这里是 {@code scale(-1,1,1)}，只是配合旧的 {@code Rz(180)} 才成立 —— 那一对已经去掉了。
     *
     * <p>坐标轴手柄、拾取、轴向拖拽都走这一个矩阵，所以“车怎么翻”和“手柄在哪”永远一致。
     */
    static Matrix4f beamRotate(final Camera camera) {
        final Matrix4f out = new Matrix4f(camera.rotation());
        return BEAM_MIRROR_X ? out.scale(-1.0F, 1.0F, 1.0F) : out;
    }

    // ---------------------------------------------------------------------------------------------
    // XYZ 坐标轴手柄 + 轴向约束拖拽
    // ---------------------------------------------------------------------------------------------

    /** 手柄长度（格）：够长好点，又不至于盖住整束光。 */
    static final double AXIS_LEN = 2.0D;
    static final int AXIS_X = 0;
    static final int AXIS_Y = 1;
    static final int AXIS_Z = 2;
    /** X 红 / Y 绿 / Z 蓝。 */
    private static final float[][] AXIS_RGB = {
            {1.0F, 0.30F, 0.30F},
            {0.36F, 1.0F, 0.42F},
            {0.34F, 0.60F, 1.0F},
    };

    /** 轴的单位向量（模型空间）。 */
    static double[] axisUnit(final int axis) {
        switch (axis) {
            case AXIS_X:
                return new double[]{1.0D, 0.0D, 0.0D};
            case AXIS_Y:
                return new double[]{0.0D, 1.0D, 0.0D};
            default:
                return new double[]{0.0D, 0.0D, 1.0D};
        }
    }

    /** {@code "X"} / {@code "Y"} / {@code "Z"}，状态行和日志用。 */
    static String axisName(final int axis) {
        return axis == AXIS_X ? "X" : axis == AXIS_Y ? "Y" : "Z";
    }

    /** 画手柄时用来加粗的那根垂直轴（各轴挑一个和它不平行的）。 */
    private static double[] axisPerpendicular(final int axis) {
        return axis == AXIS_X ? axisUnit(AXIS_Z) : axisUnit(AXIS_X);
    }

    /**
     * 在 {@code origin}（+该节车的编组偏移）上画三根 2 格的坐标轴。
     *
     * <p>每根都带一个两笔的小箭头（末端 0.3 格处往回 ±0.18 格），屏幕上只是两三个像素的点，
     * 但足以看出“哪头是正方向”。高亮的那根画两遍、稍微错开 0.04 格 = 视觉加粗。
     */
    private static void drawAxes(final VertexConsumerProvider.Immediate immediate, final Camera camera,
                                 final int centreX, final int centreY, final double[] origin,
                                 final double ox, final double oz, final int highlight) {
        if (origin == null) {
            return;
        }
        final VertexConsumer lines = immediate.getBuffer(RenderLayer.getLines());
        final Matrix4f matrix = new Matrix4f(camera.toPixels(centreX, centreY))
                .mul(beamRotate(camera));
        matrix.translate((float) ox, 0.0F, (float) oz);
        for (int axis = 0; axis < 3; axis++) {
            final double[] dir = axisUnit(axis);
            final double[] perp = axisPerpendicular(axis);
            final float[] rgb = AXIS_RGB[axis];
            final boolean on = axis == highlight;
            final double bx = origin[0];
            final double by = origin[1];
            final double bz = origin[2];
            final double ex = bx + dir[0] * AXIS_LEN;
            final double ey = by + dir[1] * AXIS_LEN;
            final double ez = bz + dir[2] * AXIS_LEN;
            // 高亮：同一根线在垂直方向错开画两遍 = 加粗（线宽不可配，只能叠）。
            final double spread = on ? 0.04D : 0.0D;
            for (int pass = 0; pass < (on ? 3 : 1); pass++) {
                final double shift = pass == 0 ? 0.0D : (pass == 1 ? spread : -spread);
                // 起点稍微离开 pos，免得三根线在原点挤成一团看不清颜色。
                line(lines, matrix,
                        bx + perp[0] * shift, by + perp[1] * shift, bz + perp[2] * shift,
                        ex + perp[0] * shift, ey + perp[1] * shift, ez + perp[2] * shift,
                        rgb[0], rgb[1], rgb[2], on ? 1.0F : 0.75F);
            }
            // 箭头两笔
            final double back = 0.3D;
            final double wing = 0.18D;
            for (int side = -1; side <= 1; side += 2) {
                line(lines, matrix, ex, ey, ez,
                        ex - dir[0] * back + perp[0] * wing * side,
                        ey - dir[1] * back + perp[1] * wing * side,
                        ez - dir[2] * back + perp[2] * wing * side,
                        rgb[0], rgb[1], rgb[2], on ? 1.0F : 0.75F);
            }
        }
        immediate.draw();
    }

    /** 命中的那根轴：{@link #axis} + 属于编组里的第几节车（偏移 {@link #ox}/{@link #oz}）。 */
    static final class AxisHit {
        final int axis;
        final int car;
        final double ox;
        final double oz;
        final double distance;

        AxisHit(final int axis, final int car, final double ox, final double oz,
                final double distance) {
            this.axis = axis;
            this.car = car;
            this.ox = ox;
            this.oz = oz;
            this.distance = distance;
        }
    }

    /**
     * 手柄命中检测：和 {@link #pick} 同一套“投影到像素再算点到线段距离”，
     * 只是候选从光束换成了 {@link #AXIS_LEN} 格的三根轴（每节车各一套）。
     *
     * @return 最近的一根，或 null（阈值 ~5 px / 0.35 格，比光束的宽容度小：三根轴挤在一起，
     *         太宽就分不出点的是哪根）
     */
    static AxisHit pickAxis(final Camera camera, final double mouseX, final double mouseY,
                            final int x0, final int y0, final int x1, final int y1,
                            final double[] origin, final double[][] offsets) {
        if (origin == null || offsets == null) {
            return null;
        }
        final int centreX = (x0 + x1) / 2;
        final int centreY = (y0 + y1) / 2;
        final Matrix4f matrix = new Matrix4f(camera.toPixels(centreX, centreY))
                .mul(beamRotate(camera));
        final double threshold = Math.max(5.0D, camera.scale * 0.35D);
        AxisHit best = null;
        for (int car = 0; car < offsets.length; car++) {
            final double ox = offsets[car][0];
            final double oz = offsets[car][1];
            final double bx = origin[0] + ox;
            final double by = origin[1];
            final double bz = origin[2] + oz;
            final float ax = projectX(matrix, bx, by, bz);
            final float ay = projectY(matrix, bx, by, bz);
            for (int axis = 0; axis < 3; axis++) {
                final double[] dir = axisUnit(axis);
                final float bx2 = projectX(matrix, bx + dir[0] * AXIS_LEN,
                        by + dir[1] * AXIS_LEN, bz + dir[2] * AXIS_LEN);
                final float by2 = projectY(matrix, bx + dir[0] * AXIS_LEN,
                        by + dir[1] * AXIS_LEN, bz + dir[2] * AXIS_LEN);
                final double d = pointSegment(mouseX, mouseY, ax, ay, bx2, by2);
                if (d <= threshold && (best == null || d < best.distance)) {
                    best = new AxisHit(axis, car, ox, oz, d);
                }
            }
        }
        return best;
    }

    /**
     * <b>鼠标射线 × 轴向直线</b>的最近点参数：返回沿 {@code dir} 从 {@code base} 量起的格数。
     *
     * <p>正交相机下射线是精确可逆的，不需要 matrix invert：
     * <pre>
     *   pixel = (centre + pan) + scale · (Rx·v, −Ry·v)          // Ry 取负 = 屏幕 Y 向下
     *   ⇒ 视空间方向 (0,0,1) 的原像 = R 的第三行  → 射线方向 u
     *   ⇒ 该像素的射线起点 = vx·R第0行 + vy·R第1行 → O
     * </pre>
     * 然后解经典的两直线最近点（u·u = v·v = 1）：
     * <pre>
     *   w = base − O,  b = dir·u,  denom = 1 − b²
     *   t = (b·(u·w) − (dir·w)) / denom
     * </pre>
     * {@code t} 就是“鼠标指向轴上的哪一点”。拖拽时用 {@code t − t按下} 作为位移，
     * 所以光标和轴上的点始终贴着走，不会因为视角斜着就越拖越偏。
     *
     * @return 格数；{@code NaN} = 轴几乎正对相机（屏幕上是一个点，解退化），调用方应放弃这一帧
     */
    static double axisCoordinate(final Camera camera, final double mouseX, final double mouseY,
                                 final int x0, final int y0, final int x1, final int y1,
                                 final double[] base, final double[] dir) {
        if (base == null || dir == null) {
            return Double.NaN;
        }
        final int centreX = (x0 + x1) / 2;
        final int centreY = (y0 + y1) / 2;
        final Matrix4f rot = camera.rotation();
        // 射线方向 u = Rᵀ·(0,0,1) = R 的第三行。
        final double ux = rot.m02();
        final double uy = rot.m12();
        final double uz = rot.m22();
        // 鼠标像素 → 视平面坐标（格，Y 向上）。
        final double vx = (mouseX - (centreX + camera.panX)) / camera.scale;
        final double vy = -(mouseY - (centreY + camera.panY)) / camera.scale;
        // 视平面点 (vx, vy, 0) 的原像 = 射线起点。
        final double ox = vx * rot.m00() + vy * rot.m01();
        final double oy = vx * rot.m10() + vy * rot.m11();
        final double oz = vx * rot.m20() + vy * rot.m21();
        final double wx = base[0] - ox;
        final double wy = base[1] - oy;
        final double wz = base[2] - oz;
        final double b = dir[0] * ux + dir[1] * uy + dir[2] * uz;
        final double denom = 1.0D - b * b;
        if (denom < 1.0E-4D) {
            return Double.NaN;
        }
        final double dDotW = dir[0] * wx + dir[1] * wy + dir[2] * wz;
        final double eDotW = ux * wx + uy * wy + uz * wz;
        return (b * eDotW - dDotW) / denom;
    }

    private static float projectX(final Matrix4f m, final double x, final double y, final double z) {
        return m.m00() * (float) x + m.m10() * (float) y + m.m20() * (float) z + m.m30();
    }

    private static float projectY(final Matrix4f m, final double x, final double y, final double z) {
        return m.m01() * (float) x + m.m11() * (float) y + m.m21() * (float) z + m.m31();
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
            final double[] dir = direction(beam);
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

    /**
     * 一束光的方向（单位向量）。
     *
     * <p><b>头灯</b>：零旋转 = 车头方向 <b>-Z</b>（yaw 0），
     * {@code dir = (-sin(yaw)·cos(pitch), -sin(pitch), -cos(yaw)·cos(pitch))}。
     *
     * <p><b>尾灯</b>：<b>就是车头方向逐分量取反</b> —— 不是“再算一遍带 180° 偏航的公式”。
     * 两者的差别在带俯仰时才露出来：绕 Y 加 180° 只反 x/z，取反连 y 一起反。world 端
     * {@code ProtoBeamCone#emitSlot} 对尾端做的是 {@code dir = dir.multiply(-1)}，
     * {@code BeamGeom#axis} 就是这个向量，所以编辑器必须用<b>取反</b>这一支才对得上：
     * <pre>
     *   head: dir
     *   tail: -dir        // 零旋转 = (0, 0, +1) = 车尾
     * </pre>
     * {@code rot} 因此保持“相对该类型出厂方向的角度”：rot=[0,0,0] 的尾灯朝 +Z，而删掉这里的
     * 取反、只把基偏航写成 180° 就得到 (sin, -sin p, cos) —— pitch 的符号会反，导出到运行时
     * 就和光柱对不上。这一支是唯一的定义，别在别处再写一份尾灯公式。
     */
    static double[] direction(final Beam beam) {
        final double[] head = direction(0.0D, beam == null ? null : beam.rot);
        if (BeamSession.isHead(beam)) {
            return head;
        }
        return new double[]{-head[0], -head[1], -head[2]};
    }

    /** {@code rot = [yaw, pitch, roll]}（度）+ 额外偏航 → 单位方向。零偏航 = 车头 -Z。 */
    static double[] direction(final double baseYaw, final double[] rot) {
        final double yaw = Math.toRadians(baseYaw + (rot == null ? 0.0D : rot[0]));
        final double pitch = Math.toRadians(rot == null ? 0.0D : rot[1]);
        final double cosPitch = Math.cos(pitch);
        return new double[]{-Math.sin(yaw) * cosPitch, -Math.sin(pitch),
                -Math.cos(yaw) * cosPitch};
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
