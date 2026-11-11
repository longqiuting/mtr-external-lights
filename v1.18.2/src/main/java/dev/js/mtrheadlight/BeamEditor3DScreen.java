package dev.js.mtrheadlight;

import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;

import dev.js.mtrheadlight.clientgui.Widgets;

import net.minecraft.client.util.math.MatrixStack;
import dev.js.mtrheadlight.VehicleLightOverrides.Beam;
import mtr.client.TrainClientRegistry;
import mtr.data.TransportMode;
import dev.js.mtrheadlight.clientgui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import dev.js.mtrheadlight.clientgui.Matrix4f;
import dev.js.mtrheadlight.clientgui.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
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
 * ├──────────┬────────────────────────────────────────┬────────────────┤
 * │ 车型(150)│                                        │ 添加光柱(130)  │
 * │ [搜索…]  │                                        │ 复制           │
 * │ ● pack·id│        真实列车模型 + 光束线框          │ 删除           │
 * │   pack·id│        左键空白处转视角                 │ 类型           │
 * │   ...  ║ │        左键拖光束 = 当前工具            │ 导出 JSON      │
 * │        ║ │        右键拖动平移 / 滚轮缩放          │ 打开导出目录   │
 * │        ║ │                                        │ 关闭           │
 * │        ║ │                                        │ ── 颜色 ──     │
 * │        ║ │                                        │ R/G/B 滑块     │
 * │        ║ │                                        │ ── 几何 ──     │
 * │        ║ │                                        │ 长 / 宽 / 透明 │
 * ├──────────┴────────────────────────────────────────┴────────────────┤
 * │ 状态行 / 操作提示                                                  │
 * └────────────────────────────────────────────────────────────────────┘
 *   左侧 ║ 是可见的滚动条（滑轨 + 可拖滑块）；窗口变窄时两栏等比收缩，
 *   中间的 3D 视图优先保住（见 {@link #leftW()} / {@link #rightW()}）。
 * </pre>
 *
 * <h2>数据流</h2>
 * <ul>
 *   <li>车型列表 = 资源包注册表（{@link TrainClientRegistry#forEach}）∪ 世界里正在跑的列车，
 *       所以「资源包里没上车」的车型也能直接调出来编辑；世界里在用的打 {@code ●}。</li>
 *   <li>打开车型 → {@link BeamSession#open}：自动识别 + 资源包覆盖合并后的整份列表。
 *       如果桌面上的 {@code <id>.json} 已经存在，优先读它（编辑器上次导出的结果）；
 *       没有就找已加载资源包里的 {@code assets/lte/beam3D/<id>.json}，再退回旧的
 *       {@code assets/<ns>/lte_beams/<id>.json}（见 {@link BeamPackWriter}）。</li>
 *   <li>拖拽 / 滑块 → 立刻写运行时层（世界里当场预览），<b>不落盘</b>。</li>
 *   <li>「导出」→ 只写当前车型的 {@code <桌面>/<id>.json}（桌面不可用时退回
 *       {@code config/lte_beams/}），并提示放进 {@code assets/lte/beam3D/}。字段见
 *       {@link BeamPackWriter}。</li>
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
    /** 左栏基准宽（窄栏：只放车型名 + 搜索框 + 滚动条）。 */
    private static final int LEFT_W = 150;
    /** 右栏基准宽（窄栏：动作按钮 + 滑块）。 */
    private static final int RIGHT_W = 130;
    /** 窗口变窄时按这个下限收缩，宁可挤侧栏也要给 3D 视图留 {@link #MIN_VIEW_W}px。 */
    private static final int MIN_LEFT_W = 104;
    private static final int MIN_RIGHT_W = 112;
    private static final int MIN_VIEW_W = 180;
    private static final int PAD = 8;
    private static final int ROW_H = 18;
    private static final int PITCH = 22;
    private static final int SCROLL_W = 6;
    private static final int SEARCH_H = 16;
    /** 搜索框顶边（左栏标题下面一行）。 */
    private static final int SEARCH_Y = TOP_H + 18;
    /** 车型列表第一行的顶边（搜索框下面）。 */
    private static final int LIST_TOP = SEARCH_Y + SEARCH_H + 6;
    private static final int FOOTER_H = 20;
    /** 视口左下角「轴向吸附」按钮：3 列 × 2 行，40×14。 */
    private static final int SNAP_W = 40;
    private static final int SNAP_H = 14;
    private static final int SNAP_GAP = 3;

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
    /** 拖动左栏滚动条（不重建控件，只改 scrollRow）。 */
    private static final int DRAG_SCROLL = 5;
    /** 按住某根坐标轴拖动：只沿那一根轴移动（射线 × 轴线的最近点投影）。 */
    private static final int DRAG_BEAM_AXIS = 6;

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
    /** 资源包里一个车型都没有：屏幕上只画一行提示 + 关闭按钮（见 {@link #init()}）。 */
    private boolean empty;

    /** 全量车型目录（只读；搜索时过滤到 {@link #shown}）。 */
    private List<VehicleRow> vehicles = List.of();
    /** 当前显示的行（搜索过滤结果）。列表是自绘的，滚动/选中都不重建控件。 */
    private List<VehicleRow> shown = List.of();
    /** 搜索框文本（rebuild 时用它把输入恢复回去）。 */
    private String search = "";
    private TextFieldWidget searchBox;
    private int scrollRow;
    /** 滚轮/拖动滚动条时累计的小数位移，避免触控板的小步进被截断。 */
    private double scrollAccum;
    /** 按下滚动条时鼠标相对滑块顶边的偏移（拖动时保持相对位置）。 */
    private double scrollGrab;

    /** 导入的、<b>没被游戏加载</b>的资源包；null = 只用 MTR 注册表（+ 世界里在跑的车）。 */
    private CustomPack imported;
    /** 预览编组节数（1..{@link TrainPreview#MAX_CARS}），只影响预览，不影响导出的 JSON。 */
    private int carCount = 1;
    private ButtonWidget importButton;

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
    /**
     * 移动拖动的起点 + 累计像素位移。Shift 的 0.5 格步进必须以"按下时那一点"为基准重新量化，
     * 否则每帧各自取整会把 0.02 格这种小位移整个吃掉（光束永远不动）。
     */
    private double[] moveAnchor;
    private double moveAccumX;
    private double moveAccumY;
    /**
     * 轴向拖拽的状态：{@code axisAnchor} 是按下那一刻的 {@code beam.pos}，
     * {@code axisBase} 是轴线的基点（= pos + 该节车的编组偏移），{@code axisGrab} 是按下时
     * 鼠标射线落到轴线上的参数（格）。之后每帧只更新被选中的那一个分量。
     */
    private int dragAxis = -1;
    private double[] axisAnchor;
    private double[] axisBase;
    private double axisGrab = Double.NaN;
    /** 悬停在哪根坐标轴上（仅在移动模式、没在拖动时算），用来高亮。 */
    private int hoverAxis = -1;

    BeamEditor3DScreen(final Screen parent, final String vehicleId) {
        super(tr("beam3d.title"));
        this.parent = parent;
        this.vehicleId = vehicleId;
        // 车型名的 json 缓存每次开编辑器都作废重读：玩家可能刚加了个包 / 重载了资源。
        TrainNames.refresh();
        // 这一行是「指令到底有没有把屏幕装上」的唯一证据：构造器跑了却看不到界面，说明是渲染/
        // 布局侧的问题；连这一行都没有，说明 setScreen 就没走到（见 Commands#openBeamEditor）。
        LteLog.LOGGER.info("[lte] beam3d: vehicles={} vehicleId={} loaded={}",
                Integer.valueOf(this.vehicles.size()), describe(this.vehicleId),
                describe(this.loadedId));
    }

    /** 日志里区分「null」和空串，两者在 catalog 里含义不同。 */
    private static String describe(final String value) {
        return value == null ? "null" : (value.isBlank() ? "\"\"" : value);
    }

    static MutableText tr(final String key, final Object... args) {
        return new TranslatableText(key, args);
    }

    // ---------------------------------------------------------------------------------------------
    // 车型目录
    // ---------------------------------------------------------------------------------------------

    /**
     * 一条车型行：名字（资源包里的 {@code name}）+ 世界里有没在用。
     *
     * <p>列表显示<b>名字</b>而不是内部 id：MTR 的 {@code mtr_custom_resources.json} 里
     * {@code custom_trains.<key>.name} 就是车厂里看到的那一行，注册 id 则是
     * {@code mtr_custom_train_<key>}（见 {@link TrainNames}）。
     * 搜索同时匹配名字和 id，所以按 id 找车的习惯不受影响。
     */
    private static final class VehicleRow {
        final String id;
        final boolean inUse;
        /** 来自导入包（不在 MTR 注册表里）时为该车型，null = 注册表车型。 */
        final CustomPack.Train imported;
        /** 贴图命名空间 / 名字（懒加载，列表滚动时只算可见行）。 */
        private String pack;
        private String name;

        VehicleRow(final String id, final boolean inUse) {
            this(id, inUse, null);
        }

        VehicleRow(final String id, final boolean inUse, final CustomPack.Train imported) {
            this.id = id;
            this.inUse = inUse;
            this.imported = imported;
        }

        String pack() {
            if (this.pack == null) {
                this.pack = this.imported != null ? this.imported.pack.label
                        : TrainPreview.packOf(this.id);
            }
            return this.pack;
        }

        /** 游戏里显示的名字（资源包 name → 语言表 → 内部 id）。 */
        String name() {
            if (this.name == null) {
                this.name = this.imported != null ? this.imported.name : TrainNames.of(this.id);
            }
            return this.name;
        }

        String label() {
            return (this.inUse ? "● " : "") + name();
        }
    }

    /**
     * 资源包里注册的全部车型 + 世界里正在跑的车型 + {@code imported} 里导入的车型。
     * MTR 的注册表按 {@link TransportMode} 分组暴露 {@code forEach}，这是唯一能枚举
     * 「包里有什么车」的公开入口；没被加载的包只能靠 {@link CustomPack} 自己读。
     */
    private static List<VehicleRow> catalog(final CustomPack imported) {
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
            // 同 id 的车型如果来自导入包，就挂上包引用：打开时用包内模型、导出写进包里。
            final CustomPack.Train train = imported == null ? null : imported.train(id);
            rows.add(new VehicleRow(id, containsIgnoreCase(inUse, id), train));
        }
        if (imported != null) {
            for (final CustomPack.Train train : imported.trains()) {
                if (!ids.contains(train.id)) {
                    rows.add(new VehicleRow(train.id, containsIgnoreCase(inUse, train.id), train));
                }
            }
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

    /** 世界里正在跑的车型数（空目录日志用；读不到就是 0）。 */
    private static int worldTrainCount() {
        try {
            return BeamSession.inUseIds().size();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * {@code disableBuiltInTrains} 开着时，MTR 内置车型会被 {@code BuiltInTrains#prune} 从
     * {@code TrainClientRegistry} 里删掉 —— 而这个编辑器的车型目录正是从那个注册表枚举的。
     * 空目录时把这条写进日志，省得玩家以为资源包坏了。
     */
    private static boolean builtinsPruned() {
        try {
            final HeadlightConfig cfg = MtrHeadlightClient.config();
            return cfg != null && cfg.disableBuiltInTrains;
        } catch (Throwable t) {
            return false;
        }
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
            this.vehicles = catalog(this.imported);
        }
        LteLog.LOGGER.info("[lte] beam3d: vehicles={} vehicleId={} loaded={}",
                Integer.valueOf(this.vehicles.size()), describe(this.vehicleId),
                describe(this.loadedId));
        if (this.vehicles.isEmpty() && (this.vehicleId == null || this.vehicleId.isBlank())) {
            // 没有车型时不能静默 return：那样屏幕上只剩背景色 + 顶栏，看起来就像「开了个空窗」。
            // 这里改成明确的一行提示 + 一个关闭按钮，至少能确认屏幕本身是活的。
            // 显式给了车型 id 时不受影响：注册表被 disableBuiltInTrains 裁空 / 枚举失败时，
            // /mtr_lte beam3d <id> 仍然要能把编辑器打开（列表为空，但添加/导出照常）。
            LteLog.LOGGER.warn("[lte] beam3d: no vehicles (pack=none builtinPruned={} trainsInWorld={})",
                    Boolean.valueOf(builtinsPruned()), Integer.valueOf(worldTrainCount()));
            this.empty = true;
            this.addDrawableChild(Widgets.button(tr("beam3d.close"), b -> close())
                    .dimensions(this.width / 2 - 60, this.height / 2 + 30, 120, ROW_H).build());
            // 「一个车型都没有」正是最需要导入的时候（包没启用 / 被裁空），所以这里也给入口。
            this.addDrawableChild(Widgets.button(tr("beam3d.import"), b -> openImport())
                    .dimensions(this.width / 2 - 90, this.height / 2 + 54, 180, ROW_H).build());
            return;
        }
        this.empty = false;
        if (this.vehicleId == null || this.vehicleId.isBlank()) {
            this.vehicleId = defaultVehicle();
        }

        this.viewX0 = leftW() + PAD;
        this.viewY0 = TOP_H + PAD;
        this.viewX1 = Math.max(this.viewX0 + 40, this.width - rightW() - PAD);
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
        buildVehicleSearch();
        buildImportBar();
        applyFilter();
        buildRightColumn();
    }

    /**
     * 左栏最下方：「导入资源包」。
     *
     * <p>导入的是<b>游戏没加载</b>的包（zip / 文件夹），编辑器临时读它的
     * {@code mtr_custom_resources.json} 与模型，把车型加进列表；导出时写回<b>包内</b>
     * {@code assets/<ns>/beam3D/<id>.json}（zip 不改，见 {@link CustomPack}）。
     */
    private void buildImportBar() {
        final int width = Math.max(40, leftW() - 2 * PAD);
        final int y = this.height - FOOTER_H - PAD - ROW_H;
        final String label = this.imported == null
                ? tr("beam3d.import").getString()
                : tr("beam3d.imported", this.imported.label).getString();
        this.importButton = this.addDrawableChild(Widgets.button(
                        new LiteralText(this.textRenderer.trimToWidth(label, width - 6)),
                        b -> openImport())
                .dimensions(PAD, y, width, ROW_H).build());
    }

    /** 打开「导入资源包」页；关掉它以后回到本页（不丢当前车型）。 */
    private void openImport() {
        if (this.client == null) {
            return;
        }
        this.client.setScreen(new PackImportScreen(this, this::acceptImport));
    }

    /** 导入成功：换目录、优先打开包里第一辆车，并让 {@link #openVehicle} 走包内模型。 */
    private void acceptImport(final CustomPack pack) {
        if (pack == null) {
            return;
        }
        if (this.imported != null && this.imported != pack) {
            this.imported.release();
        }
        this.imported = pack;
        this.vehicles = catalog(pack);
        this.shown = this.vehicles;
        this.search = "";
        this.scrollRow = 0;
        this.loadedId = null;
        final CustomPack.Train first = pack.trains().isEmpty() ? null : pack.trains().get(0);
        if (first != null) {
            this.vehicleId = first.id;
        }
        this.status = tr("beam3d.import.done", pack.label,
                Integer.valueOf(pack.trains().size())).getString();
        LteLog.LOGGER.info("[lte] beam3d: imported {} ({} trains)", pack,
                Integer.valueOf(pack.trains().size()));
        rebuild();
    }

    // ---------------------------------------------------------------------------------------------
    // 左右栏宽度：窗口够宽就用基准窄栏；不够时两栏等比收缩，优先保住中间的 3D 视图
    // ---------------------------------------------------------------------------------------------

    /** 左栏实际宽度：{@code LEFT_W} 减去窗口溢出的一半，不低于 {@code MIN_LEFT_W}。 */
    private int leftW() {
        final int overflow = Math.max(0, LEFT_W + RIGHT_W + MIN_VIEW_W - this.width);
        return Math.max(MIN_LEFT_W, LEFT_W - (overflow + 1) / 2);
    }

    /** 右栏实际宽度：与左栏对称收缩，不低于 {@code MIN_RIGHT_W}。 */
    private int rightW() {
        final int overflow = Math.max(0, LEFT_W + RIGHT_W + MIN_VIEW_W - this.width);
        return Math.max(MIN_RIGHT_W, RIGHT_W - overflow / 2);
    }

    /**
     * 打开一个车型：模型 + 会话 + 相机取景，并优先读回编辑器上次导出的文件。
     *
     * <p>车型来自导入包（不在注册表里）时走 {@link CustomPack}：模型由包内
     * {@code model / model_properties} 现场构造成 {@link mtr.client.DynamicTrainModel}，
     * 布局也从它算（{@link TrainPreview#imported}）。光束底稿同样来自包内的
     * {@code assets/<ns>/beam3D/<id>.json}（旧路径 {@code assets/<ns>/lte_beams/} 也认）。
     */
    private void openVehicle(final String id) {
        this.loadedId = id;
        final CustomPack.Train train = this.imported == null ? null : this.imported.train(id);
        if (train != null) {
            final mtr.model.ModelTrainBase model = train.model();
            final mtr.client.DynamicTrainModel dynamic = train.dynamic();
            final VehicleLights.Layout layout = dynamic == null
                    ? null : VehicleLights.layoutFor(dynamic, 0);
            this.subject = model == null ? null
                    : TrainPreview.imported(train.id, train.pack.label, train.texture(), model, layout);
            this.session = BeamSession.open(id, layout);
            if (model == null) {
                LteLog.LOGGER.warn("[lte] beam3d: imported model unavailable for {} ({})",
                        id, train.error());
            }
        } else {
            this.subject = id == null ? null : TrainPreview.find(id);
            this.session = id == null ? null : BeamSession.open(id);
        }
        this.selected = -1;
        if (this.session != null) {
            this.session.selected.clear();
        }
        if (this.subject != null) {
            this.camera.targetY = (float) this.subject.centreY();
            this.camera.scale = this.subject.fitScale(this.viewY1 - this.viewY0, this.carCount);
            this.camera.panX = 0.0F;
            this.camera.panY = 0.0F;
        }

        if (id != null && !id.isBlank()) {
            final List<Beam> saved = train != null ? this.imported.readBeams(train) : BeamPackWriter.read(id);
            if (!saved.isEmpty() && this.session != null) {
                this.session.work.clear();
                this.session.base.clear();
                for (final Beam beam : saved) {
                    final Beam materialised = BeamSession.materialise(beam, null);
                    this.session.work.add(materialised);
                    this.session.base.add(materialised.copy());
                }
                this.status = tr("beam3d.loadedFile",
                        train != null
                                ? (train.loadedFrom == null ? train.beamPath() : train.loadedFrom)
                                : BeamPackWriter.fileName(id),
                        Integer.valueOf(this.session.size())).getString();
            }
        }
    }

    private void buildToolbar() {
        final int gap = 4;
        final int available = Math.max(MODE_COUNT * 48, this.width - leftW() - 2 * PAD);
        final int width = Math.max(48, Math.min(92, (available - gap * (MODE_COUNT - 1)) / MODE_COUNT));
        for (int i = 0; i < MODE_COUNT; i++) {
            final int index = i;
            final ButtonWidget button = this.addDrawableChild(Widgets.button(
                            tr(MODE_KEYS[i]), b -> {
                                this.mode = index;
                                this.status = tr(MODE_KEYS[index]).getString();
                            })
                    .dimensions(PAD + i * (width + gap), 5, width, ROW_H).build());
            this.modeButtons.add(button);
        }
    }

    /**
     * 左栏搜索框（按车型 id 过滤，大小写不敏感）。文本框是唯一的真实控件；
     * 车型行是自绘的（见 {@link #drawVehicleList}），所以选中/滚动都不会重建控件 ——
     * 这既保住了滚动位置，也不会把输入焦点从搜索框上抢走。
     */
    private void buildVehicleSearch() {
        final int w = Math.max(40, leftW() - 2 * PAD);
        final TextFieldWidget box = new TextFieldWidget(this.textRenderer, PAD, SEARCH_Y, w, SEARCH_H,
                tr("beam3d.search"));
        box.setMaxLength(64);
        // setText 会触发 changedListener，所以先写文本再挂监听，避免 init 时把滚动位置清掉。
        box.setText(this.search == null ? "" : this.search);
        box.setChangedListener(value -> {
            this.search = value == null ? "" : value;
            this.scrollRow = 0;
            applyFilter();
        });
        this.searchBox = box;
        this.addDrawableChild(box);
    }

    /**
     * 按 {@link #search} 过滤车型目录到 {@link #shown}，并把滚动位置夹回合法范围。
     *
     * <p>同时匹配<b>名字</b>（资源包 {@code name}）和 <b>id</b>：用户按车厂里的名字搜，
     * 或者按 {@code /mtr_lte beam3d <id>} 用的 id 搜，都能命中。
     */
    private void applyFilter() {
        final String query = this.search == null ? "" : this.search.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            this.shown = this.vehicles;
        } else {
            final List<VehicleRow> hits = new ArrayList<>();
            for (final VehicleRow row : this.vehicles) {
                if (row.id.toLowerCase(Locale.ROOT).contains(query)
                        || row.name().toLowerCase(Locale.ROOT).contains(query)
                        || row.pack().toLowerCase(Locale.ROOT).contains(query)) {
                    hits.add(row);
                }
            }
            this.shown = hits;
        }
        clampScroll();
    }

    /** 滚动位置夹到 [0, total-visible]，列表为空时归零。 */
    private void clampScroll() {
        final int max = Math.max(0, this.shown.size() - visibleRows());
        if (this.scrollRow > max) {
            this.scrollRow = max;
        }
        if (this.scrollRow < 0) {
            this.scrollRow = 0;
        }
    }

    /** 列表区域的可用宽度（右侧留出滚动条的位置）。 */
    private int rowW() {
        return Math.max(24, leftW() - 2 * PAD - SCROLL_W - 2);
    }

    private int listBottom() {
        return LIST_TOP + visibleRows() * PITCH - 4;
    }

    /** 鼠标 y → {@link #shown} 里的行号（不在列表区域内返回 -1）。 */
    private int rowAt(final double mouseX, final double mouseY) {
        if (mouseX < PAD || mouseX > PAD + rowW()) {
            return -1;
        }
        if (mouseY < LIST_TOP || mouseY >= LIST_TOP + visibleRows() * PITCH) {
            return -1;
        }
        final int index = this.scrollRow + (int) ((mouseY - LIST_TOP) / PITCH);
        return index >= 0 && index < this.shown.size() ? index : -1;
    }

    /**
     * 滚动条的几何：{trackTop, trackLen, thumbTop, thumbLen}。列表不需要滚动时返回 null。
     * 绘制、命中检测、拖动都用这一份，保证三者一致。
     */
    private int[] scrollbar() {
        final int total = this.shown.size();
        final int visible = visibleRows();
        if (total <= visible) {
            return null;
        }
        final int trackTop = LIST_TOP;
        final int trackLen = Math.max(1, listBottom() - trackTop);
        final int thumbLen = Math.max(14, (int) ((long) trackLen * visible / total));
        final int max = Math.max(1, total - visible);
        final int thumbTop = trackTop
                + (int) Math.round((double) (trackLen - thumbLen) * this.scrollRow / max);
        return new int[]{trackTop, trackLen, thumbTop, thumbLen};
    }

    private int scrollbarX() {
        return leftW() - PAD - SCROLL_W;
    }

    /** 选中某个车型：只换车型，不动 scrollRow，也不再重建左栏的滚动位置。 */
    private void selectVehicle(final int index) {
        if (index < 0 || index >= this.shown.size()) {
            return;
        }
        final VehicleRow row = this.shown.get(index);
        if (this.vehicleId != null && this.vehicleId.equalsIgnoreCase(row.id)) {
            return;
        }
        this.vehicleId = row.id;
        // 点列表行 = 离开输入状态：不主动清焦点的话，之后的 1-4/Del 会继续被文本框吃掉。
        if (this.searchBox != null) {
            this.searchBox.setTextFieldFocused(false);
        }
        // rebuild 会重新 init，但 scrollRow / search 都是字段，位置和过滤条件都保留。
        rebuild();
    }

    /** 列表能显示多少行：底部要给「导入资源包」按钮留一行。 */
    private int visibleRows() {
        return Math.max(1, (this.height - FOOTER_H - PAD - ROW_H - 6 - LIST_TOP) / PITCH);
    }

    /**
     * 右栏：动作按钮 → 颜色 RGB → 几何（长/宽/透明）。没有选中光束时只显示按钮和一行提示；
     * 窗口太矮（缩放很大）时省略几何滑块，保证颜色和动作按钮始终可点。
     */
    private void buildRightColumn() {
        final int x = this.width - rightW() + PAD;
        final int width = Math.max(40, rightW() - 2 * PAD);
        final int buttonPitch = 20;
        final int bottom = this.height - FOOTER_H - PAD;
        int y = TOP_H + PAD;

        this.addDrawableChild(Widgets.button(tr("beam3d.add"), b -> addBeam())
                .dimensions(x, y, width, ROW_H).build());
        y += buttonPitch;
        this.duplicateButton = this.addDrawableChild(Widgets.button(tr("beam3d.duplicate"),
                        b -> duplicateBeam()).dimensions(x, y, width, ROW_H).build());
        y += buttonPitch;
        this.deleteButton = this.addDrawableChild(Widgets.button(tr("beam3d.delete"),
                        b -> deleteBeam()).dimensions(x, y, width, ROW_H).build());
        y += buttonPitch;
        final ButtonWidget typeButton = this.addDrawableChild(Widgets.button(typeLabel(),
                        b -> cycleType()).dimensions(x, y, width, ROW_H).build());
        typeButton.active = this.selected >= 0;
        y += buttonPitch;
        // 预览编组节数：只影响预览，不影响导出的 JSON（光束坐标始终是单车局部的）。
        this.addDrawableChild(Widgets.button(carsLabel(), b -> cycleCars())
                .dimensions(x, y, width, ROW_H).build());
        y += buttonPitch;
        this.addDrawableChild(Widgets.button(tr("beam3d.export"), b -> export())
                .dimensions(x, y, width, ROW_H).build());
        y += buttonPitch;
        // 导出与「打开导出目录」挨在一起：导出完想找文件就是下一步操作。
        this.addDrawableChild(Widgets.button(tr("beam3d.openDir"), b -> openExportDir())
                .dimensions(x, y, width, ROW_H).build());
        y += buttonPitch;
        this.addDrawableChild(Widgets.button(tr("beam3d.close"), b -> close())
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
        final int sliderH = 16;
        final int colorBlock = 12 + 3 * (sliderH + 2);
        if (y + colorBlock > bottom) {
            // 窗口太矮（GUI 缩放很大）：动作按钮优先，滑块整块省略而不是画到屏幕外。
            this.hintY = y + 4;
            return;
        }
        y += 12;
        addBeamSlider(x, y, width, sliderH, SLIDER_R);
        y += sliderH + 2;
        addBeamSlider(x, y, width, sliderH, SLIDER_G);
        y += sliderH + 2;
        addBeamSlider(x, y, width, sliderH, SLIDER_B);
        y += sliderH + 6;

        final int geometryBlock = 12 + 3 * (sliderH + 2);
        if (this.height >= 320 && y + geometryBlock <= bottom) {
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
        this.init(this.client, this.width, this.height); // 1.18.2 没有 clearAndInit()：init 自己会 clearChildren
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

    /** 「车厢 ×N」按钮文案。 */
    private MutableText carsLabel() {
        return tr("beam3d.cars", Integer.valueOf(this.carCount));
    }

    /**
     * 循环 1 → 2 → … → {@link TrainPreview#MAX_CARS} → 1。
     * 换节数时按整列重新取景（{@link TrainPreview.Subject#fitScale(int, int)}），
     * 不然 4 节的编组会有一半在视口外。
     */
    private void cycleCars() {
        this.carCount = this.carCount >= TrainPreview.MAX_CARS ? 1 : this.carCount + 1;
        if (this.subject != null) {
            this.camera.scale = this.subject.fitScale(this.viewY1 - this.viewY0, this.carCount);
        }
        this.status = tr("beam3d.carsHint", Integer.valueOf(this.carCount),
                String.format(Locale.ROOT, "%.1f", Double.valueOf(TrainPreview.carPitch(this.subject))))
                .getString();
        rebuild();
    }

    private void export() {
        if (this.session == null) {
            return;
        }
        final CustomPack.Train importedTrain = this.imported == null
                ? null : this.imported.train(this.vehicleId);
        if (importedTrain != null) {
            exportToPack(importedTrain);
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
                    path.toAbsolutePath().toString()).getString()
                    + "  " + tr("beam3d.exportHint").getString();
            LteLog.LOGGER.info("[lte] beam3d: exported {} beams -> {}", Integer.valueOf(beams), path);
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] beam3d: export failed", t);
            this.status = tr("beam3d.exportFail",
                    t.getClass().getSimpleName() + ": " + t.getMessage()).getString();
        }
    }

    /**
     * 导入的车型：写进<b>那个资源包</b>。
     *
     * <ul>
     *   <li>文件夹包 → 直接写 {@code <包>/assets/<ns>/beam3D/<id>.json}（自动建目录），
     *       写完立刻从包里读回来核对束数；</li>
     *   <li>zip → Minecraft/MTR 都不支持原地改 zip，所以写到桌面
     *       （{@link BeamPackWriter#exportDir()}）并提示「解压后放进
     *       {@code assets/<ns>/beam3D/}」。</li>
     * </ul>
     */
    private void exportToPack(final CustomPack.Train train) {
        try {
            final int beams = this.session.size();
            final CustomPack.Export result = this.imported.writeBeams(train, this.session.work);
            if (result.insidePack) {
                final int readBack = this.imported.readBeams(train).size();
                if (readBack != beams) {
                    this.status = tr("beam3d.exportFail",
                            "read-back " + readBack + " != " + beams).getString();
                    return;
                }
                this.status = tr("beam3d.exportedPack", Integer.valueOf(beams),
                        result.packRelative, result.path.toAbsolutePath().toString()).getString();
            } else {
                this.status = tr("beam3d.exportedZip", Integer.valueOf(beams),
                        result.path.toAbsolutePath().toString(), result.packRelative).getString();
            }
            LteLog.LOGGER.info("[lte] beam3d: exported {} beams -> {} (inPack={})",
                    Integer.valueOf(beams), result.path, Boolean.valueOf(result.insidePack));
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] beam3d: export to pack failed", t);
            this.status = tr("beam3d.exportFail",
                    t.getClass().getSimpleName() + ": " + t.getMessage()).getString();
        }
    }

    /**
     * 打开导出目录（桌面；桌面不可用时是 {@code config/lte_beams/}）：目录不存在就先创建
     * （第一次导出前点也没问题），然后交给系统的文件管理器
     * （{@link Util#getOperatingSystem()} → Windows 上是 rundll32）。
     */
    private void openExportDir() {
        try {
            final Path dir = BeamPackWriter.dir();
            Files.createDirectories(dir);
            Util.getOperatingSystem().open(dir.toFile());
            this.status = tr("beam3d.openDirDone", dir.toAbsolutePath().toString()).getString();
            LteLog.LOGGER.info("[lte] beam3d: opened export dir {}", dir.toAbsolutePath());
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] beam3d: open export dir failed", t);
            this.status = tr("beam3d.openDirFail",
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
        if (this.empty) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        // 左栏（车型列表 + 滚动条）是自绘的，命中检测自己来；都在 super 之前，避免被按钮抢走。
        if (button == 0 && mouseX < leftW() && mouseY >= TOP_H) {
            final int[] bar = scrollbar();
            if (bar != null && mouseX >= scrollbarX() && mouseX <= scrollbarX() + SCROLL_W
                    && mouseY >= bar[0] && mouseY <= bar[0] + bar[1]) {
                this.scrollGrab = mouseY >= bar[2] && mouseY <= bar[2] + bar[3]
                        ? mouseY - bar[2] : bar[3] / 2.0;
                this.drag = DRAG_SCROLL;
                this.dragButton = button;
                applyScrollDrag(mouseY);
                return true;
            }
            final int row = rowAt(mouseX, mouseY);
            if (row >= 0) {
                selectVehicle(row);
                return true;
            }
        }
        if (inViewport(mouseX, mouseY)) {
            this.pressX = mouseX;
            this.pressY = mouseY;
            this.lastX = mouseX;
            this.lastY = mouseY;
            this.dragButton = button;
            if (button == 0) {
                // 吸附按钮盖在视口左下角：先吃掉点击，否则会被当成"点空白处"把选中取消掉。
                final int snap = snapButtonAt(mouseX, mouseY);
                if (snap >= 0) {
                    snapSelected(snap);
                    this.drag = DRAG_NONE;
                    return true;
                }
                // 坐标轴手柄优先于光束本体：手柄就长在光束起点上，不先判它的话永远点不到。
                if (this.mode == MODE_MOVE && beginAxisDrag(mouseX, mouseY)) {
                    return true;
                }
                final TrainPreview.Hit hit = this.session == null ? null
                        : TrainPreview.pick(this.camera, mouseX, mouseY,
                        this.viewX0, this.viewY0, this.viewX1, this.viewY1, this.session.work);
                if (hit != null) {
                    select(hit.index);
                    if (this.mode == MODE_MOVE) {
                        this.drag = DRAG_BEAM_MOVE;
                        this.moveAnchor = null;       // 每次按下都从当前 pos 重新起算（Shift 步进要用）
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
            case DRAG_BEAM_AXIS:
                moveAlongAxis(mouseX, mouseY);
                break;
            case DRAG_BEAM_ROTATE:
                rotateSelected(dx, dy);
                break;
            case DRAG_SCROLL:
                applyScrollDrag(mouseY);
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
            this.moveAnchor = null;
            this.dragAxis = -1;
            this.axisAnchor = null;
            this.axisBase = null;
            this.axisGrab = Double.NaN;
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
        if (this.moveAnchor == null) {
            this.moveAnchor = beam.pos.clone();
            this.moveAccumX = 0.0D;
            this.moveAccumY = 0.0D;
        }
        // 累计位移（不是这一帧的增量）：Shift 步进要拿"按下时那一点"重新量化，
        // 否则每帧各自取整会把小位移吃掉，光束拖不动。
        this.moveAccumX += dx;
        this.moveAccumY += dy;
        final Matrix4f inverse = new Matrix4f(TrainPreview.beamRotate(this.camera)).invert();
        final Vector3f delta = inverse.transformDirection(new Vector3f(
                (float) (this.moveAccumX / this.camera.scale),
                (float) (-this.moveAccumY / this.camera.scale), 0.0F));
        final boolean snap = hasShiftDown();
        final double x = this.moveAnchor[0] + delta.x;
        final double y = this.moveAnchor[1] + delta.y;
        final double z = this.moveAnchor[2] + delta.z;
        beam.pos[0] = clamp(snap ? BeamSnap.stepped(x) : x, POS_MIN, POS_MAX);
        beam.pos[1] = clamp(snap ? BeamSnap.stepped(y) : y, POS_MIN, POS_MAX);
        beam.pos[2] = clamp(snap ? BeamSnap.stepped(z) : z, POS_MIN, POS_MAX);
        round(beam.pos);
        this.session.save();
    }

    // ---------------------------------------------------------------------------------------------
    // 轴向约束拖拽（XYZ 坐标轴手柄）
    // ---------------------------------------------------------------------------------------------

    /**
     * 按下时先看有没有点在坐标轴手柄上。命中就进入 {@link #DRAG_BEAM_AXIS}：
     * 记下按下点的 {@code pos}、轴线基点、以及鼠标射线在轴线上的参数。
     *
     * @return true = 已经接管这次按下（调用方必须直接 return）
     */
    private boolean beginAxisDrag(final double mouseX, final double mouseY) {
        final Beam beam = selectedBeam();
        if (beam == null) {
            return false;
        }
        if (beam.pos == null) {
            beam.pos = new double[]{0.0D, 0.0D, 0.0D};
        }
        final TrainPreview.AxisHit hit = TrainPreview.pickAxis(this.camera, mouseX, mouseY,
                this.viewX0, this.viewY0, this.viewX1, this.viewY1, beam.pos,
                TrainPreview.carOffsets(this.subject, this.carCount));
        if (hit == null) {
            return false;
        }
        // 轴线基点带上该节车的编组偏移：手柄画在哪一节车上，就按哪一节车的轴拖。
        this.axisBase = new double[]{beam.pos[0] + hit.ox, beam.pos[1], beam.pos[2] + hit.oz};
        this.axisGrab = TrainPreview.axisCoordinate(this.camera, mouseX, mouseY,
                this.viewX0, this.viewY0, this.viewX1, this.viewY1,
                this.axisBase, TrainPreview.axisUnit(hit.axis));
        if (Double.isNaN(this.axisGrab)) {
            // 这根轴几乎正对相机：屏幕上就是一个点，没法投影位移，别进入拖拽。
            this.axisBase = null;
            return false;
        }
        this.drag = DRAG_BEAM_AXIS;
        this.dragAxis = hit.axis;
        this.axisAnchor = beam.pos.clone();
        this.status = tr("beam3d.dragAxis", TrainPreview.axisName(hit.axis),
                Integer.valueOf(this.selected + 1)).getString();
        return true;
    }

    /**
     * 只沿 {@link #dragAxis} 这一根轴移动。
     *
     * <p>位移不是“像素差 / scale”（那样视角一斜就和光标脱节），而是
     * {@code 当前射线×轴线的最近点 − 按下时的那个最近点}：光标始终贴着轴上的同一个点走。
     * 另外两个分量从按下时的快照里原样写回，保证「只动一根轴」不会因为取整而漂。
     * Shift 仍然把结果量化到 0.5 格（和自由移动同一个 {@link BeamSnap#stepped}）。
     */
    private void moveAlongAxis(final double mouseX, final double mouseY) {
        final Beam beam = selectedBeam();
        if (beam == null || beam.pos == null || this.dragAxis < 0
                || this.axisAnchor == null || this.axisBase == null) {
            return;
        }
        final double t = TrainPreview.axisCoordinate(this.camera, mouseX, mouseY,
                this.viewX0, this.viewY0, this.viewX1, this.viewY1,
                this.axisBase, TrainPreview.axisUnit(this.dragAxis));
        if (Double.isNaN(t)) {
            return;
        }
        final int axis = this.dragAxis;
        double value = this.axisAnchor[axis] + (t - this.axisGrab);
        if (hasShiftDown()) {
            value = BeamSnap.stepped(value);
        }
        beam.pos[axis] = clamp(value, POS_MIN, POS_MAX);
        beam.pos[(axis + 1) % 3] = this.axisAnchor[(axis + 1) % 3];
        beam.pos[(axis + 2) % 3] = this.axisAnchor[(axis + 2) % 3];
        round(beam.pos);
        this.session.save();
    }

    /** 鼠标现在悬在哪根轴上（-1 = 没有）。拖动中直接返回正在拖的那根。 */
    private int axisUnder(final double mouseX, final double mouseY) {
        if (this.drag == DRAG_BEAM_AXIS) {
            return this.dragAxis;
        }
        if (this.mode != MODE_MOVE || this.drag != DRAG_NONE) {
            return -1;
        }
        final Beam beam = selectedBeam();
        if (beam == null || beam.pos == null) {
            return -1;
        }
        final TrainPreview.AxisHit hit = TrainPreview.pickAxis(this.camera, mouseX, mouseY,
                this.viewX0, this.viewY0, this.viewX1, this.viewY1, beam.pos,
                TrainPreview.carOffsets(this.subject, this.carCount));
        return hit == null ? -1 : hit.axis;
    }

    // ---------------------------------------------------------------------------------------------
    // 轴向吸附（六个正交方向 + Shift 0.5 格步进）
    // ---------------------------------------------------------------------------------------------

    /** 吸附按钮左上角：视口左下角，3 列 × 2 行，色块自绘（不进 Widget 列表，拖动时不会重建）。 */
    private int snapX(final int dir) {
        return this.viewX0 + 4 + (dir % 3) * (SNAP_W + SNAP_GAP);
    }

    private int snapY(final int dir) {
        return snapTop() + (dir / 3) * (SNAP_H + SNAP_GAP);
    }

    private int snapTop() {
        return Math.max(this.viewY0 + 20, this.viewY1 - 6 - 2 * SNAP_H - SNAP_GAP);
    }

    /** 命中检测：返回 {@link BeamSnap} 的方向，或 -1（没选光束时整排按钮不出现、也不吃点击）。 */
    private int snapButtonAt(final double mouseX, final double mouseY) {
        if (selectedBeam() == null) {
            return -1;
        }
        for (int dir = 0; dir < BeamSnap.COUNT; dir++) {
            final int x = snapX(dir);
            final int y = snapY(dir);
            if (mouseX >= x && mouseX <= x + SNAP_W && mouseY >= y && mouseY <= y + SNAP_H) {
                return dir;
            }
        }
        return -1;
    }

    /**
     * 把选中的光束摆到六个正交方向之一（角度表见 {@link BeamSnap}）：
     * 前 = -Z（车头）、后 = +Z（车尾）、上 = +Y、下 = -Y、左 = -X、右 = +X。
     */
    private void snapSelected(final int dir) {
        final Beam beam = selectedBeam();
        if (beam == null) {
            this.status = tr("beam3d.needSelection").getString();
            return;
        }
        final double[] rot = BeamSnap.apply(beam, dir);
        this.session.save();
        this.status = tr("beam3d.snapDone", tr(BeamSnap.key(dir)),
                String.format(Locale.ROOT, "%.0f", Double.valueOf(rot[0])),
                String.format(Locale.ROOT, "%.0f", Double.valueOf(rot[1]))).getString();
    }

    private void drawSnapButtons(final DrawContext context, final int mouseX, final int mouseY) {
        if (selectedBeam() == null) {
            return;
        }
        context.drawTextWithShadow(this.textRenderer, tr("beam3d.snap.title"),
                this.viewX0 + 4, snapTop() - 11, 0x909090);
        for (int dir = 0; dir < BeamSnap.COUNT; dir++) {
            final int x = snapX(dir);
            final int y = snapY(dir);
            final boolean hover = mouseX >= x && mouseX <= x + SNAP_W
                    && mouseY >= y && mouseY <= y + SNAP_H;
            context.fill(x, y, x + SNAP_W, y + SNAP_H, hover ? 0xFF39543A : 0xC0202020);
            context.fill(x, y, x + SNAP_W, y + 1, hover ? 0xFF9DFF9D : 0xFF5A5A5A);
            context.drawCenteredTextWithShadow(this.textRenderer,
                    new LiteralText(this.textRenderer.trimToWidth(
                            tr(BeamSnap.key(dir)).getString(), SNAP_W - 4)),
                    x + SNAP_W / 2, y + 3, hover ? 0xFFFFFFFF : 0xC8C8C8);
        }
    }

    /** 快捷键：F/B = 前后，U/D = 上下，L/R = 左右（按钮上的字母一一对应）。 */
    private static int snapKeyOf(final int keyCode) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_F:
                return BeamSnap.FRONT;
            case GLFW.GLFW_KEY_B:
                return BeamSnap.BACK;
            case GLFW.GLFW_KEY_U:
                return BeamSnap.UP;
            case GLFW.GLFW_KEY_D:
                return BeamSnap.DOWN;
            case GLFW.GLFW_KEY_L:
                return BeamSnap.LEFT;
            case GLFW.GLFW_KEY_R:
                return BeamSnap.RIGHT;
            default:
                return -1;
        }
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

    /**
     * 滚动条拖动：把鼠标 y 换算成 scrollRow。滚不动的列表（内容比视口短）直接归零。
     * 只改字段，不重建控件 —— 这样拖动时不会掉帧，也不会丢失搜索框焦点。
     */
    private void applyScrollDrag(final double mouseY) {
        final int[] bar = scrollbar();
        if (bar == null) {
            return;
        }
        final double travel = bar[1] - bar[3];
        final int max = Math.max(1, this.shown.size() - visibleRows());
        if (travel <= 0.0D) {
            this.scrollRow = 0;
            return;
        }
        final double t = (mouseY - this.scrollGrab - bar[0]) / travel;
        this.scrollRow = (int) Math.round(clamp(t, 0.0D, 1.0D) * max);
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double amount) {
        if (this.empty) {
            return super.mouseScrolled(mouseX, mouseY, amount);
        }
        if (inViewport(mouseX, mouseY) && amount != 0.0D) {
            this.camera.zoom(amount);
            return true;
        }
        if (mouseX < leftW() && mouseY >= LIST_TOP && this.shown.size() > visibleRows()
                && amount != 0.0D) {
            // 触控板一次只给 0.1 格：先累加，攒够一格才滚，同时不重建控件。
            this.scrollAccum += amount;
            final int steps = (int) this.scrollAccum;
            if (steps != 0) {
                this.scrollAccum -= steps;
                this.scrollRow -= steps;
                clampScroll();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (this.empty) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        // 搜索框有焦点时，1-4 / Delete / Ctrl+D 属于「输入」，不能再当工具快捷键吃掉。
        if (this.searchBox != null && this.searchBox.isFocused()) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == GLFW.GLFW_KEY_DELETE) {
            deleteBeam();
            return true;
        }
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_4) {
            this.mode = keyCode - GLFW.GLFW_KEY_1;
            return true;
        }
        // Ctrl+D 是"复制"，必须在「D = 正下方」之前判掉。
        if (keyCode == GLFW.GLFW_KEY_D && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            duplicateBeam();
            return true;
        }
        final int snapKey = snapKeyOf(keyCode);
        if (snapKey >= 0) {
            snapSelected(snapKey);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ---------------------------------------------------------------------------------------------
    // 渲染
    // ---------------------------------------------------------------------------------------------

    @Override
    public void render(final MatrixStack matrices, final int mouseX, final int mouseY, final float delta) {
        final DrawContext context = new DrawContext(matrices);
        context.fill(0, 0, this.width, this.height, 0xF0101010);

        if (this.empty) {
            // 空目录：不画 3D / 侧栏，只留一行说明和关闭按钮。屏幕本身必须是「看得见」的，
            // 否则玩家无法区分「没车型」和「界面根本没打开」。
            drawEmptyState(context);
            super.render(matrices, mouseX, mouseY, delta);
            return;
        }

        // 3D 先画：之后的 2D 全部落在深度 0（最前），不会被模型压住。
        // carCount 只是预览里的编组节数（车体局部坐标不变，所以光束/导出都不受影响）。
        // 移动模式下给选中的光柱画 XYZ 手柄；悬停/正在拖的那根高亮。
        this.hoverAxis = axisUnder(mouseX, mouseY);
        TrainPreview.draw(this.subject, context, this.camera,
                this.viewX0, this.viewY0, this.viewX1, this.viewY1,
                this.session == null ? List.of() : this.session.work, this.selected, this.carCount,
                this.mode == MODE_MOVE && this.selected >= 0, this.hoverAxis);

        context.fill(0, 0, this.width, TOP_H, 0xE0181818);
        context.fill(0, TOP_H, leftW(), this.height, 0xE0181818);
        context.fill(this.width - rightW(), TOP_H, this.width, this.height, 0xE0181818);
        context.fill(0, this.height - FOOTER_H, this.width, this.height, 0xE0181818);

        super.render(matrices, mouseX, mouseY, delta);
        drawToolbarState(context);

        // 左栏标题（搜索时显示「命中/总数」）
        final String count = this.search == null || this.search.isBlank()
                ? String.valueOf(this.vehicles.size())
                : this.shown.size() + "/" + this.vehicles.size();
        final String listTitle = tr("beam3d.vehicles", count).getString();
        context.drawTextWithShadow(this.textRenderer, new LiteralText(
                        this.textRenderer.trimToWidth(listTitle, leftW() - 2 * PAD)),
                PAD, TOP_H + 6, 0xA0A0A0);

        drawVehicleList(context, mouseX, mouseY);
        drawScrollbar(context);

        // 右栏分区
        if (this.colorSectionY >= 0) {
            context.drawTextWithShadow(this.textRenderer,
                    tr("beam3d.section.color"), this.width - rightW() + PAD, this.colorSectionY, 0xC0C0C0);
        }
        if (this.geometrySectionY >= 0) {
            context.drawTextWithShadow(this.textRenderer,
                    tr("beam3d.section.geometry"), this.width - rightW() + PAD,
                    this.geometrySectionY, 0xC0C0C0);
        }
        if (this.session == null || this.session.size() == 0) {
            if (this.hintY >= 0) {
                context.drawTextWithShadow(this.textRenderer, tr("beam3d.emptyList"),
                        this.width - rightW() + PAD, this.hintY, 0x909090);
            }
        } else if (this.hintY >= 0) {
            context.drawTextWithShadow(this.textRenderer, tr("beam3d.pickHint"),
                    this.width - rightW() + PAD, this.hintY, 0x909090);
        }

        drawViewportTexts(context);
        drawSnapButtons(context, mouseX, mouseY);
        drawFooter(context);

        if (this.deleteButton != null) {
            this.deleteButton.active = this.selected >= 0 && this.session != null
                    && this.session.size() > 1;
        }
        if (this.duplicateButton != null) {
            this.duplicateButton.active = this.selected >= 0 && this.session != null;
        }
    }

    /**
     * 左栏车型行（自绘）：选中行高亮 + 左侧绿条，悬停行提亮，标签按行宽裁切。
     * 自绘而不是每行一个 ButtonWidget，是为了滚动/选中时不重建控件（滚动位置和输入焦点都不会丢）。
     */
    private void drawVehicleList(final DrawContext context, final int mouseX, final int mouseY) {
        if (this.shown.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer,
                    new LiteralText(this.textRenderer.trimToWidth(
                            tr("beam3d.noMatch").getString(), rowW() - 4)),
                    PAD + 3, LIST_TOP + 4, 0x909090);
            return;
        }
        // 空搜索框没有 placeholder API（1.20.1 的 TextFieldWidget 不带），自己画一行灰字。
        if (this.searchBox != null && (this.search == null || this.search.isEmpty())
                && !this.searchBox.isFocused()) {
            context.drawTextWithShadow(this.textRenderer, tr("beam3d.search"),
                    PAD + 4, SEARCH_Y + 4, 0x707070);
        }
        final int listW = rowW();
        final int visible = visibleRows();
        for (int i = 0; i < visible; i++) {
            final int index = this.scrollRow + i;
            if (index >= this.shown.size()) {
                break;
            }
            final VehicleRow row = this.shown.get(index);
            final int y = LIST_TOP + i * PITCH;
            final boolean active = this.vehicleId != null && this.vehicleId.equalsIgnoreCase(row.id);
            final boolean hover = !active && mouseX >= PAD && mouseX <= PAD + listW
                    && mouseY >= y && mouseY < y + ROW_H;
            if (active) {
                context.fill(PAD, y, PAD + listW, y + ROW_H, 0xFF2C4A2C);
                context.fill(PAD, y, PAD + 2, y + ROW_H, 0xFF7FE07F);
            } else if (hover) {
                context.fill(PAD, y, PAD + listW, y + ROW_H, 0x30FFFFFF);
            }
            final String label = (active ? "▸ " : "") + row.label();
            // 导入包里的车型用偏蓝的字色区分（不在 MTR 注册表里，是临时读出来的）。
            final int colour = active ? 0xFFFFFFFF
                    : row.imported != null ? (hover ? 0xC0E8FF : 0x9DD0FF)
                    : (hover ? 0xE8E8E8 : 0xC0C0C0);
            context.drawTextWithShadow(this.textRenderer,
                    new LiteralText(this.textRenderer.trimToWidth(label, listW - 4)),
                    PAD + 4, y + 5, colour);
        }
    }

    /** 左栏滚动条：可见滑轨 + 滑块（滑轨/滑块都能拖）。列表不需要滚动时不画。 */
    private void drawScrollbar(final DrawContext context) {
        final int[] bar = scrollbar();
        if (bar == null) {
            return;
        }
        final int x = scrollbarX();
        final int bottom = bar[0] + bar[1];
        context.fill(x, bar[0], x + SCROLL_W, bottom, 0xFF000000);
        context.fill(x + 1, bar[0] + 1, x + SCROLL_W - 1, bottom - 1, 0xFF303030);
        context.fill(x, bar[2], x + SCROLL_W, bar[2] + bar[3], 0xFF9E9E9E);
        context.fill(x, bar[2], x + SCROLL_W, bar[2] + 1, 0xFFD8D8D8);
        context.fill(x, bar[2] + bar[3] - 1, x + SCROLL_W, bar[2] + bar[3], 0xFF5A5A5A);
    }

    /** 当前车型在游戏里显示的名字（导入包优先，其次注册表，最后 id）。 */
    private String currentName(final String id) {
        final CustomPack.Train train = this.imported == null ? null : this.imported.train(id);
        return train != null ? train.name : TrainNames.of(id);
    }

    /**
     * 空车型状态：一行标题 + 一行原因/下一步。屏幕必须能画出来 —— 玩家用它区分
     * 「资源包没加载」和「界面根本没打开」。
     */
    private void drawEmptyState(final DrawContext context) {
        final int cx = this.width / 2;
        final int cy = this.height / 2;
        context.drawCenteredTextWithShadow(this.textRenderer, tr("beam3d.noVehicles"),
                cx, cy - 34, 0xFFD37F);
        context.drawCenteredTextWithShadow(this.textRenderer, tr("beam3d.noVehiclesHint"),
                cx, cy - 18, 0xA0A0A0);
        if (builtinsPruned()) {
            // 最可能的那个原因直接写在屏幕上：注册表里确实一个内置车型都没有了。
            context.drawCenteredTextWithShadow(this.textRenderer, tr("beam3d.prunedHint"),
                    cx, cy - 2, 0xFF8080);
        }
        context.drawCenteredTextWithShadow(this.textRenderer,
                new LiteralText("/mtr_lte beam3d <车型id>"), cx, cy + 14, 0x707070);
    }

    private void drawToolbarState(final DrawContext context) {
        for (int i = 0; i < this.modeButtons.size(); i++) {
            if (i != this.mode) {
                continue;
            }
            final ButtonWidget button = this.modeButtons.get(i);
            context.fill(button.x - 1, button.y - 1,
                    button.x + button.getWidth() + 1, button.y, 0xFFFFFFFF);
            context.fill(button.x - 1, button.y + button.getHeight(),
                    button.x + button.getWidth() + 1, button.y + button.getHeight() + 1,
                    0xFFFFFFFF);
        }
    }

    private void drawViewportTexts(final DrawContext context) {
        final int left = this.viewX0 + 4;
        final int top = this.viewY0 + 4;
        if (this.subject != null) {
            // 名字在前、内部 id 在后：车厂里看到的是名字，指令里用的是 id。
            final String name = currentName(this.subject.id);
            final String pack = this.subject.pack + " · " + name
                    + (name.equalsIgnoreCase(this.subject.id) ? "" : "  (" + this.subject.id + ")");
            context.drawTextWithShadow(this.textRenderer,
                    new LiteralText(this.textRenderer.trimToWidth(pack, this.viewX1 - this.viewX0 - 8)),
                    left, top, 0x9DFF9D);
            if (this.carCount > 1) {
                // 编组提示贴视口右上角，别和下面「第 N 束」那行抢位置。
                final Text badge = tr("beam3d.carsBadge", Integer.valueOf(this.carCount),
                        String.format(Locale.ROOT, "%.1f",
                                Double.valueOf(TrainPreview.carPitch(this.subject))));
                context.drawTextWithShadow(this.textRenderer, badge,
                        this.viewX1 - 6 - this.textRenderer.getWidth(badge), top, 0x9DD0FF);
            }
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
            context.drawTextWithShadow(this.textRenderer, new LiteralText(head), left, top + 12, 0xFFE08A);

            if (this.mode == MODE_MOVE) {
                // 手柄只画一次说明，不占按钮位（三个颜色块 + 一句提示已经够认）。
                context.drawTextWithShadow(this.textRenderer, tr("beam3d.axisHint"),
                        left, top + 24, 0x9DD0FF);
            }

            if (this.propertiesY >= 0) {
                final int x = this.width - rightW() + PAD;
                context.drawTextWithShadow(this.textRenderer,
                        new LiteralText(String.format(Locale.ROOT, "pos %.2f %.2f %.2f",
                                Double.valueOf(axis(beam.pos, 0)), Double.valueOf(axis(beam.pos, 1)),
                                Double.valueOf(axis(beam.pos, 2)))), x, this.propertiesY, 0xE0E0E0);
                context.drawTextWithShadow(this.textRenderer,
                        new LiteralText(String.format(Locale.ROOT, "rot %.0f %.0f %.0f",
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
                new LiteralText(this.textRenderer.trimToWidth(hint, this.width - 2 * PAD)),
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
            super(x, y, w, h, LiteralText.EMPTY, normalize(current(kind), min(kind), max(kind)));
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
            setMessage(new LiteralText(label + " " + value));
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
        // 导入包的动态贴图挂在贴图管理器上：关编辑器就释放，免得反复导入把显存堆满。
        if (this.imported != null) {
            this.imported.release();
            this.imported = null;
        }
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }
}
