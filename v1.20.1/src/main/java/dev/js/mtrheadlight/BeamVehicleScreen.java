package dev.js.mtrheadlight;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 光束编辑的第 1 层：<b>选车型</b>。
 *
 * <p>只列出这个世界里真的出现过的车型（{@code ClientData.TRAINS} 的 {@code trainId} 去重）-
 * 一个 300 个车型的资源包对玩家没有意义，他只想调眼前这几辆。每行右侧是自动识别的结果
 * （{@code 自动识别：head 2 · tail 1}），所以「LTE 到底认出了几个灯」在点进去之前就是可见的。
 *
 * <h2>搜索</h2>
 * 顶部一个搜索框（子串、不分大小写）加一个 ✕。ESC 先清空搜索框，空了才返回上一层 -
 * 和 {@code SidingScreen} 同一条规则，免得一次 ESC 就把两层屏幕一起关掉。
 *
 * <h2>侧线页进来时预选车型</h2>
 * 侧线配置 -> 光源 -> 光束编辑 时，侧线自己的车型 id（{@code Siding.getTrainId()}）会传进来：
 * 那一行排在最前面并带「◀ 当前用车」标记（画一个白色选中框），但仍要点一下才进去 -
 * 「预选」不等于「跳过选择」。读不到（老版本 MTR、侧线还没配车）就是普通全量列表。
 *
 * <h2>布局</h2>
 * 列宽 = 最宽的「id + 自动识别」行 + 两个汉字，最窄 {@value #MIN_COLUMN}；行按钮占
 * {@code colW - STATUS_W - GAP}，右侧 {@value #STATUS_W} 是状态列，底栏和列同宽 -
 * 与 {@code SidingScreen} / {@code ParameterScreen} 同一套公式，没有硬编码坐标。
 */
final class BeamVehicleScreen extends Screen {
    private static final Identifier OPTIONS_BACKGROUND =
            new Identifier("textures/gui/options_background.png");

    private static final int ROW_H = 20;
    private static final int PITCH = 25;
    private static final int GAP = 8;
    private static final int MIN_COLUMN = 200;
    /** 右侧「自动识别」状态列；比侧线页的状态列宽，因为它要放 {@code head 2 · tail 1}。 */
    private static final int STATUS_W = 128;
    private static final int CLEAR_W = 18;
    private static final int TITLE_Y = 22;
    private static final int HINT_Y = 36;
    private static final int SEARCH_Y = 46;
    private static final int SEARCH_H = 18;
    private static final int LIST_TOP = 72;
    private static final int FOOTER_H = 44;

    /** 回到这里：行页（光源分区）。 */
    private final Screen parent;
    /** 主界面，列表页的「返回主界面」不会用到，但一路传下去保持一致。 */
    private final Screen root;
    /** 侧线当前使用的车型，可能为 null。 */
    private final String preset;

    private List<BeamSession.VehicleEntry> all = List.of();
    private List<BeamSession.VehicleEntry> shown = List.of();

    private TextFieldWidget searchField;
    private ButtonWidget clearButton;

    private String query = "";
    private int left;
    private int colW;
    private int rowW;
    private int statusRight;
    private int listTop = LIST_TOP;
    private int visibleRows = 1;
    private int scrollRow;
    /** 每次重建列表都要清掉的行控件（搜索框和 ✕ 是常驻的）。 */
    private final List<ClickableWidget> dynamicRows = new ArrayList<>();

    BeamVehicleScreen(final Screen parent, final Screen root, final String preset) {
        super(tr("beam.title.vehicles"));
        this.parent = parent;
        this.root = root == null ? parent : root;
        this.preset = preset == null || preset.isBlank() ? null : preset;
    }

    static MutableText tr(final String key, final Object... args) {
        return Text.translatable(key, args);
    }

    private int viewBottom() {
        return this.height - FOOTER_H - GAP;
    }

    // ---------------------------------------------------------------------------------------------
    // Init / layout
    // ---------------------------------------------------------------------------------------------

    @Override
    protected void init() {
        this.all = order(BeamSession.vehicles());

        int need = 0;
        for (final BeamSession.VehicleEntry entry : this.all) {
            need = Math.max(need, textWidth(label(entry)));
        }
        final int charW = this.textRenderer.getWidth("测");
        final int maxCol = Math.max(MIN_COLUMN, this.width - 32);
        this.colW = Math.min(maxCol, Math.max(MIN_COLUMN, need + STATUS_W + GAP + charW * 2));
        this.left = this.width / 2 - this.colW / 2;
        this.statusRight = this.left + this.colW;
        this.rowW = this.colW - STATUS_W - GAP;

        this.visibleRows = Math.max(1, (viewBottom() - LIST_TOP + (PITCH - ROW_H)) / PITCH);
        this.listTop = listTop();

        buildTopBar();
        rebuildRows();
    }

    /** 预选车型排第一，其余保持 id 顺序。 */
    private List<BeamSession.VehicleEntry> order(final List<BeamSession.VehicleEntry> entries) {
        if (this.preset == null) {
            return entries;
        }
        final List<BeamSession.VehicleEntry> out = new ArrayList<>(entries.size());
        for (final BeamSession.VehicleEntry entry : entries) {
            if (entry.id.equalsIgnoreCase(this.preset)) {
                out.add(entry);
            }
        }
        for (final BeamSession.VehicleEntry entry : entries) {
            if (!entry.id.equalsIgnoreCase(this.preset)) {
                out.add(entry);
            }
        }
        return out;
    }

    /** 列表全放得下时垂直居中，放不下就贴住 {@link #LIST_TOP}。 */
    private int listTop() {
        final int shownRows = Math.min(this.visibleRows, this.shown.size());
        final int listH = shownRows * PITCH - (PITCH - ROW_H);
        final boolean scrolls = this.shown.size() > this.visibleRows;
        return scrolls ? LIST_TOP : LIST_TOP + Math.max(0, (viewBottom() - LIST_TOP - listH) / 2);
    }

    private int textWidth(final Text text) {
        return this.textRenderer.getWidth(text.getString());
    }

    private Text label(final BeamSession.VehicleEntry entry) {
        final MutableText text = Text.literal(entry.id);
        if (this.preset != null && entry.id.equalsIgnoreCase(this.preset)) {
            text.append(tr("beam.preset"));
        }
        return text;
    }

    private void buildTopBar() {
        final int fieldW = this.colW - CLEAR_W - GAP;
        this.searchField = new TextFieldWidget(this.textRenderer, this.left, SEARCH_Y,
                Math.max(40, fieldW), SEARCH_H, tr("beam.search"));
        this.searchField.setMaxLength(64);
        this.searchField.setPlaceholder(tr("beam.search"));
        this.searchField.setFocusUnlocked(true);
        this.searchField.setText(this.query);
        this.searchField.setChangedListener(this::onQueryChanged);
        this.addDrawableChild(this.searchField);

        this.clearButton = this.addDrawableChild(ButtonWidget.builder(tr("btn.clearSearch"), b -> {
            if (this.searchField != null) {
                this.searchField.setText("");
                this.searchField.setFocused(true);
            }
        }).dimensions(this.left + fieldW + GAP, SEARCH_Y, CLEAR_W, SEARCH_H).build());
    }

    private void onQueryChanged(final String text) {
        final String q = text == null ? "" : text.trim();
        if (q.equals(this.query)) {
            return;
        }
        this.query = q;
        this.scrollRow = 0;
        rebuildRows();
    }

    // ---------------------------------------------------------------------------------------------
    // Rows
    // ---------------------------------------------------------------------------------------------

    private void rebuildRows() {
        for (final ClickableWidget widget : this.dynamicRows) {
            this.remove(widget);
        }
        this.dynamicRows.clear();

        final List<BeamSession.VehicleEntry> filtered = new ArrayList<>();
        for (final BeamSession.VehicleEntry entry : this.all) {
            if (this.query.isEmpty() || matches(entry, this.query)) {
                filtered.add(entry);
            }
        }
        this.shown = filtered;

        this.visibleRows = Math.max(1, (viewBottom() - LIST_TOP + (PITCH - ROW_H)) / PITCH);
        final int maxScroll = Math.max(0, this.shown.size() - this.visibleRows);
        this.scrollRow = Math.max(0, Math.min(this.scrollRow, maxScroll));
        this.listTop = listTop();

        final int last = Math.min(this.shown.size(), this.scrollRow + this.visibleRows);
        for (int i = this.scrollRow; i < last; i++) {
            final BeamSession.VehicleEntry entry = this.shown.get(i);
            final int y = this.listTop + (i - this.scrollRow) * PITCH;
            final String trimmed = this.textRenderer.trimToWidth(
                    label(entry).getString(), this.rowW - 12);
            final ButtonWidget button = ButtonWidget.builder(Text.literal(trimmed),
                    b -> open(entry)).dimensions(this.left, y, this.rowW, ROW_H).build();
            attach(button);
        }

        if (this.clearButton != null) {
            this.clearButton.active = !this.query.isEmpty();
        }
        buildFooter();
    }

    private void attach(final ClickableWidget widget) {
        this.addDrawableChild(widget);
        this.dynamicRows.add(widget);
    }

    private boolean matches(final BeamSession.VehicleEntry entry, final String rawQuery) {
        final String q = rawQuery.toLowerCase(Locale.ROOT);
        return entry.id.toLowerCase(Locale.ROOT).contains(q);
    }

    private void buildFooter() {
        final int bottom = this.height - FOOTER_H;
        final int backW = (this.colW - GAP) / 2;
        attach(ButtonWidget.builder(tr("btn.back"), b -> close())
                .dimensions(this.width / 2 - backW / 2, bottom, backW, ROW_H).build());
    }

    private void open(final BeamSession.VehicleEntry entry) {
        if (this.client == null || entry == null) {
            return;
        }
        this.client.setScreen(new BeamListScreen(this, this.root, BeamSession.open(entry.id)));
    }

    // ---------------------------------------------------------------------------------------------
    // Render / input
    // ---------------------------------------------------------------------------------------------

    private void drawRight(final DrawContext context, final Text text, final int right, final int y,
                           final int color) {
        context.drawTextWithShadow(this.textRenderer, text,
                right - textWidth(text), y, color);
    }

    @Override
    public void render(final DrawContext context, final int mouseX, final int mouseY, final float delta) {
        context.drawTexture(OPTIONS_BACKGROUND, 0, 0, 0, 0, this.width, this.height, 32, 32);
        context.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
        // 预选车型的白框先画，按钮文字随后由 super.render 画在上面，不会被框盖住。
        drawPresetFrame(context);
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, TITLE_Y, 0xFFFFFF);
        final String hint = this.textRenderer.trimToWidth(tr("beam.hint.vehicles").getString(),
                Math.max(80, this.width - 32));
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(hint),
                this.width / 2, HINT_Y, 0xB0B0B0);

        if (this.all.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, tr("beam.noVehicles"),
                    this.width / 2, LIST_TOP + 16, 0xFFD37F);
            context.drawCenteredTextWithShadow(this.textRenderer, tr("beam.noVehiclesHint"),
                    this.width / 2, LIST_TOP + 30, 0x909090);
            return;
        }
        if (this.shown.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, tr("sidings.noMatch"),
                    this.width / 2, this.listTop + 16, 0xFFD37F);
            return;
        }

        final int last = Math.min(this.shown.size(), this.scrollRow + this.visibleRows);
        for (int i = this.scrollRow; i < last; i++) {
            final BeamSession.VehicleEntry entry = this.shown.get(i);
            final int y = this.listTop + (i - this.scrollRow) * PITCH;
            final Text auto = entry.auto == null
                    ? tr("beam.auto.none")
                    : tr("beam.auto.summary", entry.auto);
            drawRight(context, auto, this.statusRight, y + 6,
                    entry.auto == null ? 0x9A9A9A : 0x9DFF9D);
        }
        drawScrollBar(context);
        if (this.shown.size() > this.visibleRows) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    tr("config.scrollHint", Integer.valueOf(this.scrollRow + 1),
                            Integer.valueOf(this.shown.size())),
                    this.width / 2, this.height - FOOTER_H - 14, 0x909090);
        }
    }

    /** 预选车型那一行外面的一圈白框（画在按钮下面，不挡文字）。 */
    private void drawPresetFrame(final DrawContext context) {
        if (this.preset == null || this.shown.isEmpty()) {
            return;
        }
        final int last = Math.min(this.shown.size(), this.scrollRow + this.visibleRows);
        for (int i = this.scrollRow; i < last; i++) {
            final BeamSession.VehicleEntry entry = this.shown.get(i);
            if (!entry.id.equalsIgnoreCase(this.preset)) {
                continue;
            }
            final int y = this.listTop + (i - this.scrollRow) * PITCH;
            context.fill(this.left - 2, y - 2, this.left + this.rowW + 2, y - 1, 0xFFFFFFFF);
            context.fill(this.left - 2, y + ROW_H + 1, this.left + this.rowW + 2, y + ROW_H + 2, 0xFFFFFFFF);
            context.fill(this.left - 2, y - 2, this.left - 1, y + ROW_H + 2, 0xFFFFFFFF);
            context.fill(this.left + this.rowW + 1, y - 2, this.left + this.rowW + 2, y + ROW_H + 2, 0xFFFFFFFF);
        }
    }

    private void drawScrollBar(final DrawContext context) {
        if (this.shown.size() <= this.visibleRows) {
            return;
        }
        final int top = LIST_TOP;
        final int bottom = this.listTop + this.visibleRows * PITCH - (PITCH - ROW_H);
        final int x = this.left + this.colW + 4;
        context.fill(x, top, x + 1, Math.max(top, bottom), 0x60FFFFFF);
        final int trackH = Math.max(1, bottom - top);
        final int thumbH = Math.max(12, trackH * this.visibleRows / this.shown.size());
        final int span = Math.max(1, this.shown.size() - this.visibleRows);
        final int thumbY = top + (trackH - thumbH) * this.scrollRow / span;
        context.fill(x - 1, thumbY, x + 2, thumbY + thumbH, 0xFFFFFFFF);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** ESC / 返回：回光源分区（逐层返回的第 1 层）。 */
    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double amount) {
        if (this.shown.size() > this.visibleRows && amount != 0.0D) {
            this.scrollRow -= (int) Math.signum(amount);
            rebuildRows();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (this.searchField != null && this.searchField.isFocused() && !this.query.isEmpty()
                && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.searchField.setText("");
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            return scrollBy(Math.max(1, this.visibleRows - 1));
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            return scrollBy(-Math.max(1, this.visibleRows - 1));
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private boolean scrollBy(final int delta) {
        if (this.shown.size() <= this.visibleRows) {
            return false;
        }
        this.scrollRow += delta;
        rebuildRows();
        return true;
    }
}
