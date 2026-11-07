package dev.js.mtrheadlight;

import mtr.client.ClientData;
import mtr.data.Depot;
import mtr.data.Siding;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The siding list as a map: a top-down 2D plan of the depots (rectangles) and the sidings (a dot
 * plus the line between the two saved rail ends), painted from the coordinates the server already
 * sends to the client.
 *
 * <h2>Where the coordinates come from (verified with javap on MTR 3.6.4.2 / 1.20.1)</h2>
 * <ul>
 *   <li>{@code AreaBase.corner1}/{@code corner2} are public {@code Pair<Integer,Integer>} fields
 *       holding {@code (x, z)}. They are <b>not</b> normalised - either corner may be the maximum -
 *       and {@code AreaBase.setCorners()} turns a corner whose two components are both {@code 0}
 *       into {@code null}. So a depot saved without corners reaches the client as
 *       {@code corner1 == null}, which is the only "no area" signal; the picture skips those
 *       instead of drawing a rectangle at the world origin. {@code AreaBase.nonNullCorners(depot)}
 *       is the public test for that.</li>
 *   <li>{@code SavedRailBase.positions} is a private {@code Set<BlockPos>} with <b>no</b>
 *       {@code getPos1()}/{@code getPos2()} getter. The public route to both ends is
 *       {@code getOrderedPositions(midPos, reversed)}, which returns the two positions of the set
 *       (order only). {@code getMidPos()} is their summed/floor-divided midpoint, and
 *       {@code getMidPos(true)} forces {@code y = 0}. A siding with fewer than two positions
 *       reports {@code (0,0,0)} for the missing end, so the picture falls back to a dot at the
 *       midpoint when the pair is not a pair.</li>
 *   <li>Every siding is axis-aligned ({@code getAxis()} returns X or Z), so the segment between
 *       the ends is a horizontal or vertical bar; a diagonal pair (data damaged or a future MTR)
 *       is still drawn, as a dotted line, rather than being lost.</li>
 * </ul>
 *
 * <h2>Projection</h2>
 * <pre>
 * world bbox  = (minX,minZ) .. (maxX,maxZ) over every depot corner and every siding end
 * baseScale   = min((mapW - 2*PAD) / spanX, (mapH - 2*PAD) / spanZ)      // blocks -&gt; pixels
 * s           = baseScale * zoom,  zoom in [0.5, 8]
 * sx(wx)      = mapCx + panX + (wx - bboxCx) * s
 * sy(wz)      = mapCy + panY + (wz - bboxCz) * s      // +Z is down, so north (-Z) is up
 * worldX(mx)  = bboxCx + (mx - mapCx - panX) / s
 * </pre>
 * Minecraft's north is {@code -Z}, so the vertical axis is deliberately not flipped: the plan reads
 * the same way as an MTR map, with the "北 / N" marker in the top-right corner.
 *
 * <h2>Interaction</h2>
 * <ul>
 *   <li>Wheel zooms 0.5x..8x <b>around the cursor</b>: the world point under the pointer is
 *       recomputed before and after the change and the pan is corrected so it stays put.</li>
 *   <li>Left-drag pans. A press that never moves more than {@link #DRAG_SLOP} pixels is a click,
 *       so panning can never accidentally open a siding.</li>
 *   <li>Click a siding (within {@link #HIT_PX} pixels of its dot, in screen space, so the target
 *       stays the same size at every zoom) -&gt; {@link SidingDetailScreen}. Click a depot rectangle
 *       -&gt; its sidings stay bright and the rest dim; clicking it again or clicking empty space
 *       clears the filter. Depot highlighting uses the <em>drawn</em> rectangle, so what is lit is
 *       exactly what the player sees enclosed.</li>
 *   <li>R / 重置视图 restores zoom 1 and no pan.</li>
 * </ul>
 *
 * <h2>Performance</h2>
 * The coordinate table ({@link Mark}/{@link DepotBox}, including the depot-in-area assignment) is
 * built once per screen in {@link #init()} - never per frame. Only the enabled/overridden state can
 * change while the screen is open (the detail page writes it), so {@link #refreshStates()} re-reads
 * just that, once per client tick. Rendering culls anything outside the canvas before touching a
 * single pixel, and the canvas is scissored so a zoomed-in plan cannot paint over the buttons.
 */
public final class SidingMapScreen extends Screen {
    private static final Identifier OPTIONS_BACKGROUND =
            new Identifier("textures/gui/options_background.png");

    private static final int TITLE_Y = 10;
    private static final int BAR_Y = 25;
    private static final int BAR_H = 18;
    private static final int MAP_INSET = 12;
    private static final int MAP_TOP = 64;
    private static final int FOOTER_H = 40;
    /** Empty margin between the plan and the canvas edge, in pixels. */
    private static final int PAD = 16;
    private static final double MIN_ZOOM = 0.5D;
    private static final double MAX_ZOOM = 8.0D;
    private static final double ZOOM_STEP = 1.15D;
    /** Screen-space radius (pixels) that still counts as a click on a siding dot. */
    private static final double HIT_PX = 8.0D;
    /** A press that moves less than this many pixels is a click, more is a pan. */
    private static final double DRAG_SLOP = 3.0D;
    private static final int RESET_W = 84;
    private static final int BACK_W = 160;
    /** Smallest plan the screen will paint; below this it says the window is too short. */
    private static final int MIN_CANVAS = 48;
    /** Never zoom a one-block world into a single pixel-width line. */
    private static final int MIN_SPAN = 32;
    /** Grid spacing candidates in blocks: the first one at least {@link #GRID_PX} apart wins. */
    private static final int[] GRID_STEPS = {8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384};
    private static final int GRID_PX = 56;

    private static final int COL_PANEL = 0xFF0C1014;
    private static final int COL_PANEL_EDGE = 0xFF2A3440;
    private static final int COL_GRID = 0x12FFFFFF;
    private static final int COL_DEPOT_FILL = 0x2E33A0FF;
    private static final int COL_DEPOT_EDGE = 0x8833A0FF;
    private static final int COL_DEPOT_HOT = 0x4D33A0FF;
    private static final int COL_DEPOT_SEL_FILL = 0x3AFFD37F;
    private static final int COL_DEPOT_SEL_EDGE = 0xCCFFD37F;
    private static final int COL_TRACK = 0x7038C6FF;
    private static final int COL_HOVER = 0xFFFFFFFF;

    private final Screen parent;

    /** Every siding of the world with its coordinates, built once per screen. */
    private final List<Mark> marks = new ArrayList<>();
    /** Every depot that has a usable area, built once per screen. */
    private final List<DepotBox> depotBoxes = new ArrayList<>();

    private int bboxMinX;
    private int bboxMinZ;
    private int bboxMaxX;
    private int bboxMaxZ;

    private double zoom = 1.0D;
    private double panX;
    private double panY;

    /** Cached per frame (and per hit test) so no drawing call recomputes the projection. */
    private double viewScale = 1.0D;
    private double viewCx;
    private double viewCz;
    private int viewMapCx;
    private int viewMapCy;

    private int mapX;
    private int mapY;
    private int mapW;
    private int mapH;

    private boolean pressed;
    private boolean panning;
    private boolean dragged;
    private double pressX;
    private double pressY;
    private double lastX;
    private double lastY;

    private int hoveredSiding = -1;
    private int hoveredDepot = -1;
    private int selectedDepot = -1;
    /** Set once so a broken config read cannot print a stack trace 20 times a second. */
    private boolean stateErrorLogged;

    public SidingMapScreen(final Screen parent) {
        super(tr("map.title"));
        this.parent = parent;
    }

    private static MutableText tr(final String key, final Object... args) {
        return Text.translatable(key, args);
    }

    // ---------------------------------------------------------------------------------------------
    // Init / model
    // ---------------------------------------------------------------------------------------------

    @Override
    protected void init() {
        this.mapX = MAP_INSET;
        this.mapY = MAP_TOP;
        this.mapW = Math.max(0, this.width - MAP_INSET * 2);
        this.mapH = Math.max(0, this.height - MAP_TOP - FOOTER_H - 6);

        buildModel();
        refreshStates();

        this.addDrawableChild(ButtonWidget.builder(tr("btn.mapReset"), b -> resetView())
                .dimensions(MAP_INSET, BAR_Y, RESET_W, BAR_H).build());

        final int backW = Math.min(BACK_W, Math.max(80, this.width - 32));
        this.addDrawableChild(ButtonWidget.builder(tr("btn.back"), b -> this.close())
                .dimensions(this.width / 2 - backW / 2, this.height - FOOTER_H + 4, backW, 20).build());
    }

    /**
     * Reads {@code ClientData.SIDINGS}/{@code DEPOTS} once and turns them into pixels-ready
     * records: world bounds, depot rectangles, and for every siding its midpoint, both ends and the
     * depot whose rectangle encloses it. Nothing here runs per frame.
     */
    private void buildModel() {
        this.marks.clear();
        this.depotBoxes.clear();

        final List<Siding> sidings = new ArrayList<>();
        try {
            for (final Siding siding : ClientData.SIDINGS) {
                // Same filter as the list screen: no nulls, and id 0 means "no siding" in MTR.
                if (siding != null && siding.id != 0L) {
                    sidings.add(siding);
                }
            }
        } catch (RuntimeException e) {
            MtrHeadlightClient.LOGGER.warn("[mtr-headlight] could not read SIDINGS for the map", e);
        }
        sidings.sort(Comparator.comparing(ParameterScreen::describe));

        try {
            for (final Depot depot : ClientData.DEPOTS) {
                if (depot != null && depot.id != 0L) {
                    final DepotBox box = DepotBox.of(depot);
                    if (box != null) {
                        this.depotBoxes.add(box);
                    }
                }
            }
        } catch (RuntimeException e) {
            MtrHeadlightClient.LOGGER.warn("[mtr-headlight] could not read DEPOTS for the map", e);
        }
        this.depotBoxes.sort((a, b) -> a.name.compareToIgnoreCase(b.name));

        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (final DepotBox box : this.depotBoxes) {
            minX = Math.min(minX, box.minX);
            minZ = Math.min(minZ, box.minZ);
            maxX = Math.max(maxX, box.maxX);
            maxZ = Math.max(maxZ, box.maxZ);
        }

        for (final Siding siding : sidings) {
            final Mark mark = Mark.of(siding);
            if (mark == null) {
                continue;
            }
            this.marks.add(mark);
            minX = Math.min(minX, Math.min(mark.midX, Math.min(mark.ax, mark.bx)));
            maxX = Math.max(maxX, Math.max(mark.midX, Math.max(mark.ax, mark.bx)));
            minZ = Math.min(minZ, Math.min(mark.midZ, Math.min(mark.az, mark.bz)));
            maxZ = Math.max(maxZ, Math.max(mark.midZ, Math.max(mark.az, mark.bz)));
        }

        if (minX == Integer.MAX_VALUE) {
            // Nothing to draw: bounds stay empty and render() shows the empty state.
            this.bboxMinX = 0;
            this.bboxMinZ = 0;
            this.bboxMaxX = 0;
            this.bboxMaxZ = 0;
            return;
        }

        // A single-block world would divide by ~0; the MIN_SPAN floor gives it a sane scale.
        if (maxX - minX < MIN_SPAN) {
            final int mid = (minX + maxX) / 2;
            minX = mid - MIN_SPAN / 2;
            maxX = minX + MIN_SPAN;
        }
        if (maxZ - minZ < MIN_SPAN) {
            final int mid = (minZ + maxZ) / 2;
            minZ = mid - MIN_SPAN / 2;
            maxZ = minZ + MIN_SPAN;
        }
        this.bboxMinX = minX;
        this.bboxMinZ = minZ;
        this.bboxMaxX = maxX;
        this.bboxMaxZ = maxZ;

        // Which drawn rectangle each siding sits in (smallest wins, so a nested depot keeps its
        // own sidings). Purely from the pixels-ready rectangles, so the highlight can never
        // disagree with the picture.
        for (final Mark mark : this.marks) {
            mark.depot = -1;
            long bestArea = Long.MAX_VALUE;
            for (int i = 0; i < this.depotBoxes.size(); i++) {
                final DepotBox box = this.depotBoxes.get(i);
                if (mark.midX < box.minX || mark.midX > box.maxX
                        || mark.midZ < box.minZ || mark.midZ > box.maxZ) {
                    continue;
                }
                final long area = (long) (box.maxX - box.minX + 1) * (box.maxZ - box.minZ + 1);
                if (area < bestArea) {
                    bestArea = area;
                    mark.depot = i;
                }
            }
            if (mark.depot >= 0) {
                this.depotBoxes.get(mark.depot).count++;
            }
        }
    }

    /** Re-reads only the on/off state, which the detail page can change while this screen exists. */
    private void refreshStates() {
        if (this.marks.isEmpty()) {
            return;
        }
        try {
            final HeadlightConfig cfg = MtrHeadlightClient.config();
            for (final Mark mark : this.marks) {
                final HeadlightConfig.Effective eff = cfg.effectiveFor(mark.siding.id);
                mark.state = !eff.enabled
                        ? tr("sidings.state.off")
                        : tr(eff.overridden ? "sidings.state.custom" : "sidings.state.on");
                mark.stateColor = !eff.enabled ? 0xFFFF8080 : (eff.overridden ? 0xFFFFD37F : 0xFF9DFF9D);
            }
        } catch (RuntimeException e) {
            if (!this.stateErrorLogged) {
                this.stateErrorLogged = true;
                MtrHeadlightClient.LOGGER.warn("[mtr-headlight] could not read siding light state", e);
            }
        }
    }

    @Override
    public void tick() {
        // The detail page edits the live config; Screen.init() is not called again when it returns
        // to this instance, so the dot colours are refreshed here instead of per frame.
        refreshStates();
    }

    // ---------------------------------------------------------------------------------------------
    // Projection
    // ---------------------------------------------------------------------------------------------

    private double baseScale() {
        final double spanX = Math.max(1, this.bboxMaxX - this.bboxMinX);
        final double spanZ = Math.max(1, this.bboxMaxZ - this.bboxMinZ);
        final double availW = Math.max(1, this.mapW - PAD * 2);
        final double availH = Math.max(1, this.mapH - PAD * 2);
        return Math.min(availW / spanX, availH / spanZ);
    }

    private double centerX() {
        return (this.bboxMinX + this.bboxMaxX) / 2.0D;
    }

    private double centerZ() {
        return (this.bboxMinZ + this.bboxMaxZ) / 2.0D;
    }

    /** Caches the projection for this frame / hit test. */
    private void refreshView() {
        this.viewScale = baseScale() * this.zoom;
        this.viewCx = centerX();
        this.viewCz = centerZ();
        this.viewMapCx = this.mapX + this.mapW / 2;
        this.viewMapCy = this.mapY + this.mapH / 2;
    }

    private int sx(final int worldX) {
        return (int) Math.round(this.viewMapCx + this.panX + (worldX - this.viewCx) * this.viewScale);
    }

    private int sy(final int worldZ) {
        return (int) Math.round(this.viewMapCy + this.panY + (worldZ - this.viewCz) * this.viewScale);
    }

    private double worldX(final double mouseX) {
        return this.viewCx + (mouseX - this.viewMapCx - this.panX) / this.viewScale;
    }

    private double worldZ(final double mouseY) {
        return this.viewCz + (mouseY - this.viewMapCy - this.panY) / this.viewScale;
    }

    private boolean inCanvas(final double x, final double y) {
        return x >= this.mapX && x < this.mapX + this.mapW
                && y >= this.mapY && y < this.mapY + this.mapH;
    }

    private void resetView() {
        this.zoom = 1.0D;
        this.panX = 0;
        this.panY = 0;
    }

    /** Zooms around a screen point, so the world under the cursor stays under the cursor. */
    private void zoomAt(final double mouseX, final double mouseY, final double factor) {
        final double next = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, this.zoom * factor));
        if (next == this.zoom) {
            return;
        }
        refreshView();
        final double wx = worldX(mouseX);
        final double wz = worldZ(mouseY);
        this.zoom = next;
        refreshView();
        this.panX = mouseX - this.viewMapCx - (wx - this.viewCx) * this.viewScale;
        this.panY = mouseY - this.viewMapCy - (wz - this.viewCz) * this.viewScale;
        clampPan();
        refreshView();
    }

    /** Keeps at least a strip of the plan on screen, so it can never be panned away entirely. */
    private void clampPan() {
        final double s = baseScale() * this.zoom;
        final double planW = (this.bboxMaxX - this.bboxMinX) * s;
        final double planH = (this.bboxMaxZ - this.bboxMinZ) * s;
        final double limX = Math.max(0, (planW + this.mapW) / 2.0D - 24);
        final double limY = Math.max(0, (planH + this.mapH) / 2.0D - 24);
        this.panX = Math.max(-limX, Math.min(limX, this.panX));
        this.panY = Math.max(-limY, Math.min(limY, this.panY));
    }

    // ---------------------------------------------------------------------------------------------
    // Hit testing
    // ---------------------------------------------------------------------------------------------

    /** Nearest siding dot within {@link #HIT_PX} pixels of the cursor, or -1. */
    private int sidingAt(final double mouseX, final double mouseY) {
        refreshView();
        int best = -1;
        double bestDist = HIT_PX;
        for (int i = 0; i < this.marks.size(); i++) {
            final Mark mark = this.marks.get(i);
            final double dx = sx(mark.midX) - mouseX;
            final double dy = sy(mark.midZ) - mouseY;
            final double dist = Math.sqrt(dx * dx + dy * dy);
            if (dist <= bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    /** Smallest depot rectangle containing a world point, or -1. */
    private int depotAt(final double wx, final double wz) {
        int best = -1;
        long bestArea = Long.MAX_VALUE;
        for (int i = 0; i < this.depotBoxes.size(); i++) {
            final DepotBox box = this.depotBoxes.get(i);
            if (wx < box.minX || wx > box.maxX + 1 || wz < box.minZ || wz > box.maxZ + 1) {
                continue;
            }
            final long area = (long) (box.maxX - box.minX + 1) * (box.maxZ - box.minZ + 1);
            if (area < bestArea) {
                bestArea = area;
                best = i;
            }
        }
        return best;
    }

    private void clickAt(final double mouseX, final double mouseY) {
        final int siding = sidingAt(mouseX, mouseY);
        if (siding >= 0) {
            open(this.marks.get(siding).siding);
            return;
        }
        final int depot = depotAt(worldX(mouseX), worldZ(mouseY));
        this.selectedDepot = depot == this.selectedDepot ? -1 : depot;
    }

    private void open(final Siding siding) {
        if (this.client != null && siding != null) {
            this.client.setScreen(new SidingDetailScreen(this, siding));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && inCanvas(mouseX, mouseY)) {
            this.pressed = true;
            this.panning = true;
            this.dragged = false;
            this.pressX = mouseX;
            this.pressY = mouseY;
            this.lastX = mouseX;
            this.lastY = mouseY;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(final double mouseX, final double mouseY, final int button,
                                final double deltaX, final double deltaY) {
        if (this.panning) {
            if (!this.dragged
                    && Math.abs(mouseX - this.pressX) + Math.abs(mouseY - this.pressY) > DRAG_SLOP) {
                this.dragged = true;
            }
            this.panX += mouseX - this.lastX;
            this.panY += mouseY - this.lastY;
            this.lastX = mouseX;
            this.lastY = mouseY;
            clampPan();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(final double mouseX, final double mouseY, final int button) {
        if (this.panning) {
            final boolean click = !this.dragged && this.pressed;
            this.panning = false;
            this.pressed = false;
            if (click) {
                clickAt(mouseX, mouseY);
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double amount) {
        // 1.20.1 has no horizontal scroll channel: one delta, up = zoom in.
        if (inCanvas(mouseX, mouseY) && amount != 0) {
            zoomAt(mouseX, mouseY, amount > 0 ? ZOOM_STEP : 1.0D / ZOOM_STEP);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_R) {
            resetView();
            return true;
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

    // ---------------------------------------------------------------------------------------------
    // Render
    // ---------------------------------------------------------------------------------------------

    @Override
    public void render(final DrawContext context, final int mouseX, final int mouseY, final float delta) {
        context.drawTexture(OPTIONS_BACKGROUND, 0, 0, 0, 0, this.width, this.height, 32, 32);
        context.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, TITLE_Y, 0xFFFFFF);
        // Top-left toolbar: the current zoom next to the reset button that undoes it.
        context.drawTextWithShadow(this.textRenderer, tr("map.zoom", Math.round(this.zoom * 100.0D)),
                MAP_INSET + RESET_W + 8, BAR_Y + 5, 0xFFFFFF);

        if (this.marks.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, tr("sidings.empty"),
                    this.width / 2, this.mapY + 34, 0xFFD37F);
            context.drawCenteredTextWithShadow(this.textRenderer, tr("sidings.emptyHint"),
                    this.width / 2, this.mapY + 48, 0x909090);
            return;
        }

        if (this.mapW < 40 || this.mapH < MIN_CANVAS) {
            context.drawCenteredTextWithShadow(this.textRenderer, tr("map.tooShort"),
                    this.width / 2, this.mapY + 16, 0xFFD37F);
            return;
        }

        refreshView();
        this.hoveredSiding = this.panning && this.dragged ? -1 : sidingAt(mouseX, mouseY);
        this.hoveredDepot = this.hoveredSiding >= 0 ? -1 : depotAt(worldX(mouseX), worldZ(mouseY));

        final String hint = this.textRenderer.trimToWidth(
                tr(this.hoveredDepot >= 0 ? "map.hintDepot" : "map.hint").getString(),
                Math.max(60, this.width - 32));
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(hint),
                this.width / 2, this.mapY - 14, 0xB0B0B0);

        drawPlan(context);
        drawCorners(context);
        drawTooltip(context, mouseX, mouseY);
    }

    /** Panel, grid, depot rectangles, siding lines/dots/ids. Everything is scissored to the canvas. */
    private void drawPlan(final DrawContext context) {
        final int right = this.mapX + this.mapW;
        final int bottom = this.mapY + this.mapH;

        context.fill(this.mapX, this.mapY, right, bottom, COL_PANEL);
        context.enableScissor(this.mapX, this.mapY, right, bottom);

        drawGrid(context);

        for (int i = 0; i < this.depotBoxes.size(); i++) {
            drawDepot(context, i);
        }
        for (final Mark mark : this.marks) {
            drawSiding(context, mark);
        }

        context.disableScissor();
        // 1px frame on top of the scissor edge so the plan reads as one panel.
        context.fill(this.mapX, this.mapY, right, this.mapY + 1, COL_PANEL_EDGE);
        context.fill(this.mapX, bottom - 1, right, bottom, COL_PANEL_EDGE);
        context.fill(this.mapX, this.mapY, this.mapX + 1, bottom, COL_PANEL_EDGE);
        context.fill(right - 1, this.mapY, right, bottom, COL_PANEL_EDGE);
    }

    /** Block grid at a spacing that stays at least {@link #GRID_PX} pixels apart at this zoom. */
    private void drawGrid(final DrawContext context) {
        int step = GRID_STEPS[GRID_STEPS.length - 1];
        for (final int candidate : GRID_STEPS) {
            if (candidate * this.viewScale >= GRID_PX) {
                step = candidate;
                break;
            }
        }
        final int left = this.mapX;
        final int right = this.mapX + this.mapW;
        final int top = this.mapY;
        final int bottom = this.mapY + this.mapH;

        // Only the blocks that are actually on screen: the plan can be zoomed 8x into a world
        // thousands of blocks wide, and neither the loop nor the fill should walk the rest.
        final double wxFrom = Math.min(worldX(left), worldX(right));
        final double wxTo = Math.max(worldX(left), worldX(right));
        final double wzFrom = Math.min(worldZ(top), worldZ(bottom));
        final double wzTo = Math.max(worldZ(top), worldZ(bottom));

        long wx = (long) Math.floor(wxFrom / step) * step;
        for (int guard = 0; wx <= wxTo && guard < 1024; guard++, wx += step) {
            context.fill(sx((int) wx), top, sx((int) wx) + 1, bottom, COL_GRID);
        }
        long wz = (long) Math.floor(wzFrom / step) * step;
        for (int guard = 0; wz <= wzTo && guard < 1024; guard++, wz += step) {
            context.fill(left, sy((int) wz), right, sy((int) wz) + 1, COL_GRID);
        }
    }

    private void drawDepot(final DrawContext context, final int index) {
        final DepotBox box = this.depotBoxes.get(index);
        final int x1 = sx(box.minX);
        final int z1 = sy(box.minZ);
        final int x2 = sx(box.maxX + 1);
        final int z2 = sy(box.maxZ + 1);
        final int left = Math.min(x1, x2);
        final int top = Math.min(z1, z2);
        final int r = Math.max(left + 2, Math.max(x1, x2));
        final int b = Math.max(top + 2, Math.max(z1, z2));

        // Cull: off-canvas rectangles that are already anchored elsewhere are not worth a fill.
        if (r < this.mapX || left > this.mapX + this.mapW
                || b < this.mapY || top > this.mapY + this.mapH) {
            return;
        }

        final boolean selected = index == this.selectedDepot;
        final boolean hot = index == this.hoveredDepot;
        final int fill = selected ? COL_DEPOT_SEL_FILL : (hot ? COL_DEPOT_HOT : COL_DEPOT_FILL);
        final int edge = selected ? COL_DEPOT_SEL_EDGE : COL_DEPOT_EDGE;

        context.fill(left, top, r, b, fill);
        context.fill(left, top, r, top + 1, edge);
        context.fill(left, b - 1, r, b, edge);
        context.fill(left, top, left + 1, b, edge);
        context.fill(r - 1, top, r, b, edge);

        if (r - left >= 24 && b - top >= 14) {
            final String name = this.textRenderer.trimToWidth(box.name, r - left - 6);
            context.drawTextWithShadow(this.textRenderer, Text.literal(name), left + 3, top + 3,
                    selected ? 0xFFFFD37F : 0xCCFFFFFF);
        }
    }

    private void drawSiding(final DrawContext context, final Mark mark) {
        final int x = sx(mark.midX);
        final int y = sy(mark.midZ);
        final int ax = sx(mark.ax);
        final int az = sy(mark.az);
        final int bx = sx(mark.bx);
        final int bz = sy(mark.bz);

        // Cull on the whole segment, not just the dot: a long siding can cross the canvas while
        // its midpoint is already off screen.
        final int loX = Math.min(x, Math.min(ax, bx));
        final int hiX = Math.max(x, Math.max(ax, bx));
        final int loY = Math.min(y, Math.min(az, bz));
        final int hiY = Math.max(y, Math.max(az, bz));
        if (hiX < this.mapX - 4 || loX > this.mapX + this.mapW + 4
                || hiY < this.mapY - 12 || loY > this.mapY + this.mapH + 4) {
            return; // outside the canvas: no fill, no text
        }

        final boolean dim = this.selectedDepot >= 0 && mark.depot != this.selectedDepot;
        final boolean hot = this.hoveredSiding >= 0 && this.marks.get(this.hoveredSiding) == mark;

        if (mark.hasLine) {
            drawSegment(context, ax, az, bx, bz,
                    dim ? 0x3038C6FF : (hot ? 0xFFFFD37F : COL_TRACK));
        }

        final int colour = dim ? (mark.stateColor & 0x00FFFFFF) | 0x50000000 : mark.stateColor;
        context.fill(x - 1, y - 1, x + 2, y + 2, colour);
        if (hot) {
            // A 7px hollow ring: visible over a dense cluster without hiding the neighbours.
            context.fill(x - 3, y - 3, x + 4, y - 2, COL_HOVER);
            context.fill(x - 3, y + 3, x + 4, y + 4, COL_HOVER);
            context.fill(x - 3, y - 2, x - 2, y + 3, COL_HOVER);
            context.fill(x + 3, y - 2, x + 4, y + 3, COL_HOVER);
        }

        final String label = this.textRenderer.trimToWidth("#" + mark.siding.id, 48);
        final int labelY = y - 10 < this.mapY + 2 ? y + 4 : y - 10;
        context.drawTextWithShadow(this.textRenderer, Text.literal(label), x + 4, labelY,
                dim ? 0x60808080 : (hot ? 0xFFFFFFFF : 0xC0D8D8D8));
    }

    /**
     * A line from (x1,y1) to (x2,y2) drawn with {@code fill} only. Sidings are axis-aligned, so the
     * usual case is a single 2px bar; a diagonal pair is walked in 2px steps instead of being lost.
     */
    private void drawSegment(final DrawContext context, final int x1, final int y1,
                             final int x2, final int y2, final int colour) {
        if (y1 == y2) {
            context.fill(Math.min(x1, x2), y1 - 1, Math.max(x1, x2) + 1, y1 + 1, colour);
            return;
        }
        if (x1 == x2) {
            context.fill(x1 - 1, Math.min(y1, y2), x1 + 1, Math.max(y1, y2) + 1, colour);
            return;
        }
        final int steps = Math.max(1, Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1)) / 2);
        for (int i = 0; i <= steps; i++) {
            final float t = (float) i / steps;
            final int x = Math.round(x1 + (x2 - x1) * t);
            final int y = Math.round(y1 + (y2 - y1) * t);
            context.fill(x - 1, y - 1, x + 1, y + 1, colour);
        }
    }

    /** North marker, legend, and the depot-filter notice - painted over the finished plan. */
    private void drawCorners(final DrawContext context) {
        final int right = this.mapX + this.mapW;
        final int bottom = this.mapY + this.mapH;

        // North is -Z, which is up on this plan, so the marker is always the same glyph.
        context.drawTextWithShadow(this.textRenderer, tr("map.north"), right - 24, this.mapY + 5, 0xD0FFFFFF);
        context.fill(right - 15, this.mapY + 9, right - 14, this.mapY + 20, 0x80FFFFFF);
        context.fill(right - 17, this.mapY + 9, right - 12, this.mapY + 10, 0x80FFFFFF);

        context.drawTextWithShadow(this.textRenderer,
                tr("map.legend"), this.mapX + 4, bottom - 12, 0x90C0C0C0);

        if (this.selectedDepot >= 0 && this.selectedDepot < this.depotBoxes.size()) {
            final String name = this.depotBoxes.get(this.selectedDepot).name;
            final String line = this.textRenderer.trimToWidth(
                    tr("map.selectedDepot", name).getString(), this.mapW - 12);
            context.drawTextWithShadow(this.textRenderer, Text.literal(line),
                    this.mapX + 4, bottom - 24, 0xFFFFD37F);
        }
    }

    /** Hover tooltip: id / name, type - length - cars, and the light state. */
    private void drawTooltip(final DrawContext context, final int mouseX, final int mouseY) {
        final List<Text> lines = new ArrayList<>();
        final List<Integer> colours = new ArrayList<>();

        if (this.hoveredSiding >= 0) {
            final Mark mark = this.marks.get(this.hoveredSiding);
            lines.add(Text.literal("#" + mark.siding.id + "  " + ParameterScreen.nameOf(mark.siding)));
            colours.add(0xFFFFFFFF);
            final String stats = ParameterScreen.stats(mark.siding);
            if (!stats.isEmpty()) {
                lines.add(Text.literal(stats));
                colours.add(0xFFB0B0B0);
            }
            if (mark.state != null) {
                lines.add(mark.state);
                colours.add(mark.stateColor);
            }
        } else if (this.hoveredDepot >= 0) {
            final DepotBox box = this.depotBoxes.get(this.hoveredDepot);
            lines.add(tr("map.depotCount", box.name, box.count));
            colours.add(0xFFFFFFFF);
            lines.add(tr("map.hintDepot"));
            colours.add(0xFFB0B0B0);
        }

        if (lines.isEmpty()) {
            return;
        }

        int w = 0;
        for (final Text line : lines) {
            w = Math.max(w, this.textRenderer.getWidth(line.getString()));
        }
        final int boxW = w + 12;
        final int boxH = lines.size() * 11 + 6;
        int x = mouseX + 12;
        int y = mouseY + 12;
        if (x + boxW > this.width - 4) {
            x = this.width - 4 - boxW;
        }
        if (y + boxH > this.height - 4) {
            y = this.height - 4 - boxH;
        }
        x = Math.max(4, x);
        y = Math.max(4, y);

        context.fill(x - 1, y - 1, x + boxW + 1, y + boxH + 1, 0xC0000000);
        context.fill(x, y, x + boxW, y + boxH, 0xF0101418);
        context.fill(x, y, x + boxW, y + 1, 0xFF3A4450);

        int lineY = y + 3;
        for (int i = 0; i < lines.size(); i++) {
            context.drawTextWithShadow(this.textRenderer, lines.get(i), x + 6, lineY, colours.get(i));
            lineY += 11;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Records
    // ---------------------------------------------------------------------------------------------

    /** One siding: midpoint, both saved ends, the depot it sits in, and its light state. */
    private static final class Mark {
        final Siding siding;
        final int midX;
        final int midZ;
        final int ax;
        final int az;
        final int bx;
        final int bz;
        final boolean hasLine;
        int depot = -1;
        Text state;
        int stateColor = 0xFF9DFF9D;

        private Mark(final Siding siding, final int midX, final int midZ,
                     final int ax, final int az, final int bx, final int bz, final boolean hasLine) {
            this.siding = siding;
            this.midX = midX;
            this.midZ = midZ;
            this.ax = ax;
            this.az = az;
            this.bx = bx;
            this.bz = bz;
            this.hasLine = hasLine;
        }

        /**
         * Reads the coordinates through MTR's public API only: {@code getMidPos()} and
         * {@code getOrderedPositions(mid, false)}. MTR arrives compiled against intermediary
         * names, so the {@link BlockPos} casts go through {@code Object} (dev classpath) and are
         * the same class at runtime.
         */
        static Mark of(final Siding siding) {
            try {
                final var midPos = siding.getMidPos();
                final BlockPos mid = (BlockPos) (Object) midPos;
                int ax = mid.getX();
                int az = mid.getZ();
                int bx = ax;
                int bz = az;
                boolean hasLine = false;

                final List<?> ends = siding.getOrderedPositions(midPos, false);
                if (ends != null && ends.size() >= 2 && ends.get(0) != null && ends.get(1) != null) {
                    final BlockPos a = (BlockPos) (Object) ends.get(0);
                    final BlockPos b = (BlockPos) (Object) ends.get(1);
                    // A siding whose positions were never synced reports (0,0)-(0,0); that is not a
                    // track, so keep the midpoint dot instead of drawing a line to the origin.
                    if (!(a.getX() == 0 && a.getZ() == 0 && b.getX() == 0 && b.getZ() == 0)) {
                        ax = a.getX();
                        az = a.getZ();
                        bx = b.getX();
                        bz = b.getZ();
                        hasLine = ax != bx || az != bz;
                    }
                }
                return new Mark(siding, mid.getX(), mid.getZ(), ax, az, bx, bz, hasLine);
            } catch (RuntimeException e) {
                MtrHeadlightClient.LOGGER.warn(
                        "[mtr-headlight] siding {} has no usable coordinates for the map", siding.id, e);
                return null;
            }
        }
    }

    /** A depot's area as the four normalized world coordinates of its rectangle. */
    private static final class DepotBox {
        final Depot depot;
        final String name;
        final int minX;
        final int minZ;
        final int maxX;
        final int maxZ;
        int count;

        private DepotBox(final Depot depot, final String name,
                         final int minX, final int minZ, final int maxX, final int maxZ) {
            this.depot = depot;
            this.name = name;
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
        }

        /** {@code null} when MTR sent no corners at all - there is no rectangle to draw then. */
        static DepotBox of(final Depot depot) {
            try {
                if (!mtr.data.AreaBase.nonNullCorners(depot)) {
                    return null;
                }
                // corner1/corner2 are Pair<Integer,Integer> = (x, z), and either may be the max.
                final int x1 = depot.corner1.method_15442();
                final int z1 = depot.corner1.method_15441();
                final int x2 = depot.corner2.method_15442();
                final int z2 = depot.corner2.method_15441();
                final int minX = Math.min(x1, x2);
                final int maxX = Math.max(x1, x2);
                final int minZ = Math.min(z1, z2);
                final int maxZ = Math.max(z1, z2);
                if (minX == 0 && maxX == 0 && minZ == 0 && maxZ == 0) {
                    return null; // belt and braces: a zeroed area is "no area", not the origin
                }
                final String name = depot.name == null || depot.name.isBlank()
                        ? tr("sidings.group.unnamed", depot.id).getString()
                        : depot.name;
                return new DepotBox(depot, name, minX, minZ, maxX, maxZ);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
