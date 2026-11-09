package dev.js.mtrheadlight;

import dev.js.mtrheadlight.VehicleLightOverrides.Beam;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 光束编辑的第 2 层：<b>这个车型的全部光束</b>。
 *
 * <h2>行</h2>
 * 一行一束：左边一个 {@value #SEL_W}px 的勾选框（☑ / ☐），右边是
 * {@code #1 车头 #FFF3D6 16.0} 的按钮。点勾选框 = 选中（可以多选），点按钮 = 进单束编辑
 * （{@link BeamEditScreen}）。两件事从不挤在同一个控件上，所以「想删第三束，结果点进了编辑页」
 * 不会发生。
 *
 * <h2>底栏两行</h2>
 * <ul>
 *   <li>新增光束 / 删除选中 / 导出 json - 三等分，和列表同宽；</li>
 *   <li>↺ 放弃修改 / 返回 - 两等分。放弃修改会先问一次
 *       （{@link ResetConfirmScreen}），因为它清的是<b>本次会话</b>写过的全部运行时覆盖，
 *       不只是当前这辆车。</li>
 * </ul>
 *
 * <h2>导出</h2>
 * 写 {@code config/lte_lights/lte_vehicle_lights.json}（和资源包 / 服务端读的同一个文件名），
 * 写完立刻用数据层的解析器<b>读回</b>一遍：读不回来就说明格式坏了，聊天栏会说失败，而不是
 * 报一句「已导出」。结果（路径 + 车型数 + 束数）只打在聊天栏 - 按钮没有别的反馈渠道，
 * 和「重载内置车型」同一个约定。
 */
final class BeamListScreen extends Screen {
    private static final Identifier OPTIONS_BACKGROUND =
            new Identifier("textures/gui/options_background.png");

    private static final int ROW_H = 20;
    private static final int PITCH = 25;
    private static final int GAP = 8;
    private static final int MIN_COLUMN = 200;
    private static final int MIN_CELL = 84;
    /** 勾选框列。 */
    private static final int SEL_W = 20;
    private static final int TITLE_Y = 22;
    private static final int HINT_Y = 36;
    private static final int CONTENT_TOP = 58;
    /** 两行底栏：新增/删除/导出 与 放弃修改/返回。 */
    private static final int FOOTER_H = 70;
    private static final int FOOTER_ROW_GAP = 6;
    private static final int LIST_BOTTOM_PAD = 20;

    private final Screen parent;
    private final Screen root;
    /** 「放弃修改」之后重建一次：会话里的束都要从资源包 / 自动识别重新取。 */
    private BeamSession session;
    private boolean discarded;

    private final List<ClickableWidget> dynamicRows = new ArrayList<>();
    /** 最后一行的提示（「至少保留一束」/ 导出结果），下一次操作后清掉。 */
    private String notice = "";

    private int colX;
    private int colW;
    private int rowX;
    private int rowW;
    private int listTop;
    private int visibleRows = 1;
    private int scrollRow;

    BeamListScreen(final Screen parent, final Screen root, final BeamSession session) {
        super(tr("beam.title.list", session.vehicleId));
        this.parent = parent;
        this.root = root == null ? parent : root;
        this.session = session;
    }

    static MutableText tr(final String key, final Object... args) {
        return Text.translatable(key, args);
    }

    private int viewBottom() {
        return this.height - FOOTER_H - LIST_BOTTOM_PAD;
    }

    // ---------------------------------------------------------------------------------------------
    // Init / layout
    // ---------------------------------------------------------------------------------------------

    @Override
    protected void init() {
        if (this.discarded) {
            // 放弃修改之后：运行时层已经清掉，会话要按资源包 + 自动识别重新取一次。
            this.discarded = false;
            this.scrollRow = 0;
            this.notice = "";
            this.session = BeamSession.open(this.session.vehicleId);
        }
        int need = 0;
        for (int i = 0; i < this.session.size(); i++) {
            need = Math.max(need, textWidth(Text.literal(rowText(this.session.beam(i), i))));
        }
        // 底栏：三等分那一行里最宽的按钮要先撑得下，列宽才能同时装下它。
        int cellNeed = 0;
        for (final Text label : List.of(tr("btn.beamAdd"), tr("btn.beamDelete"), tr("btn.beamExport"),
                tr("btn.beamDiscard"))) {
            cellNeed = Math.max(cellNeed, textWidth(label) + 12);
        }
        final int charW = this.textRenderer.getWidth("测");
        final int maxCol = Math.max(MIN_COLUMN, this.width - 24);
        this.colW = Math.min(maxCol, Math.max(MIN_COLUMN, Math.max(
                need + SEL_W + GAP + charW,
                cellNeed * 3 + GAP * 2)));
        this.colX = this.width / 2 - this.colW / 2;
        this.rowX = this.colX + SEL_W + GAP;
        this.rowW = this.colW - SEL_W - GAP;

        this.visibleRows = Math.max(1, (viewBottom() - CONTENT_TOP + (PITCH - ROW_H)) / PITCH);
        final int maxScroll = Math.max(0, this.session.size() - this.visibleRows);
        this.scrollRow = Math.max(0, Math.min(this.scrollRow, maxScroll));
        final int shownRows = Math.min(this.visibleRows, this.session.size());
        final int listH = shownRows * PITCH - (PITCH - ROW_H);
        this.listTop = maxScroll == 0
                ? CONTENT_TOP + Math.max(0, (viewBottom() - CONTENT_TOP - listH) / 2)
                : CONTENT_TOP;

        rebuildRows();
    }

    private int textWidth(final Text text) {
        return this.textRenderer.getWidth(text.getString());
    }

    /** {@code #1 车头 #FFF3D6 16.0}. */
    private String rowText(final Beam beam, final int index) {
        if (beam == null) {
            return "?";
        }
        final String length = beam.length == null ? "?" : String.format(Locale.ROOT, "%.1f",
                beam.length.doubleValue());
        return "#" + (index + 1) + " "
                + BeamSession.typeName(BeamSession.typeOf(beam)) + " "
                + BeamSession.hex(beam.color) + " " + length;
    }

    // ---------------------------------------------------------------------------------------------
    // Rows / footer
    // ---------------------------------------------------------------------------------------------

    private void rebuildRows() {
        for (final ClickableWidget widget : this.dynamicRows) {
            this.remove(widget);
        }
        this.dynamicRows.clear();

        final int last = Math.min(this.session.size(), this.scrollRow + this.visibleRows);
        for (int i = this.scrollRow; i < last; i++) {
            final int index = i;
            final Beam beam = this.session.beam(i);
            final int y = this.listTop + (i - this.scrollRow) * PITCH;

            final boolean picked = this.session.selected.contains(Integer.valueOf(i));
            final ButtonWidget box = ButtonWidget.builder(
                            Text.literal(picked ? "☑" : "☐"),
                            b -> toggle(index))
                    .dimensions(this.colX, y, SEL_W, ROW_H).build();
            attach(box);

            final String label = this.textRenderer.trimToWidth(rowText(beam, i), this.rowW - 12);
            attach(ButtonWidget.builder(Text.literal(label), b -> open(index))
                    .dimensions(this.rowX, y, this.rowW, ROW_H).build());
        }
        buildFooter();
    }

    private void buildFooter() {
        final int rowA = this.height - FOOTER_H;
        final int cell3 = (this.colW - GAP * 2) / 3;
        final int barW = cell3 * 3 + GAP * 2;
        final int barX = this.width / 2 - barW / 2;
        attach(ButtonWidget.builder(tr("btn.beamAdd"), b -> addBeam())
                .dimensions(barX, rowA, cell3, ROW_H).build());
        final ButtonWidget delete = ButtonWidget.builder(tr("btn.beamDelete"), b -> deleteSelected())
                .dimensions(barX + cell3 + GAP, rowA, cell3, ROW_H).build();
        delete.active = !this.session.selected.isEmpty();
        attach(delete);
        attach(ButtonWidget.builder(tr("btn.beamExport"), b -> export())
                .dimensions(barX + (cell3 + GAP) * 2, rowA, cell3, ROW_H).build());

        final int rowB = rowA + ROW_H + FOOTER_ROW_GAP;
        final int cell2 = (this.colW - GAP) / 2;
        final int x2 = this.width / 2 - (cell2 * 2 + GAP) / 2;
        final ButtonWidget discard = ButtonWidget.builder(tr("btn.beamDiscard"), b -> confirmDiscard())
                .dimensions(x2, rowB, cell2, ROW_H).build();
        discard.active = BeamSession.editedCount() > 0;
        attach(discard);
        attach(ButtonWidget.builder(tr("btn.back"), b -> close())
                .dimensions(x2 + cell2 + GAP, rowB, cell2, ROW_H).build());
    }

    private void attach(final ClickableWidget widget) {
        this.addDrawableChild(widget);
        this.dynamicRows.add(widget);
    }

    // ---------------------------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------------------------

    private void toggle(final int index) {
        final Integer key = Integer.valueOf(index);
        if (!this.session.selected.remove(key)) {
            this.session.selected.add(key);
        }
        this.notice = "";
        reinit();
    }

    private void open(final int index) {
        if (this.client == null) {
            return;
        }
        this.client.setScreen(new BeamEditScreen(this, this.root, this.session, index));
    }

    private void addBeam() {
        final int index = this.session.add();
        this.notice = "";
        // 新增后直接进编辑页：新束的位置 / 长度都是从上一束复制来的，玩家多半是来改它的。
        open(index);
    }

    private void deleteSelected() {
        if (!this.session.deleteSelected()) {
            this.notice = tr("beam.keepOne").getString();
            reinit();
            return;
        }
        this.notice = "";
        reinit();
    }

    /** 导出 + 读回校验，结果进聊天栏（按钮本身没有别的反馈）。 */
    private void export() {
        final String result = BeamSession.exportFile();
        final MinecraftClient client = MinecraftClient.getInstance();
        final Text message;
        if (result.startsWith("!")) {
            message = tr("beam.export.fail", result.substring(1)).formatted(Formatting.RED);
            LteLog.LOGGER.warn("[lte] beam editor: export failed: {}", result.substring(1));
        } else {
            final String[] parts = result.split("\\|");
            message = tr("beam.export.ok", parts.length > 0 ? parts[0] : "?",
                    parts.length > 1 ? parts[1] : "?", BeamSession.exportPath().toString())
                    .formatted(Formatting.GREEN);
            LteLog.LOGGER.info("[lte] beam editor: exported {} vehicle(s) to {}",
                    parts.length > 0 ? parts[0] : "?", BeamSession.exportPath());
        }
        if (client.player != null) {
            client.player.sendMessage(message, false);
        }
        this.notice = message.getString();
        reinit();
    }

    /** 放弃修改：确认后清掉本次会话写过的运行时覆盖（资源包层、已导出的文件都不动）。 */
    private void confirmDiscard() {
        if (this.client == null) {
            return;
        }
        this.client.setScreen(new ResetConfirmScreen(this, tr("beam.discard.title"),
                tr("beam.discard.confirm", Integer.valueOf(BeamSession.editedCount())),
                () -> {
                    final int removed = BeamSession.abandon();
                    // 确认页自己会回到这一页（它的 parent 就是这里）；这里只置标志，
                    // 让 init() 把会话按资源包 + 自动识别重建，屏幕上立刻显示放弃后的样子。
                    this.discarded = true;
                    LteLog.LOGGER.info("[lte] beam editor: abandoned {} vehicle override(s)", removed);
                }));
    }

    private void reinit() {
        this.clearAndInit();
    }

    // ---------------------------------------------------------------------------------------------
    // Render / input
    // ---------------------------------------------------------------------------------------------

    @Override
    public void render(final DrawContext context, final int mouseX, final int mouseY, final float delta) {
        context.drawTexture(OPTIONS_BACKGROUND, 0, 0, 0, 0, this.width, this.height, 32, 32);
        context.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, TITLE_Y, 0xFFFFFF);
        // 束数 / 选中数每帧重算：增删之后就对了，不需要重建整个屏幕的标题。
        context.drawCenteredTextWithShadow(this.textRenderer,
                tr("beam.count", Integer.valueOf(this.session.size()),
                        Integer.valueOf(this.session.selected.size())),
                this.width / 2, HINT_Y, 0xB0B0B0);

        if (this.session.size() == 0) {
            context.drawCenteredTextWithShadow(this.textRenderer, tr("beam.empty"),
                    this.width / 2, CONTENT_TOP + 16, 0xFFD37F);
        }
        if (this.session.size() > this.visibleRows) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    tr("config.scrollHint", Integer.valueOf(this.scrollRow + 1),
                            Integer.valueOf(this.session.size())),
                    this.width / 2, this.height - FOOTER_H - 14, 0x909090);
            drawScrollBar(context);
        }
        final String hint = this.notice.isEmpty() ? tr("beam.hint.list").getString() : this.notice;
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(this.textRenderer.trimToWidth(hint, Math.max(80, this.width - 32))),
                this.width / 2, this.height - 14, this.notice.isEmpty() ? 0x909090 : 0xFFD37F);
    }

    private void drawScrollBar(final DrawContext context) {
        final int top = CONTENT_TOP;
        final int bottom = this.listTop + this.visibleRows * PITCH - (PITCH - ROW_H);
        final int x = this.colX + this.colW + 4;
        context.fill(x, top, x + 1, Math.max(top, bottom), 0x60FFFFFF);
        final int trackH = Math.max(1, bottom - top);
        final int thumbH = Math.max(12, trackH * this.visibleRows / Math.max(1, this.session.size()));
        final int span = Math.max(1, this.session.size() - this.visibleRows);
        final int thumbY = top + (trackH - thumbH) * this.scrollRow / span;
        context.fill(x - 1, thumbY, x + 2, thumbY + thumbH, 0xFFFFFFFF);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** ESC / 返回：回车型列表（逐层返回的第 2 层）。 */
    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double amount) {
        if (this.session.size() > this.visibleRows && amount != 0.0D) {
            this.scrollRow -= (int) Math.signum(amount);
            reinit();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        final int page = Math.max(1, this.visibleRows - 1);
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            return scrollBy(page);
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            return scrollBy(-page);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private boolean scrollBy(final int delta) {
        if (this.session.size() <= this.visibleRows) {
            return false;
        }
        this.scrollRow += delta;
        reinit();
        return true;
    }
}
