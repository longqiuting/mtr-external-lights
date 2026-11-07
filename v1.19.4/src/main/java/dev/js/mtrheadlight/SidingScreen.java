package dev.js.mtrheadlight;

import mtr.client.ClientData;
import mtr.data.Depot;
import mtr.data.Siding;
import com.mojang.blaze3d.systems.RenderSystem;
import static net.minecraft.client.gui.DrawableHelper.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The siding list of the "按侧线配置" screen: one row per siding, its current light state on the
 * right, and the whole row opens {@link SidingDetailScreen}.
 *
 * <p>A train carries the siding it came from ({@code Train.sidingId}), so this is a direct map from
 * the player's choice to the light filter, with no guessing about depots or route names.
 *
 * <h2>Why there is a search box and why the grouping is a guess</h2>
 * <p>MTR never sends the siding-to-depot relation to the client: {@code Siding.depot} is a private
 * field filled only by {@code Siding.setSidingData(Level, Depot, rails)} on the server, it has no
 * getter, and neither {@code Siding.writePacket} nor its packet constructor carries a depot id.
 * {@code Depot.deployableSidings} is a server-side map as well. What the client <em>does</em> get is
 * both ends of every siding ({@code SavedRailBase} positions, so {@code getMidPos()} works) and the
 * four corners plus the name of every depot ({@code AreaBase.corner1/corner2}, both public).
 *
 * <p>So the grouping below is a <b>heuristic</b>: a siding belongs to the depot whose area contains
 * its midpoint; failing that, to the closest depot area within {@link #NEAR_DEPOT} blocks. It is
 * right for the normal case (sidings stand inside the depot area) and can be wrong for tracks that
 * run between two neighbouring depots or for a long siding that leaves the area it is stored in.
 * Those are exactly the cases the search box covers, so the text field matches the siding id, the
 * siding name <em>and</em> the name of the depot it was grouped under: typing a depot name lists
 * that depot's sidings whether or not the grouping guessed right.
 *
 * <h2>Layout rules (why the numbers look like this)</h2>
 * <ul>
 *   <li>One column width for the list and the bottom bar, at least as wide as the widest row label
 *       measured with the real font ({@link #textWidth}) plus button padding. The column is
 *       measured over <em>all</em> sidings, never over the filtered ones, so typing in the search
 *       box does not make the list jump sideways.</li>
 *   <li>Each row is a button of {@code colW - STATUS_W - GAP} plus a right-aligned state column of
 *       {@code STATUS_W}; every button in the list has exactly the same width. A group header is a
 *       painted band of the full column width with no widget, so it can never steal a click.</li>
 *   <li>Title (22) and hint (36) own the top, then the search row (46..64) and, on narrow windows,
 *       a second row (68..86) with the depot toggle on the left half and the 地图选择 button on the
 *       right half. The paging bar owns {@code height-44..height-24}. Pages are cut
 *       by real pixel height (headers are shorter than rows), so on short windows fewer rows fit -
 *       rows are never allowed to paint over the bar.</li>
 * </ul>
 */
public final class SidingScreen extends Screen {
    private static final Identifier OPTIONS_BACKGROUND =
            new Identifier("textures/gui/options_background.png");

    /** Upper bound on rows per page; the real page size is whatever fits the window. */
    private static final int MAX_ROWS = 8;
    private static final int ROW_H = 20;
    private static final int ROW_PITCH_MAX = 24;
    /** A group header is shorter than a row: text only, no button. */
    private static final int HEADER_H = 14;
    private static final int LABEL_PAD = 8;
    private static final int GAP = 8;
    private static final int TITLE_Y = 22;
    private static final int HINT_Y = 36;
    private static final int SEARCH_Y = 46;
    private static final int SEARCH_H = 18;
    private static final int FOOTER_H = 44;
    /** Where the list starts when there is no search row at all (no sidings in the world). */
    private static final int LEGACY_LIST_TOP = 58;
    /** Narrowest list column the screen will draw (the window can still force it narrower). */
    private static final int MIN_COLUMN = 200;
    /** Width of the right-hand state column ("启用" / "已停用" / "自定义"). */
    private static final int STATUS_W = 56;
    /** Width of the "✕" that empties the search box. */
    private static final int CLEAR_W = 18;
    private static final int GROUP_BTN_W = 112;
    /** Width of the "地图选择" / "Map" button that opens {@link SidingMapScreen}. */
    private static final int MAP_BTN_W = 88;
    /**
     * Search box and group toggle share one line only when the column is at least this wide: the
     * old 336 plus the map button and its gap.
     */
    private static final int ONE_ROW_MIN = 336 + MAP_BTN_W + GAP;
    /** Least vertical room (header + one row) that still justifies drawing the search row. */
    private static final int MIN_LIST_SPAN = 44;
    /** A siding whose midpoint is at most this far outside a depot area still counts as that depot's. */
    private static final int NEAR_DEPOT = 48;

    private final Screen parent;

    /** Every siding of the world, sorted by label - never filtered, so the column width is stable. */
    private List<Siding> all = List.of();
    /** Filtered and (optionally) grouped: header rows interleaved with siding rows. */
    private List<Row> entries = List.of();
    /** Index into {@link #entries} where each page starts; built by walking the real pixel heights. */
    private final List<Integer> pageStarts = new ArrayList<>();
    /** What the current page actually shows; parallel to {@link #rowY}/{@link #rowState}. */
    private List<Row> visible = List.of();

    private final Map<Long, Depot> depotById = new LinkedHashMap<>();
    /** Siding id -> depot id, {@code 0} when no depot could be guessed. */
    private final Map<Long, Long> sidingDepot = new HashMap<>();
    private boolean hasDepots;

    /** Widgets rebuilt on every filter/page change; the search box itself is not one of them. */
    private final List<ClickableWidget> dynamicRows = new ArrayList<>();

    private TextFieldWidget searchField;
    private ButtonWidget clearButton;
    private ButtonWidget groupButton;

    private String query = "";
    private boolean grouped = true;
    private int page;

    private int left;
    private int colW;
    private int rowW;
    private int statusRight;
    private int listTop = LEGACY_LIST_TOP;
    private int listBottom;
    private int pitch = ROW_H + 2;
    private boolean oneRow = true;
    private boolean topBar = true;

    private int[] rowY = new int[0];
    private Text[] rowState = new Text[0];
    private int[] rowColor = new int[0];

    public SidingScreen(final Screen parent) {
        super(tr("sidings.title"));
        this.parent = parent;
    }

    private static MutableText tr(final String key, final Object... args) {
        return Text.translatable(key, args);
    }

    // ---------------------------------------------------------------------------------------------
    // Init / layout
    // ---------------------------------------------------------------------------------------------

    @Override
    protected void init() {
        final List<Siding> loaded = new ArrayList<>();
        try {
            for (final Siding siding : ClientData.SIDINGS) {
                // Drop nulls (a detail screen for "no siding" is the global editor) and id 0 (MTR
                // treats sidingId 0 as "no siding", so it cannot be configured separately).
                if (siding != null && siding.id != 0L) {
                    loaded.add(siding);
                }
            }
        } catch (RuntimeException e) {
            MtrHeadlightClient.LOGGER.warn("[mtr-headlight] could not read SIDINGS", e);
        }
        loaded.sort(Comparator.comparing(ParameterScreen::describe));
        this.all = loaded;

        loadDepots();
        computeGeometry();
        buildTopBar();
        rebuildRows();
    }

    /**
     * Reads the depots and guesses, once per screen, which depot each siding stands in. The guess is
     * only for the grouping headers - it never changes what a row does when clicked.
     */
    private void loadDepots() {
        this.depotById.clear();
        this.sidingDepot.clear();
        final List<Depot> depots = new ArrayList<>();
        try {
            for (final Depot depot : ClientData.DEPOTS) {
                if (depot != null && depot.id != 0L) {
                    depots.add(depot);
                    this.depotById.put(depot.id, depot);
                }
            }
        } catch (RuntimeException e) {
            MtrHeadlightClient.LOGGER.warn("[mtr-headlight] could not read DEPOTS", e);
        }
        this.hasDepots = !depots.isEmpty();
        for (final Siding siding : this.all) {
            this.sidingDepot.put(siding.id, nearestDepot(siding, depots));
        }
    }

    /**
     * The depot a siding most likely belongs to, or {@code 0}.
     *
     * <p>Inside a depot area wins (smallest area first, so a depot nested inside another keeps its
     * own sidings); otherwise the nearest area within {@link #NEAR_DEPOT} blocks. Everything else is
     * "no nearby depot" - a deliberate bucket rather than a random assignment.
     */
    private static long nearestDepot(final Siding siding, final List<Depot> depots) {
        if (depots.isEmpty()) {
            return 0L;
        }
        final int px;
        final int pz;
        try {
            // MTR is compiled against intermediary names, so its BlockPos arrives as
            // net.minecraft.class_2338 and the coordinate getters carry the intermediary names the
            // MTR bytecode itself calls (method_10263 = getX, method_10260 = getZ). Both ends of
            // the siding are in its packet, so this is valid on the client.
            final var mid = siding.getMidPos();
            px = mid.method_10263();
            pz = mid.method_10260();
        } catch (RuntimeException e) {
            return 0L;
        }

        Depot inside = null;
        double insideArea = Double.MAX_VALUE;
        Depot near = null;
        double nearDist = Double.MAX_VALUE;
        for (final Depot depot : depots) {
            if (depot.corner1 == null || depot.corner2 == null) {
                continue;
            }
            // Same story as getMidPos(): AreaBase's corners are a net.minecraft.class_3545 (Pair)
            // whose accessors only exist under their intermediary names on MTR's classpath.
            final int x1 = depot.corner1.method_15442();
            final int z1 = depot.corner1.method_15441();
            final int x2 = depot.corner2.method_15442();
            final int z2 = depot.corner2.method_15441();
            final int minX = Math.min(x1, x2);
            final int maxX = Math.max(x1, x2);
            final int minZ = Math.min(z1, z2);
            final int maxZ = Math.max(z1, z2);
            // A depot saved without corners reaches the client as (0,0)-(0,0); that is not a place.
            if (minX == 0 && maxX == 0 && minZ == 0 && maxZ == 0) {
                continue;
            }
            final double dx = px < minX ? minX - px : (px > maxX ? px - maxX : 0);
            final double dz = pz < minZ ? minZ - pz : (pz > maxZ ? pz - maxZ : 0);
            if (dx == 0 && dz == 0) {
                final double area = (double) (maxX - minX + 1) * (maxZ - minZ + 1);
                if (area < insideArea) {
                    insideArea = area;
                    inside = depot;
                }
            } else {
                final double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist < nearDist) {
                    nearDist = dist;
                    near = depot;
                }
            }
        }
        if (inside != null) {
            return inside.id;
        }
        return near != null && nearDist <= NEAR_DEPOT ? near.id : 0L;
    }

    /** Column width, row pitch, and where the list starts, all from the window and the real font. */
    private void computeGeometry() {
        final int cx = this.width / 2;
        final int charW = this.textRenderer.getWidth("测");
        int needed = 0;
        for (final Siding siding : this.all) {
            needed = Math.max(needed, textWidth(Text.literal(ParameterScreen.describe(siding))));
        }
        // Same rule as ParameterScreen: the longest label plus two characters, not a fixed 360.
        final int maxCol = Math.max(MIN_COLUMN, this.width - 32);
        this.colW = Math.min(maxCol, Math.max(MIN_COLUMN, needed + STATUS_W + GAP + charW * 2));
        this.left = cx - this.colW / 2;
        this.statusRight = this.left + this.colW;
        this.rowW = this.colW - STATUS_W - GAP;

        this.oneRow = this.colW >= ONE_ROW_MIN;
        this.listBottom = this.height - FOOTER_H - GAP;
        // Where the list would start with the search row (and, on narrow windows, the toggle).
        final int searchTop = this.oneRow
                ? SEARCH_Y + SEARCH_H + GAP
                : SEARCH_Y + SEARCH_H + 4 + SEARCH_H + 6;
        // A window this short cannot hold a depot header plus one row below the search box; the
        // list then falls back to the old top (58) and the search row is not built at all, instead
        // of squeezing rows under the paging bar.
        this.topBar = !this.all.isEmpty() && this.listBottom - searchTop >= MIN_LIST_SPAN;
        this.listTop = this.topBar ? searchTop : LEGACY_LIST_TOP;
        final int avail = Math.max(ROW_H + 2, this.listBottom - this.listTop);
        this.pitch = Math.min(ROW_PITCH_MAX, Math.max(ROW_H + 2, avail / MAX_ROWS));
    }

    /** Search box, its "✕", and the depot-grouping toggle. These survive filter changes. */
    private void buildTopBar() {
        if (!this.topBar) {
            return;
        }
        final int fieldW = this.oneRow
                ? this.colW - CLEAR_W - GAP - GROUP_BTN_W - GAP - MAP_BTN_W - GAP
                : this.colW - CLEAR_W - GAP;

        this.searchField = new TextFieldWidget(this.textRenderer, this.left, SEARCH_Y,
                Math.max(40, fieldW), SEARCH_H, tr("sidings.search"));
        this.searchField.setMaxLength(64);
        this.searchField.setPlaceholder(tr("sidings.search"));
        // Unlocked focus so the cursor can be put in the box by a click; no text predicate, so
        // IME-composed Chinese goes in untouched.
        this.searchField.setFocusUnlocked(true);
        this.searchField.setText(this.query);
        this.searchField.setChangedListener(this::onQueryChanged);
        this.addDrawableChild(this.searchField);

        this.clearButton = this.addDrawableChild(ButtonWidget.builder(tr("btn.clearSearch"), b -> {
            if (this.searchField != null) {
                // setText fires the changed listener, which refreshes the list.
                this.searchField.setText("");
                this.searchField.setFocused(true);
            }
        }).dimensions(this.left + fieldW + GAP, SEARCH_Y, CLEAR_W, SEARCH_H).build());

        // Group toggle and the map button: side by side on one line when the column is wide enough
        // for both, otherwise the toggle takes the left half of its own line and the map button the
        // right half - never a fixed width that clips either label.
        final int halfW = (this.colW - GAP) / 2;
        final int gx = this.oneRow
                ? this.left + this.colW - GROUP_BTN_W - GAP - MAP_BTN_W : this.left;
        final int gy = this.oneRow ? SEARCH_Y : SEARCH_Y + SEARCH_H + 4;
        final int gw = this.oneRow ? GROUP_BTN_W : halfW;
        this.groupButton = this.addDrawableChild(ButtonWidget.builder(groupLabel(), b -> {
            this.grouped = !this.grouped;
            this.page = 0;
            b.setMessage(groupLabel());
            rebuildRows();
        }).dimensions(gx, gy, gw, SEARCH_H).build());
        this.groupButton.active = this.hasDepots;

        final int mx = this.oneRow ? this.left + this.colW - MAP_BTN_W : this.left + halfW + GAP;
        final int mw = this.oneRow ? MAP_BTN_W : this.colW - halfW - GAP;
        this.addDrawableChild(ButtonWidget.builder(tr("btn.map"), b -> openMap())
                .dimensions(mx, gy, mw, SEARCH_H).build());
    }

    private Text groupLabel() {
        if (!this.hasDepots) {
            return tr("sidings.group.none");
        }
        return tr(this.grouped ? "sidings.group.on" : "sidings.group.off");
    }

    private void onQueryChanged(final String text) {
        final String q = text == null ? "" : text.trim();
        if (q.equals(this.query)) {
            return;
        }
        this.query = q;
        this.page = 0;
        rebuildRows();
    }

    // ---------------------------------------------------------------------------------------------
    // Rows: filtering, grouping, paging
    // ---------------------------------------------------------------------------------------------

    /**
     * Rebuilds the list widgets (rows and the bottom bar) without touching the search box, so the
     * cursor and the IME composition survive a keystroke. The old widgets are removed from the
     * screen first; the box, "✕" and the toggle are permanent children.
     */
    private void rebuildRows() {
        for (final ClickableWidget widget : this.dynamicRows) {
            this.remove(widget);
        }
        this.dynamicRows.clear();

        rebuildEntries();
        paginate();
        if (this.pageStarts.isEmpty()) {
            this.page = 0;
        } else {
            this.page = Math.max(0, Math.min(this.page, this.pageStarts.size() - 1));
        }

        final List<Row> shown = new ArrayList<>();
        if (!this.pageStarts.isEmpty()) {
            final int start = this.pageStarts.get(this.page);
            final int end = this.page + 1 < this.pageStarts.size()
                    ? this.pageStarts.get(this.page + 1)
                    : this.entries.size();
            for (int i = start; i < end && i < this.entries.size(); i++) {
                shown.add(this.entries.get(i));
            }
        }
        this.visible = shown;

        this.rowY = new int[shown.size()];
        this.rowState = new Text[shown.size()];
        this.rowColor = new int[shown.size()];

        final HeadlightConfig cfg = MtrHeadlightClient.config();
        int y = this.listTop;
        for (int i = 0; i < shown.size(); i++) {
            final Row row = shown.get(i);
            this.rowY[i] = y;
            if (row.header != null) {
                y += HEADER_H + 2;
                continue;
            }
            final Siding siding = row.siding;
            final String label = this.textRenderer.trimToWidth(
                    ParameterScreen.describe(siding), this.rowW - LABEL_PAD * 2);
            final ButtonWidget button = ButtonWidget.builder(Text.literal(label), b -> open(siding))
                    .dimensions(this.left, y, this.rowW, ROW_H).build();
            this.attach(button);
            this.rowState[i] = stateLabel(cfg, siding);
            this.rowColor[i] = stateColor(cfg, siding);
            y += this.pitch;
        }

        if (this.clearButton != null) {
            this.clearButton.active = !this.query.isEmpty();
        }
        buildFooter();
    }

    /** Applies the query, then either one flat list or one block per guessed depot. */
    private void rebuildEntries() {
        final List<Siding> filtered = new ArrayList<>();
        for (final Siding siding : this.all) {
            if (this.query.isEmpty() || matches(siding, this.query)) {
                filtered.add(siding);
            }
        }

        final List<Row> out = new ArrayList<>();
        if (!this.grouped || !this.hasDepots) {
            for (final Siding siding : filtered) {
                out.add(Row.of(siding));
            }
            this.entries = out;
            return;
        }

        final Map<Long, List<Siding>> groups = new LinkedHashMap<>();
        for (final Siding siding : filtered) {
            groups.computeIfAbsent(depotOf(siding.id), key -> new ArrayList<>()).add(siding);
        }
        final List<Long> keys = new ArrayList<>(groups.keySet());
        keys.sort((a, b) -> {
            if (a == 0L) {
                return b == 0L ? 0 : 1; // "no nearby depot" last
            }
            if (b == 0L) {
                return -1;
            }
            return groupName(a).compareToIgnoreCase(groupName(b));
        });
        for (final Long key : keys) {
            final List<Siding> members = groups.get(key);
            out.add(Row.header(tr("sidings.groupHeader", groupName(key), members.size())));
            for (final Siding siding : members) {
                out.add(Row.of(siding));
            }
        }
        this.entries = out;
    }

    /**
     * Cuts pages by walking the entries at their real pixel heights. A header is only placed when
     * its first row still fits below it, so no page can start or end with an orphan header.
     */
    private void paginate() {
        this.pageStarts.clear();
        int i = 0;
        while (i < this.entries.size()) {
            final int start = i;
            int y = this.listTop;
            while (i < this.entries.size()) {
                final Row row = this.entries.get(i);
                if (row.header != null) {
                    if (y + HEADER_H + 2 + this.pitch > this.listBottom) {
                        break;
                    }
                    y += HEADER_H + 2;
                } else {
                    if (y + this.pitch > this.listBottom) {
                        break;
                    }
                    y += this.pitch;
                }
                i++;
            }
            if (i == start) {
                break; // nothing fits at all (window too short): render() says so instead
            }
            this.pageStarts.add(start);
        }
    }

    /** Siding id, siding name, or the name of the depot it was grouped under. */
    private boolean matches(final Siding siding, final String rawQuery) {
        final String q = rawQuery.toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return true;
        }
        if (ParameterScreen.nameOf(siding).toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        // A leading '#' is the label's own marker, so "#12" and "12" both match id 12.
        final String digits = q.startsWith("#") ? q.substring(1) : q;
        if (!digits.isEmpty() && Long.toString(siding.id).contains(digits)) {
            return true;
        }
        return this.hasDepots && groupName(depotOf(siding.id)).toLowerCase(Locale.ROOT).contains(q);
    }

    private long depotOf(final long sidingId) {
        final Long depotId = this.sidingDepot.get(sidingId);
        return depotId == null ? 0L : depotId;
    }

    private String groupName(final long depotId) {
        if (depotId == 0L) {
            return tr("sidings.group.unmatched").getString();
        }
        final Depot depot = this.depotById.get(depotId);
        if (depot == null) {
            return tr("sidings.group.unmatched").getString();
        }
        final String name = depot.name;
        return name == null || name.isBlank()
                ? tr("sidings.group.unnamed", depotId).getString()
                : name;
    }

    private void buildFooter() {
        final int bottom = this.height - FOOTER_H;
        final int cx = this.width / 2;
        if (this.pageStarts.size() > 1) {
            final int cell = (this.colW - GAP * 2) / 3;
            final int barW = cell * 3 + GAP * 2;
            final int barX = cx - barW / 2;

            final ButtonWidget prev = ButtonWidget.builder(Text.literal("◀"), b -> {
                if (this.page > 0) {
                    this.page--;
                    rebuildRows();
                }
            }).dimensions(barX, bottom, cell, ROW_H).build();
            prev.active = this.page > 0;
            this.attach(prev);

            this.attach(ButtonWidget.builder(tr("btn.back"), b -> this.close())
                    .dimensions(barX + cell + GAP, bottom, cell, ROW_H).build());

            final ButtonWidget next = ButtonWidget.builder(Text.literal("▶"), b -> {
                if (this.page + 1 < this.pageStarts.size()) {
                    this.page++;
                    rebuildRows();
                }
            }).dimensions(barX + (cell + GAP) * 2, bottom, cell, ROW_H).build();
            next.active = this.page + 1 < this.pageStarts.size();
            this.attach(next);
        } else {
            // Without paging, 返回 takes the same half-column cell the detail/global footers use.
            final int backW = (this.colW - GAP) / 2;
            this.attach(ButtonWidget.builder(tr("btn.back"), b -> this.close())
                    .dimensions(cx - backW / 2, bottom, backW, ROW_H).build());
        }
    }

    private void attach(final ClickableWidget widget) {
        this.addDrawableChild(widget);
        this.dynamicRows.add(widget);
    }

    private void open(final Siding siding) {
        if (this.client != null && siding != null) {
            this.client.setScreen(new SidingDetailScreen(this, siding));
        }
    }

    /** The same list as a top-down plan; the two views are siblings, neither replaces the other. */
    private void openMap() {
        if (this.client != null) {
            this.client.setScreen(new SidingMapScreen(this));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Render
    // ---------------------------------------------------------------------------------------------

    /** 1.19 / 1.18 draw text from a String here, so the centring is done by hand. */
    private void drawCenteredText(final MatrixStack matrices, final Text text, final int x,
                                  final int y, final int color) {
        final String s = text.getString();
        this.textRenderer.drawWithShadow(matrices, s,
                (float) (x - this.textRenderer.getWidth(s) / 2), (float) y, color);
    }

    /** Width of a label in the real font ({@code String} overload: same in 1.18/1.19/1.20). */
    private int textWidth(final Text text) {
        return this.textRenderer.getWidth(text.getString());
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
        this.textRenderer.drawWithShadow(matrices, s,
                (float) (right - this.textRenderer.getWidth(s)), (float) y, color);
    }

    @Override
    public void render(final MatrixStack matrices, final int mouseX, final int mouseY, final float delta) {
        RenderSystem.setShaderTexture(0, OPTIONS_BACKGROUND);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        drawTexture(matrices, 0, 0, 0.0F, 0.0F, this.width, this.height, 32, 32);
        fillGradient(matrices, 0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
        super.render(matrices, mouseX, mouseY, delta);
        drawCenteredText(matrices, this.title, this.width / 2, TITLE_Y, 0xFFFFFF);

        // 34..45: the hint row doubles as the grouping disclaimer, so the guess is never silent.
        final boolean depotMode = this.grouped && this.hasDepots;
        final String hint = this.textRenderer.trimToWidth(
                tr(depotMode ? "sidings.groupHint" : "sidings.hint").getString(),
                Math.max(60, this.width - 32));
        drawCenteredText(matrices, Text.literal(hint),
                this.width / 2, HINT_Y, 0xB0B0B0);

        if (this.all.isEmpty()) {
            drawCenteredText(matrices, tr("sidings.empty"),
                    this.width / 2, LEGACY_LIST_TOP + 34, 0xFFD37F);
            drawCenteredText(matrices, tr("sidings.emptyHint"),
                    this.width / 2, LEGACY_LIST_TOP + 48, 0x909090);
            return;
        }

        if (this.entries.isEmpty()) {
            drawCenteredText(matrices, tr("sidings.noMatch"),
                    this.width / 2, this.listTop + 18, 0xFFD37F);
            drawCenteredText(matrices, tr("sidings.noMatchHint"),
                    this.width / 2, this.listTop + 32, 0x909090);
            return;
        }

        if (this.pageStarts.isEmpty()) {
            // Window too short for even one row: say so instead of painting over the paging bar.
            drawCenteredText(matrices, tr("sidings.tooShort"),
                    this.width / 2, this.listTop + 8, 0xFFD37F);
            return;
        }

        for (int i = 0; i < this.visible.size(); i++) {
            final Row row = this.visible.get(i);
            if (row.header != null) {
                fill(matrices, this.left, this.rowY[i], this.left + this.colW,
                        this.rowY[i] + HEADER_H - 1, 0x33FFFFFF);
                final String title = this.textRenderer.trimToWidth(
                        row.header.getString(), this.colW - 10);
                this.textRenderer.drawWithShadow(matrices, title,
                        (float) (this.left + 5), (float) (this.rowY[i] + 3), 0xFFD37F);
            } else if (this.rowState[i] != null) {
                drawRight(matrices, this.rowState[i], this.statusRight, this.rowY[i] + 6, this.rowColor[i]);
            }
        }
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (this.searchField != null && this.searchField.isFocused()) {
            // Escape empties the box first and only closes the screen once it is already empty.
            if (keyCode == GLFW.GLFW_KEY_ESCAPE && !this.query.isEmpty()) {
                this.searchField.setText("");
                return true;
            }
        } else if (this.pageStarts.size() > 1) {
            if (keyCode == GLFW.GLFW_KEY_PAGE_UP && this.page > 0) {
                this.page--;
                rebuildRows();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN && this.page + 1 < this.pageStarts.size()) {
                this.page++;
                rebuildRows();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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

    /** One line of the list: either a depot header (shorter, no widget) or a siding row. */
    private static final class Row {
        final Siding siding;
        final Text header;

        private Row(final Siding siding, final Text header) {
            this.siding = siding;
            this.header = header;
        }

        static Row of(final Siding siding) {
            return new Row(siding, null);
        }

        static Row header(final Text title) {
            return new Row(null, title);
        }
    }
}
