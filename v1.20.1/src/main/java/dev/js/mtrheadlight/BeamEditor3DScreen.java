package dev.js.mtrheadlight;

import dev.js.mtrheadlight.VehicleLightOverrides.Beam;
import mtr.client.TrainClientRegistry;
import mtr.data.TransportMode;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 3D 光束编辑器（{@code /mtr_lte beam3d}）。
 *
 * <pre>
 * ┌────────────────────────────────────────────────────────────────────┐
 * │ [选择][移动][旋转][颜色]                        当前：pack·车型      │ 顶部工具条
 * ├──────────────┬──────────────────────────────────┬──────────────────┤
 * │ 车型（资源包）│                                  │ 添加光柱          │
 * │ ● pack·id    │      真实列车模型 + 光束线框      │ 复制              │
 * │   pack·id    │      左键空白处转视角             │ 删除              │
 * │   ...        │      左键拖光束 = 当前工具        │ 导出 JSON         │
 * │              │      右键拖动平移 / 滚轮缩放      │ 关闭              │
 * │              │                                  │ ── 颜色 ──        │
 * │              │                                  │ R/G/B 滑块        │
 * │              │                                  │ ── 几何 ──        │
 * │              │                                  │ 长 / 宽 / 透明    │
 * ├──────────────┴──────────────────────────────────┴──────────────────┤
 * │ 状态行 / 操作提示                                                   │
 * └────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>数据流</h2>
 * <ul>
 *   <li>车型列表 = 资源包注册表（{@link TrainClientRegistry#forEach}）∪ 世界里正在跑的列车，
 *       所以「资源包里没上车」的车型也能直接调出来编辑；世界里在用的打 {@code ●}。</li>
 *   <li>打开车型 → {@link BeamSession#open}：自动识别 + 资源包覆盖合并后的整份列表。
 *       如果 {@code config/lte_beams/<id>.json} 已经存在，优先读它（编辑器上次导出的结果）。</li>
 *   <li>拖拽 / 滑块 → 立刻写运行时层（世界里当场预览），<b>不落盘</b>。</li>
 *   <li>「导出」→ 只写当前车型的 {@code config/lte_beams/<id>.json}，并提示放进
 *       {@code assets/<namespace>/lte_beams/}。字段见 {@link BeamPackWriter}。</li>
 * </ul>
 *
 * <h2>相机的左键归属</h2>
 * 左键按在空白处 = 转视角；按在光束上 = 选中并按当前工具编辑（移动 / 旋转），选择工具下仍然转视角。
 * 右键始终平移，滚轮始终缩放 —— 这样「建模软件手感」和「能转视角」不打架。
 *
 * <p>{@link TrainPreview} 负责模型渲染与正交反投影拾取；本类只做布局、工具和数值搬运。
 */
final class BeamEditor3DScreen extends Screen {

    // --- layout ------------------------------------------------------------------------------
    private static final int TOP_H = 28;
    private static final int LEFT_W = 170;
    private static final int RIGHT_W = 206;
    private static final int PAD = 8;
    private static final int ROW_H = 18;
    private static final int PITCH = 22;
    private static final int LIST_TOP = TOP_H + 22;
    private static final int FOOTER_H = 20;

    // --- tools -------------------------------------------------------------------------------
    private static final int MODE_SELECT = 0;
    private static final int MODE_MOVE = 1;
    private static final int MODE_ROTATE = 2;
    private static final int MODE_COLOR = 3;
    private static final int MODE_COUNT = 4;
    private static final String[] MODE_KEYS = {
            "beam3d.mode.select", "beam3d.mode.move", "beam3d.mode.rotate", "beam3d.mode.color"
    };

    // --- drag kinds --------------------------------------------------------------------------
    private static final int DRAG_NONE = 0;
    private static final int DRAG_ORBIT = 1;
    private static final int DRAG_PAN = 2;
    private static final int DRAG_BEAM_MOVE = 3;
    private static final int DRAG_BEAM_ROTATE = 4;

    // --- slider kinds ------------------------------------------------------------------------
    private static final int SLIDER_R = 0;
    private static final int SLIDER_G = 1;
    private static final int SLIDER_B = 2;
    private static final int SLIDER_LENGTH = 3;
    private static final int SLIDER_WIDTH = 4;
    private static final int SLIDER_ALPHA = 5;

    private static final double POS_MIN = -16.0D;
    private static final double POS_MAX = 16.0D;
    private static final double LEN_MIN = 1.0D;
    private static final double LEN_MAX = 64.0D;
    private static final double WID_MIN = 0.5D;
    private static final double WID_MAX = 8.0D;
    private static final double ALP_MIN = 0.02D;
    private static final double ALP_MAX = 0.5D;

    private final Screen parent;
    private final TrainPreview.Camera camera = new TrainPreview.Camera();

    private String vehicleId;
    /** 已经打开过的车型，避免 resize / 返回本页时把编辑中的数值重置。 */
    private String loadedId;
    private TrainPreview.Subject subject;
    private BeamSession session;

    private int mode = MODE_MOVE;
    private int selected = -1;
    private String status = "";

    private List<VehicleRow> vehicles = List.of();
    private int scrollRow;

    private int viewX0;
    private int viewY0;
    private int viewX1;
    private int viewY1;

    private final List<ButtonWidget> modeButtons = new ArrayList<>();
    private ButtonWidget deleteButton;
    private ButtonWidget duplicateButton;

    /** 右栏里滑块/分区的纵向位置，render 时画分区标题用。 */
    private int colorSectionY = -1;
    private int geometrySectionY = -1;
    private int propertiesY = -1;
    private int hintY = -1;

    // --- drag state ---------------------------------------------------------------------------
    private int drag = DRAG_NONE;
    private int dragButton = -1;
    private double pressX;
    private double pressY;
    private double lastX;
    private double lastY;

    BeamEditor3DScreen(final Screen parent, final String vehicleId) {
        super(tr("beam3d.title"));
        this.parent = parent;
        this.vehicleId = vehicleId;
    }

    static MutableText tr(final String key, final Object... args) {
        return Text.translatable(key, args);
    }

    // ---------------------------------------------------------------------------------------------
    // 车型目录
    // ---------------------------------------------------------------------------------------------

    /** 一条车型行：id + 世界里是否正在用。 */
    private static final class VehicleRow {
        final String id;
        final boolean inUse;
        /** 贴图命名空间（懒加载，列表滚动时只算可见行）。 */
        private String pack;

        VehicleRow(final String id, final boolean inUse) {
            this.id = id;
            this.inUse = inUse;
        }

        String pack() {
            if (this.pack == null) {
                this.pack = TrainPreview.packOf(this.id);
            }
            return this.pack;
        }

        String label() {
            return (this.inUse ? "● " : "") + pack() + " · " + this.id;
        }
    }

    /**
     * 资源包里注册的全部车型 + 世界里正在跑的车型。MTR 的注册表按
     * {@link TransportMode} 分组暴露 {@code forEach}，这是唯一能枚举「包里有什么车」的公开入口。
     */
    private static List<VehicleRow> catalog() {
        final Set<String> inUse = BeamSession.inUseIds();
        final TreeSet<String> ids = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        ids.addAll(inUse);
        int failedModes = 0;
        for (final TransportMode transportMode : TransportMode.values()) {
            try {
                TrainClientRegistry.forEach(transportMode, (id, properties) -> {
                    if (id != null && !id.isBlank()) {
                        ids.add(id);
                    }
                });
            } catch (Throwable t) {
                failedModes++;
            }
        }
        if (failedModes > 0 && ids.isEmpty()) {
            LteLog.LOGGER.warn("[lte] beam3d: train registry not readable ({} modes failed)", failedModes);
        }
        final List<VehicleRow> rows = new ArrayList<>(ids.size());
        for (final String id : ids) {
            rows.add(new VehicleRow(id, containsIgnoreCase(inUse, id)));
        }
        rows.sort(Comparator.comparing((VehicleRow row) -> !row.inUse)
                .thenComparing(row -> row.id, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    private static boolean containsIgnoreCase(final Set<String> set, final String value) {
        for (final String candidate : set) {
            if (candidate.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private String defaultVehicle() {
        for (final VehicleRow row : this.vehicles) {
            if (row.inUse) {
                return row.id;
            }
        }
        return this.vehicles.isEmpty() ? null : this.vehicles.get(0).id;
    }

    // ---------------------------------------------------------------------------------------------
    // 生命周期 / 布局
    // ---------------------------------------------------------------------------------------------

    @Override
    protected void init() {
        if (this.vehicles.isEmpty()) {
            this.vehicles = catalog();
        }
        if (this.vehicleId == null || this.vehicleId.isBlank()) {
            this.vehicleId = defaultVehicle();
        }

        this.viewX0 = LEFT_W + PAD;
        this.viewY0 = TOP_H + PAD;
        this.viewX1 = Math.max(this.viewX0 + 40, this.width - RIGHT_W - PAD);
        this.viewY1 = Math.max(this.viewY0 + 40, this.height - FOOTER_H - PAD);

        if (this.loadedId == null || !this.loadedId.equals(this.vehicleId)) {
            openVehicle(this.vehicleId);
        }

        this.modeButtons.clear();
        if (this.session != null && this.selected >= this.session.size()) {
            this.selected = this.session.size() - 1;
            this.session.selected.clear();
            if (this.selected >= 0) {
                this.session.selected.add(Integer.valueOf(this.selected));
            }
        }
        buildToolbar();
        buildVehicleList();
        buildRightColumn();
    }

    /** 打开一个车型：模型 + 会话 + 相机取景，并优先读回编辑器上次导出的文件。 */
    private void openVehicle(final String id) {
        this.loadedId = id;
        this.subject = id == null ? null : TrainPreview.find(id);
        this.session = id == null ? null : BeamSession.open(id);
        this.selected = -1;
        if (this.session != null) {
            this.session.selected.clear();
        }
        if (this.subject != null) {
            this.camera.targetY = (float) this.subject.centreY();
            this.camera.scale = this.subject.fitScale(this.viewY1 - this.viewY0);
            this.camera.panX = 0.0F;
            this.camera.panY = 0.0F;
        }

        if (id != null && !id.isBlank()) {
            final List<Beam> saved = BeamPackWriter.read(id);
            if (!saved.isEmpty() && this.session != null) {
                this.session.work.clear();
                this.session.base.clear();
                for (final Beam beam : saved) {
                    final Beam materialised = BeamSession.materialise(beam, null);
                    this.session.work.add(materialised);
                    this.session.base.add(materialised.copy());
                }
                this.status = tr("beam3d.loadedFile", BeamPackWriter.fileName(id),
                        Integer.valueOf(this.session.size())).getString();
            }
        }
    }

    private void buildToolbar() {
        final int gap = 4;
        final int available = Math.max(MODE_COUNT * 48, this.width - LEFT_W - 2 * PAD);
        final int width = Math.max(48, Math.min(92, (available - gap * (MODE_COUNT - 1)) / MODE_COUNT));
        for (int i = 0; i < MODE_COUNT; i++) {
            final int index = i;
            final ButtonWidget button = this.addDrawableChild(ButtonWidget.builder(
                            tr(MODE_KEYS[i]), b -> {
                                this.mode = index;
                                this.status = tr(MODE_KEYS[index]).getString();
                            })
                    .dimensions(PAD + i * (width + gap), 5, width, ROW_H).build());
            this.modeButtons.add(button);
        }
    }

    private void buildVehicleList() {
        if (this.vehicles.isEmpty()) {
            return;
        }
        final int visible = visibleRows();
        final int last = Math.min(this.vehicles.size(), this.scrollRow + visible);
        for (int i = this.scrollRow; i < last; i++) {
            final VehicleRow row = this.vehicles.get(i);
            final int y = LIST_TOP + (i - this.scrollRow) * PITCH;
            final boolean active = this.vehicleId != null && this.vehicleId.equalsIgnoreCase(row.id);
            final String label = this.textRenderer.trimToWidth(row.label(), LEFT_W - 2 * PAD - 6);
            final ButtonWidget button = this.addDrawableChild(ButtonWidget.builder(
                            Text.literal((active ? "▸ " : "") + label), b -> {
                                this.vehicleId = row.id;
                                this.scrollRow = 0;
                                rebuild();
                            })
                    .dimensions(PAD, y, LEFT_W - 2 * PAD, ROW_H).build());
            button.active = !active;
        }
    }

    private int visibleRows() {
        return Math.max(1, (this.height - FOOTER_H - PAD - LIST_TOP) / PITCH);
    }

    /**
     * 右栏：动作按钮 → 颜色 RGB → 几何（长/宽/透明）。没有选中光束时只显示按钮和一行提示；
     * 窗口太矮（缩放很大）时省略几何滑块，保证颜色和动作按钮始终可点。
     */
    private void buildRightColumn() {
        final int x = this.width - RIGHT_W + PAD;
        final int width = RIGHT_W - 2 * PAD;
        final int buttonPitch = 20;
        int y = TOP_H + PAD;

        this.addDrawableChild(ButtonWidget.builder(tr("beam3d.add"), b -> addBeam())
                .dimensions(x, y, width, ROW_H).build());
        y += buttonPitch;
        this.duplicateButton = this.addDrawableChild(ButtonWidget.builder(tr("beam3d.duplicate"),
                        b -> duplicateBeam()).dimensions(x, y, width, ROW_H).build());
        y += buttonPitch;
        this.deleteButton = this.addDrawableChild(ButtonWidget.builder(tr("beam3d.delete"),
                        b -> deleteBeam()).dimensions(x, y, width, ROW_H).build());
        y += buttonPitch;
        final ButtonWidget typeButton = this.addDrawableChild(ButtonWidget.builder(typeLabel(),
                        b -> cycleType()).dimensions(x, y, width, ROW_H).build());
        typeButton.active = this.selected >= 0;
        y += buttonPitch;
        this.addDrawableChild(ButtonWidget.builder(tr("beam3d.export"), b -> export())
                .dimensions(x, y, width, ROW_H).build());
        y += buttonPitch;
        this.addDrawableChild(ButtonWidget.builder(tr("beam3d.close"), b -> close())
                .dimensions(x, y, width, ROW_H).build());
        y += buttonPitch + 4;

        this.colorSectionY = y;
        this.geometrySectionY = -1;
        this.propertiesY = -1;
        this.hintY = -1;
        final Beam beam = selectedBeam();
        if (beam == null) {
            this.hintY = y + 4;
            return;
        }
        y += 12;
        this.hintY = -1;
        final int sliderH = 16;
        addBeamSlider(x, y, width, sliderH, SLIDER_R);
        y += sliderH + 2;
        addBeamSlider(x, y, width, sliderH, SLIDER_G);
        y += sliderH + 2;
        addBeamSlider(x, y, width, sliderH, SLIDER_B);
        y += sliderH + 6;

        if (this.height >= 320) {
            this.geometrySectionY = y;
            y += 12;
            addBeamSlider(x, y, width, sliderH, SLIDER_LENGTH);
            y += sliderH + 2;
            addBeamSlider(x, y, width, sliderH, SLIDER_WIDTH);
            y += sliderH + 2;
            addBeamSlider(x, y, width, sliderH, SLIDER_ALPHA);
            y += sliderH + 6;
            this.propertiesY = y;
        }
    }

    private void addBeamSlider(final int x, final int y, final int w, final int h, final int kind) {
        this.addDrawableChild(new BeamSlider(x, y, w, h, kind));
    }

    private void rebuild() {
        this.clearAndInit();
    }

    // ---------------------------------------------------------------------------------------------
    // 动作
    // ---------------------------------------------------------------------------------------------

    private Beam selectedBeam() {
        return this.session == null ? null : this.session.beam(this.selected);
    }

    private void select(final int index) {
        this.selected = index;
        if (this.session != null) {
            this.session.selected.clear();
            if (index >= 0) {
                this.session.selected.add(Integer.valueOf(index));
            }
        }
    }

    private void addBeam() {
        if (this.session == null) {
            this.status = tr("beam3d.noModel").getString();
            return;
        }
        final int index = this.session.add();
        select(index);
        this.status = tr("beam3d.added", Integer.valueOf(index + 1)).getString();
        rebuild();
    }

    private void duplicateBeam() {
        if (this.session == null || this.selected < 0) {
            this.status = tr("beam3d.needSelection").getString();
            return;
        }
        final int index = this.session.duplicate(this.selected);
        if (index < 0) {
            return;
        }
        select(index);
        this.status = tr("beam3d.copied", Integer.valueOf(index + 1)).getString();
        rebuild();
    }

    private void deleteBeam() {
        if (this.session == null || this.selected < 0) {
            this.status = tr("beam3d.needSelection").getString();
            return;
        }
        select(this.selected);
        if (this.session.deleteSelected()) {
            this.selected = Math.min(this.selected, this.session.size() - 1);
            select(this.selected);
            this.status = tr("beam3d.deleted").getString();
            rebuild();
        } else {
            this.status = tr("beam3d.cannotDeleteLast").getString();
        }
    }

    private void cycleType() {
        final Beam beam = selectedBeam();
        if (beam == null) {
            return;
        }
        // 颜色保持不动：改类型时往往就是要保留这个颜色，换色有 RGB 滑块。
        beam.type = BeamSession.isHead(beam) ? "tail" : "head";
        this.session.save();
        this.status = tr("beam3d.type", BeamSession.typeName(beam.type)).getString();
        rebuild();
    }

    private MutableText typeLabel() {
        final Beam beam = selectedBeam();
        return tr("beam3d.type", BeamSession.typeName(
                beam == null ? "head" : BeamSession.typeOf(beam)));
    }

    private void export() {
        if (this.session == null) {
            return;
        }
        try {
            final Path path = BeamPackWriter.write(this.vehicleId, this.session.work);
            final List<Beam> readBack = BeamPackWriter.read(this.vehicleId);
            final int beams = this.session.size();
            if (readBack.size() != beams) {
                this.status = tr("beam3d.exportFail",
                        "read-back " + readBack.size() + " != " + beams).getString();
                return;
            }
            this.status = tr("beam3d.exported", Integer.valueOf(beams),
                    BeamPackWriter.fileName(this.vehicleId)).getString()
                    + "  " + tr("beam3d.exportHint").getString();
            LteLog.LOGGER.info("[lte] beam3d: exported {} beams -> {}", Integer.valueOf(beams), path);
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] beam3d: export failed", t);
            this.status = tr("beam3d.exportFail",
                    t.getClass().getSimpleName() + ": " + t.getMessage()).getString();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 拖拽 / 输入
    // ---------------------------------------------------------------------------------------------

    private boolean inViewport(final double x, final double y) {
        return x >= this.viewX0 && x <= this.viewX1 && y >= this.viewY0 && y <= this.viewY1;
    }

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        if (inViewport(mouseX, mouseY)) {
            this.pressX = mouseX;
            this.pressY = mouseY;
            this.lastX = mouseX;
            this.lastY = mouseY;
            this.dragButton = button;
            if (button == 0) {
                final TrainPreview.Hit hit = this.session == null ? null
                        : TrainPreview.pick(this.camera, mouseX, mouseY,
                        this.viewX0, this.viewY0, this.viewX1, this.viewY1, this.session.work);
                if (hit != null) {
                    select(hit.index);
                    if (this.mode == MODE_MOVE) {
                        this.drag = DRAG_BEAM_MOVE;
                        this.status = tr("beam3d.dragMove", Integer.valueOf(hit.index + 1)).getString();
                    } else if (this.mode == MODE_ROTATE) {
                        this.drag = DRAG_BEAM_ROTATE;
                        this.status = tr("beam3d.dragRotate", Integer.valueOf(hit.index + 1)).getString();
                    } else {
                        this.drag = DRAG_ORBIT;
                    }
                    rebuild();
                } else {
                    if (this.selected >= 0) {
                        select(-1);
                        rebuild();
                    }
                    this.drag = DRAG_ORBIT;
                }
            } else if (button == 1) {
                this.drag = DRAG_PAN;
            } else {
                this.drag = DRAG_ORBIT;
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(final double mouseX, final double mouseY, final int button,
                                final double deltaX, final double deltaY) {
        if (this.drag == DRAG_NONE || button != this.dragButton) {
            return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        }
        final double dx = mouseX - this.lastX;
        final double dy = mouseY - this.lastY;
        switch (this.drag) {
            case DRAG_ORBIT:
                this.camera.orbit(dx, dy);
                break;
            case DRAG_PAN:
                this.camera.pan(dx, dy);
                break;
            case DRAG_BEAM_MOVE:
                moveSelected(dx, dy);
                break;
            case DRAG_BEAM_ROTATE:
                rotateSelected(dx, dy);
                break;
            default:
                break;
        }
        this.lastX = mouseX;
        this.lastY = mouseY;
        return true;
    }

    @Override
    public boolean mouseReleased(final double mouseX, final double mouseY, final int button) {
        if (this.drag != DRAG_NONE && button == this.dragButton) {
            this.drag = DRAG_NONE;
            this.dragButton = -1;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /**
     * 屏幕像素位移 → 光束模型空间的位移。
     *
     * <p>正交相机下这是精确的：{@code 屏幕 = P * (rotation * flipX) * 模型}，所以把像素位移
     * 除以 {@link TrainPreview.Camera#scale}、翻回 Y 向上，再乘旋转矩阵的逆即可。
     * 用 {@code beamRotate}（含 X 镜像）而不是纯 rotation，因为光束就是用那个矩阵画的 ——
     * 这样往右拖，线就真的往右走。
     */
    private void moveSelected(final double dx, final double dy) {
        final Beam beam = selectedBeam();
        if (beam == null) {
            return;
        }
        if (beam.pos == null) {
            beam.pos = new double[]{0.0D, 0.0D, 0.0D};
        }
        final Matrix4f inverse = new Matrix4f(TrainPreview.beamRotate(this.camera)).invert();
        final Vector3f delta = inverse.transformDirection(new Vector3f(
                (float) (dx / this.camera.scale),
                (float) (-dy / this.camera.scale), 0.0F));
        beam.pos[0] = clamp(beam.pos[0] + delta.x, POS_MIN, POS_MAX);
        beam.pos[1] = clamp(beam.pos[1] + delta.y, POS_MIN, POS_MAX);
        beam.pos[2] = clamp(beam.pos[2] + delta.z, POS_MIN, POS_MAX);
        round(beam.pos);
        this.session.save();
    }

    /** 横向拖动 = 偏航，纵向拖动 = 俯仰（和建模软件里的旋转工具同向）。 */
    private void rotateSelected(final double dx, final double dy) {
        final Beam beam = selectedBeam();
        if (beam == null) {
            return;
        }
        if (beam.rot == null) {
            beam.rot = new double[]{0.0D, 0.0D, 0.0D};
        }
        beam.rot[0] = wrapDegrees(beam.rot[0] + dx * 0.7D);
        beam.rot[1] = clamp(beam.rot[1] + dy * 0.7D, -89.0D, 89.0D);
        this.session.save();
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double amount) {
        if (inViewport(mouseX, mouseY) && amount != 0.0D) {
            this.camera.zoom(amount);
            return true;
        }
        if (mouseX < LEFT_W && this.vehicles.size() > visibleRows() && amount != 0.0D) {
            this.scrollRow -= (int) Math.signum(amount);
            final int max = Math.max(0, this.vehicles.size() - visibleRows());
            this.scrollRow = Math.max(0, Math.min(max, this.scrollRow));
            rebuild();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_DELETE) {
            deleteBeam();
            return true;
        }
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_4) {
            this.mode = keyCode - GLFW.GLFW_KEY_1;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_D && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            duplicateBeam();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ---------------------------------------------------------------------------------------------
    // 渲染
    // ---------------------------------------------------------------------------------------------

    @Override
    public void render(final DrawContext context, final int mouseX, final int mouseY, final float delta) {
        context.fill(0, 0, this.width, this.height, 0xF0101010);

        // 3D 先画：之后的 2D 全部落在深度 0（最前），不会被模型压住。
        TrainPreview.draw(this.subject, context, this.camera,
                this.viewX0, this.viewY0, this.viewX1, this.viewY1,
                this.session == null ? List.of() : this.session.work, this.selected);

        context.fill(0, 0, this.width, TOP_H, 0xE0181818);
        context.fill(0, TOP_H, LEFT_W, this.height, 0xE0181818);
        context.fill(this.width - RIGHT_W, TOP_H, this.width, this.height, 0xE0181818);
        context.fill(0, this.height - FOOTER_H, this.width, this.height, 0xE0181818);

        super.render(context, mouseX, mouseY, delta);
        drawToolbarState(context);

        // 左栏标题
        final String listTitle = tr("beam3d.vehicles", Integer.valueOf(this.vehicles.size())).getString();
        context.drawTextWithShadow(this.textRenderer, Text.literal(
                        this.textRenderer.trimToWidth(listTitle, LEFT_W - 2 * PAD)),
                PAD, TOP_H + 6, 0xA0A0A0);

        // 右栏分区
        if (this.colorSectionY >= 0) {
            context.drawTextWithShadow(this.textRenderer,
                    tr("beam3d.section.color"), this.width - RIGHT_W + PAD, this.colorSectionY, 0xC0C0C0);
        }
        if (this.geometrySectionY >= 0) {
            context.drawTextWithShadow(this.textRenderer,
                    tr("beam3d.section.geometry"), this.width - RIGHT_W + PAD,
                    this.geometrySectionY, 0xC0C0C0);
        }
        if (this.session == null || this.session.size() == 0) {
            if (this.hintY >= 0) {
                context.drawTextWithShadow(this.textRenderer, tr("beam3d.emptyList"),
                        this.width - RIGHT_W + PAD, this.hintY, 0x909090);
            }
        } else if (this.hintY >= 0) {
            context.drawTextWithShadow(this.textRenderer, tr("beam3d.pickHint"),
                    this.width - RIGHT_W + PAD, this.hintY, 0x909090);
        }

        drawViewportTexts(context);
        drawFooter(context);

        if (this.deleteButton != null) {
            this.deleteButton.active = this.selected >= 0 && this.session != null
                    && this.session.size() > 1;
        }
        if (this.duplicateButton != null) {
            this.duplicateButton.active = this.selected >= 0 && this.session != null;
        }
    }

    private void drawToolbarState(final DrawContext context) {
        for (int i = 0; i < this.modeButtons.size(); i++) {
            if (i != this.mode) {
                continue;
            }
            final ButtonWidget button = this.modeButtons.get(i);
            context.fill(button.getX() - 1, button.getY() - 1,
                    button.getX() + button.getWidth() + 1, button.getY(), 0xFFFFFFFF);
            context.fill(button.getX() - 1, button.getY() + button.getHeight(),
                    button.getX() + button.getWidth() + 1, button.getY() + button.getHeight() + 1,
                    0xFFFFFFFF);
        }
    }

    private void drawViewportTexts(final DrawContext context) {
        final int left = this.viewX0 + 4;
        final int top = this.viewY0 + 4;
        if (this.subject != null) {
            final String pack = this.subject.pack + " · " + this.subject.id;
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal(this.textRenderer.trimToWidth(pack, this.viewX1 - this.viewX0 - 8)),
                    left, top, 0x9DFF9D);
        } else if (this.vehicleId != null) {
            context.drawCenteredTextWithShadow(this.textRenderer, tr("beam3d.noModel"),
                    (this.viewX0 + this.viewX1) / 2, (this.viewY0 + this.viewY1) / 2 - 8, 0xFFD37F);
            context.drawCenteredTextWithShadow(this.textRenderer, tr("beam3d.noModelHint"),
                    (this.viewX0 + this.viewX1) / 2, (this.viewY0 + this.viewY1) / 2 + 8, 0x909090);
        }

        final Beam beam = selectedBeam();
        if (beam != null) {
            final String head = tr("beam3d.selected", Integer.valueOf(this.selected + 1),
                    BeamSession.typeName(BeamSession.typeOf(beam)),
                    BeamSession.hex(beam.color)).getString();
            context.drawTextWithShadow(this.textRenderer, Text.literal(head), left, top + 12, 0xFFE08A);

            if (this.propertiesY >= 0) {
                final int x = this.width - RIGHT_W + PAD;
                context.drawTextWithShadow(this.textRenderer,
                        Text.literal(String.format(Locale.ROOT, "pos %.2f %.2f %.2f",
                                Double.valueOf(axis(beam.pos, 0)), Double.valueOf(axis(beam.pos, 1)),
                                Double.valueOf(axis(beam.pos, 2)))), x, this.propertiesY, 0xE0E0E0);
                context.drawTextWithShadow(this.textRenderer,
                        Text.literal(String.format(Locale.ROOT, "rot %.0f %.0f %.0f",
                                Double.valueOf(axis(beam.rot, 0)), Double.valueOf(axis(beam.rot, 1)),
                                Double.valueOf(axis(beam.rot, 2)))), x, this.propertiesY + 11, 0xE0E0E0);
            }
        } else if (this.session != null && this.session.size() > 0) {
            context.drawTextWithShadow(this.textRenderer, tr("beam3d.pickHint"), left, top + 12, 0x909090);
        }
    }

    private void drawFooter(final DrawContext context) {
        final String hint = this.status == null || this.status.isEmpty()
                ? tr("beam3d.hint").getString() : this.status;
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(this.textRenderer.trimToWidth(hint, this.width - 2 * PAD)),
                this.width / 2, this.height - 14, 0xC0C0C0);
    }

    // ---------------------------------------------------------------------------------------------
    // 数值滑块
    // ---------------------------------------------------------------------------------------------

    private static double axis(final double[] vector, final int index) {
        return vector == null || index >= vector.length ? 0.0D : vector[index];
    }

    private static double clamp(final double value, final double min, final double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round(final double[] vector) {
        for (int i = 0; i < vector.length; i++) {
            vector[i] = Math.round(vector[i] * 1000.0D) / 1000.0D;
        }
        return 0.0D;
    }

    private static double wrapDegrees(final double degrees) {
        double d = degrees % 360.0D;
        if (d > 180.0D) {
            d -= 360.0D;
        }
        if (d < -180.0D) {
            d += 360.0D;
        }
        return d;
    }

    private static double min(final int kind) {
        switch (kind) {
            case SLIDER_LENGTH:
                return LEN_MIN;
            case SLIDER_WIDTH:
                return WID_MIN;
            case SLIDER_ALPHA:
                return ALP_MIN;
            default:
                return 0.0D;
        }
    }

    private static double max(final int kind) {
        switch (kind) {
            case SLIDER_LENGTH:
                return LEN_MAX;
            case SLIDER_WIDTH:
                return WID_MAX;
            case SLIDER_ALPHA:
                return ALP_MAX;
            default:
                return 255.0D;
        }
    }

    private static double step(final int kind) {
        switch (kind) {
            case SLIDER_LENGTH:
            case SLIDER_WIDTH:
            case SLIDER_ALPHA:
                return 0.01D;
            default:
                return 1.0D;
        }
    }

    private static String labelKey(final int kind) {
        switch (kind) {
            case SLIDER_R:
                return "beam3d.slider.r";
            case SLIDER_G:
                return "beam3d.slider.g";
            case SLIDER_B:
                return "beam3d.slider.b";
            case SLIDER_LENGTH:
                return "beam3d.slider.length";
            case SLIDER_WIDTH:
                return "beam3d.slider.width";
            default:
                return "beam3d.slider.alpha";
        }
    }

    private double current(final int kind) {
        final Beam beam = selectedBeam();
        if (beam == null) {
            return min(kind);
        }
        switch (kind) {
            case SLIDER_R:
                return (color(beam) >> 16) & 0xFF;
            case SLIDER_G:
                return (color(beam) >> 8) & 0xFF;
            case SLIDER_B:
                return color(beam) & 0xFF;
            case SLIDER_LENGTH:
                return beam.length == null ? BeamSession.defaultLength(BeamSession.typeOf(beam))
                        : beam.length.doubleValue();
            case SLIDER_WIDTH:
                return beam.width == null ? BeamSession.defaultWidth() : beam.width.doubleValue();
            default:
                return beam.alpha == null ? BeamSession.defaultAlpha() : beam.alpha.doubleValue();
        }
    }

    private static int color(final Beam beam) {
        return beam.color == null
                ? BeamSession.defaultColor(BeamSession.typeOf(beam)) : beam.color.intValue() & 0xFFFFFF;
    }

    private void apply(final int kind, final double raw) {
        final Beam beam = selectedBeam();
        if (beam == null) {
            return;
        }
        switch (kind) {
            case SLIDER_R:
                beam.color = Integer.valueOf((color(beam) & 0x00FFFF) | ((int) Math.round(raw) << 16));
                break;
            case SLIDER_G:
                beam.color = Integer.valueOf((color(beam) & 0xFF00FF) | ((int) Math.round(raw) << 8));
                break;
            case SLIDER_B:
                beam.color = Integer.valueOf((color(beam) & 0xFFFF00) | (int) Math.round(raw));
                break;
            case SLIDER_LENGTH:
                beam.length = Double.valueOf(Math.max(LEN_MIN, Math.min(LEN_MAX, raw)));
                break;
            case SLIDER_WIDTH:
                beam.width = Double.valueOf(Math.max(WID_MIN, Math.min(WID_MAX, raw)));
                break;
            default:
                beam.alpha = Double.valueOf(Math.max(ALP_MIN, Math.min(ALP_MAX, raw)));
                break;
        }
        if (this.session != null) {
            this.session.save();
        }
    }

    private final class BeamSlider extends SliderWidget {
        private final int kind;
        private final boolean ready;

        BeamSlider(final int x, final int y, final int w, final int h, final int kind) {
            super(x, y, w, h, Text.empty(), normalize(current(kind), min(kind), max(kind)));
            this.kind = kind;
            this.ready = true;
            updateMessage();
        }

        private double actual() {
            final double raw = min(this.kind) + (max(this.kind) - min(this.kind)) * this.value;
            if (this.kind <= SLIDER_B) {
                return Math.round(raw);
            }
            final double step = step(this.kind);
            final double snapped = min(this.kind)
                    + Math.round((raw - min(this.kind)) / step) * step;
            return Math.max(min(this.kind), Math.min(max(this.kind), snapped));
        }

        @Override
        protected void updateMessage() {
            if (!this.ready) {
                return;
            }
            final String label = tr(labelKey(this.kind)).getString();
            final String value = this.kind <= SLIDER_B
                    ? String.valueOf((int) actual())
                    : String.format(Locale.ROOT, "%.2f", Double.valueOf(actual()));
            setMessage(Text.literal(label + " " + value));
        }

        @Override
        protected void applyValue() {
            if (!this.ready) {
                return;
            }
            BeamEditor3DScreen.this.apply(this.kind, actual());
        }
    }

    private static double normalize(final double value, final double min, final double max) {
        final double span = max - min;
        if (span <= 0.0D) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, (value - min) / span));
    }

    // ---------------------------------------------------------------------------------------------
    // 收尾
    // ---------------------------------------------------------------------------------------------

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }
}
