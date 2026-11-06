package dev.js.mtrheadlight;

import mtr.client.ClientData;
import mtr.data.Siding;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pick which sidings (where trains are dispatched from) are excluded from the dynamic lights.
 *
 * <p>A train carries the siding it came from ({@code Train.sidingId}), so this is a direct map from
 * the player's choice to the light filter, with no guessing about depots or route names.
 *
 * <p>MTR does not send the siding-to-depot relation to the client ({@code Siding.depot} is set
 * server-side only), so the list cannot be grouped by depot; each row instead shows type, length and
 * car count, which is what actually tells otherwise identically named sidings apart.
 */
public final class SidingScreen extends Screen {
    private static final Identifier OPTIONS_BACKGROUND =
            new Identifier("textures/gui/options_background.png");
    private static final int PER_PAGE = 8;

    private final Screen parent;
    private List<Siding> sidings = List.of();
    private int page;

    public SidingScreen(final Screen parent) {
        super(Text.literal("车厂 / 侧线过滤"));
        this.parent = parent;
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
        loaded.sort(Comparator.comparing(siding -> sidingLabel(siding, false)));
        this.sidings = loaded;

        final int pages = Math.max(1, (this.sidings.size() + PER_PAGE - 1) / PER_PAGE);
        this.page = Math.max(0, Math.min(this.page, pages - 1));

        int y = 46;
        final int first = this.page * PER_PAGE;
        final int last = Math.min(this.sidings.size(), first + PER_PAGE);
        for (int i = first; i < last; i++) {
            final Siding siding = this.sidings.get(i);
            this.addDrawableChild(ButtonWidget.builder(
                    toggleLabel(cfg, siding),
                    b -> {
                        toggle(cfg, siding);
                        b.setMessage(toggleLabel(cfg, siding));
                        // Keep the parent screen's "已屏蔽 N 条" counter in sync.
                        if (this.client != null) {
                            this.clearAndInit();
                        }
                    }
            ).dimensions(this.width / 2 - 165, y, 330, 20).build());
            y += 24;
        }

        if (this.sidings.isEmpty()) {
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("暂无侧线数据（请先进入有列车运行的存档）"), b -> {
                    }
            ).dimensions(this.width / 2 - 165, 46, 330, 20).build());
        }

        final int bottom = this.height - 38;
        if (pages > 1) {
            this.addDrawableChild(ButtonWidget.builder(Text.literal("◀"), b -> {
                this.page--;
                this.clearAndInit();
            }).dimensions(this.width / 2 - 160, bottom, 30, 20).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal("▶"), b -> {
                this.page++;
                this.clearAndInit();
            }).dimensions(this.width / 2 + 130, bottom, 30, 20).build());
        }
        this.addDrawableChild(ButtonWidget.builder(Text.literal("返回"), b -> this.close())
                .dimensions(this.width / 2 - 50, bottom, 100, 20).build());
    }

    private static String sidingLabel(final Siding siding, final boolean muted) {
        final StringBuilder label = new StringBuilder();
        try {
            final String name = siding.name;
            label.append(name == null || name.isBlank() ? "未命名侧线" : name);
        } catch (RuntimeException ignored) {
            label.append("未命名侧线");
        }
        try {
            // Sidings often share names (or have none), so add the type and length to tell them apart.
            label.append("  ·  ").append(siding.transportMode == null ? "?" : siding.transportMode.name())
                    .append("  ·  ").append(Math.round(siding.railLength)).append(" 格");
            if (siding.getTrainCars() > 0) {
                label.append("  ·  ").append(siding.getTrainCars()).append(" 节");
            }
        } catch (RuntimeException ignored) {
            // Keep the plain name.
        }
        return (muted ? "✘ " : "✔ ") + label;
    }

    private static Text toggleLabel(final HeadlightConfig cfg, final Siding siding) {
        final boolean muted = cfg.mutedSidings.contains(siding.id);
        return Text.literal((muted ? "不发光： " : "发光：   ") + sidingLabel(siding, muted).substring(2));
    }

    private static void toggle(final HeadlightConfig cfg, final Siding siding) {
        if (cfg.mutedSidings.contains(siding.id)) {
            cfg.mutedSidings.remove(Long.valueOf(siding.id));
        } else {
            cfg.mutedSidings.add(siding.id);
        }
        cfg.save();
    }

    @Override
    public void render(final DrawContext context, final int mouseX, final int mouseY, final float delta) {
        context.drawTexture(OPTIONS_BACKGROUND, 0, 0, 0, 0, this.width, this.height, 32, 32);
        context.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 22, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("点击切换：被标为「不发光」的侧线发出的列车不再渲染动态光源"),
                this.width / 2, 38, 0xB0B0B0);
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
