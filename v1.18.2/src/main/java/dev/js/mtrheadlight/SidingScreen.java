package dev.js.mtrheadlight;

import mtr.client.ClientData;
import mtr.data.Siding;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The siding list of the "按侧线配置" screen: one row per siding, its current light state on the
 * right, and the whole row opens {@link SidingDetailScreen}.
 *
 * <p>A train carries the siding it came from ({@code Train.sidingId}), so this is a direct map from
 * the player's choice to the light filter, with no guessing about depots or route names.
 *
 * <p>MTR does not send the siding-to-depot relation to the client ({@code Siding.depot} is set
 * server-side only), so the list cannot be grouped by depot; each row instead shows the siding id,
 * name, type, length and car count, which is what tells otherwise identically named sidings apart.
 *
 * <p>Sidings are never switched off from this screen - that is one control on the detail page,
 * next to the parameters it belongs with. The row only <em>reports</em> the state, so a click can
 * never silently change a train's light.
 *
 * <h2>Layout rules (why the numbers look like this)</h2>
 * <ul>
 *   <li>One column width for the list and the bottom bar, at least as wide as the widest row label
 *       measured with the real font ({@link #textWidth}) plus button padding.</li>
 *   <li>Each row is a button of {@code colW - STATUS_W - GAP} plus a right-aligned state column of
 *       {@code STATUS_W}; every button in the list has exactly the same width.</li>
 *   <li>Title (22) and hint (36) own the top; the paging bar owns {@code height-44..height-24}. The
 *       list must end 8px above the bar, so on short windows the page size shrinks - rows are never
 *       allowed to paint over the bar.</li>
 * </ul>
 */
public final class SidingScreen extends Screen {
    private static final Identifier OPTIONS_BACKGROUND =
            new Identifier("textures/gui/options_background.png");

    /** Upper bound on rows per page; the real page size is whatever fits the window. */
    private static final int MAX_ROWS = 8;
    private static final int ROW_H = 20;
    private static final int ROW_PITCH_MAX = 24;
    private static final int LABEL_PAD = 8;
    private static final int GAP = 8;
    private static final int LIST_TOP = 58;
    private static final int FOOTER_H = 44;
    /** Width of the right-hand state column ("✔ 启用" / "✘ 已停用"). */
    private static final int STATUS_W = 64;

    private final Screen parent;
    private List<Siding> sidings = List.of();
    private int page;
    private int perPage = MAX_ROWS;

    /** Per visible row: where its button sits, and what the state column says. */
    private int[] rowY = new int[0];
    private Text[] rowState = new Text[0];
    private int[] rowColor = new int[0];
    private int statusRight;

    public SidingScreen(final Screen parent) {
        super(tr("sidings.title"));
        this.parent = parent;
    }

    private static MutableText tr(final String key, final Object... args) {
        return new net.minecraft.text.TranslatableText(key, args);
    }

    @Override
    protected void init() {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        final List<Siding> loaded = new ArrayList<>();
        try {
            loaded.addAll(ClientData.SIDINGS);
        } catch (RuntimeException e) {
            MtrHeadlightClient.LOGGER.warn("[mtr-headlight] could not read SIDINGS", e);
        }
        loaded.sort(Comparator.comparing(ParameterScreen::describe));
        this.sidings = loaded;

        // ---- vertical: how many rows actually fit above the paging bar ----
        final int avail = Math.max(ROW_H, (this.height - FOOTER_H - GAP) - LIST_TOP);
        final int pitch = Math.min(ROW_PITCH_MAX, Math.max(ROW_H + 2, avail / MAX_ROWS));
        this.perPage = Math.max(1, Math.min(MAX_ROWS, avail / pitch));

        final int pages = Math.max(1, (this.sidings.size() + this.perPage - 1) / this.perPage);
        this.page = Math.max(0, Math.min(this.page, pages - 1));

        // ---- horizontal: one column, never narrower than the longest row label ----
        final int cx = this.width / 2;
        final int maxCol = Math.max(240, this.width - 40);
        int needed = 0;
        for (final Siding siding : this.sidings) {
            needed = Math.max(needed, textWidth(Text.of(ParameterScreen.describe(siding))));
        }
        final int colW = Math.min(maxCol, Math.max(360, needed + STATUS_W + GAP + LABEL_PAD * 2));
        final int left = cx - colW / 2;
        this.statusRight = left + colW;
        final int rowW = colW - STATUS_W - GAP;
        // Three equal cells for ◀ / 返回 / ▶ across the same column.
        final int cell = (colW - GAP * 2) / 3;

        final int first = this.page * this.perPage;
        final int last = Math.min(this.sidings.size(), first + this.perPage);
        final int visible = Math.max(0, last - first);
        this.rowY = new int[visible];
        this.rowState = new Text[visible];
        this.rowColor = new int[visible];

        int y = LIST_TOP;
        for (int i = first; i < last; i++) {
            final Siding siding = this.sidings.get(i);
            final int index = i - first;
            final String label = this.textRenderer.trimToWidth(
                    ParameterScreen.describe(siding), rowW - LABEL_PAD * 2);
            addButton(left, y, rowW, ROW_H, Text.of(label), b -> open(siding));
            this.rowY[index] = y;
            this.rowState[index] = stateLabel(cfg, siding);
            this.rowColor[index] = stateColor(cfg, siding);
            y += pitch;
        }

        final int bottom = this.height - FOOTER_H;
        if (pages > 1) {
            final int barW = cell * 3 + GAP * 2;
            final int barX = cx - barW / 2;
            addButton(barX, bottom, cell, ROW_H, Text.of("◀"), b -> {
                this.page--;
                reinit();
            });
            addButton(barX + cell + GAP, bottom, cell, ROW_H, tr("btn.back"), b -> this.close());
            addButton(barX + (cell + GAP) * 2, bottom, cell, ROW_H, Text.of("▶"), b -> {
                this.page++;
                reinit();
            });
        } else {
            // Without paging, 返回 keeps the same cell width and stays centred.
            addButton(cx - cell / 2, bottom, cell, ROW_H, tr("btn.back"), b -> this.close());
        }
    }

    private void open(final Siding siding) {
        if (this.client != null) {
            this.client.setScreen(new SidingDetailScreen(this, siding));
        }
    }

    /** Width of a label in the real font ({@code String} overload: same in 1.18/1.19/1.20). */
    private int textWidth(final Text text) {
        return this.textRenderer.getWidth(text.getString());
    }

    /**
     * Same tooltip contract as {@code ParameterScreen#addButton}: these buttons carry no tooltip.
     * That is safe here and on 1.19.4 ({@code ClickableWidget.applyTooltip()} null-checks the
     * field), while the 1.19.2/1.18.2 builds must pass {@code ButtonWidget.EMPTY} instead of a null
     * supplier - their {@code renderTooltip()} dereferences it on every hover (NPE, crash report on
     * 1.19.2). Never pass null to the raw constructor on those two versions.
     */
    private ButtonWidget addButton(final int x, final int y, final int w, final int h,
                                   final Text label, final ButtonWidget.PressAction action) {
        // 1.19.2/1.18.2 renderTooltip() dereferences the supplier unconditionally: null NPEs on hover.
        return this.addDrawableChild(new ButtonWidget(x, y, w, h, label, action, ButtonWidget.EMPTY));
    }

    private void reinit() {
        this.init(this.client, this.width, this.height);
    }

    /** 1.19/1.18 only draw text from a String here, so centre it by hand. */
    private void drawCenteredText(final MatrixStack matrices, final Text text, final int x, final int y,
                                  final int color) {
        final String s = text.getString();
        this.textRenderer.drawWithShadow(matrices, s, (float) (x - this.textRenderer.getWidth(s) / 2),
                (float) y, color);
    }


    /** Short state for the right column: off / on / on with its own parameters. */
    private static Text stateLabel(final HeadlightConfig cfg, final Siding siding) {
        final HeadlightConfig.Effective eff = cfg.effectiveFor(siding.id);
        if (!eff.enabled) {
            return tr("sidings.state.off");
        }
        return tr(eff.overridden ? "sidings.state.custom" : "sidings.state.on");
    }

    private static int stateColor(final HeadlightConfig cfg, final Siding siding) {
        final HeadlightConfig.Effective eff = cfg.effectiveFor(siding.id);
        if (!eff.enabled) {
            return 0xFF8080;
        }
        return eff.overridden ? 0xFFD37F : 0x9DFF9D;
    }

    /** Right-aligned text; the 1.18/1.19 generator swaps the body for the MatrixStack API. */
    private void drawRight(final MatrixStack matrices, final Text text, final int right, final int y,
                           final int color) {
        final String s = text.getString();
        this.textRenderer.drawWithShadow(matrices, s, (float) (right - this.textRenderer.getWidth(s)),
                (float) y, color);
    }

    @Override
    public void render(final MatrixStack matrices, final int mouseX, final int mouseY, final float delta) {
        RenderSystem.setShaderTexture(0, OPTIONS_BACKGROUND);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        drawTexture(matrices, 0, 0, 0.0F, 0.0F, this.width, this.height, 32, 32);
        fillGradient(matrices, 0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
        super.render(matrices, mouseX, mouseY, delta);
        drawCenteredText(matrices, this.title, this.width / 2, 22, 0xFFFFFF);
        // 36..45: ends above the first list row at LIST_TOP (58).
        drawCenteredText(matrices, tr("sidings.hint"),
                this.width / 2, 36, 0xB0B0B0);

        if (this.sidings.isEmpty()) {
            drawCenteredText(matrices, tr("sidings.empty"),
                    this.width / 2, LIST_TOP + 34, 0xFFD37F);
            drawCenteredText(matrices, tr("sidings.emptyHint"),
                    this.width / 2, LIST_TOP + 48, 0x909090);
            return;
        }

        for (int i = 0; i < this.rowY.length; i++) {
            drawRight(matrices, this.rowState[i], this.statusRight, this.rowY[i] + 6, this.rowColor[i]);
        }
    }

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
