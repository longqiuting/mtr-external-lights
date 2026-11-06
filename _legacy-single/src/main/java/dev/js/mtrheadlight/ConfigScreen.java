package dev.js.mtrheadlight;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * In-game config screen, opened by {@code /externallights} or from Mod Menu.
 *
 * <p>Uses the vanilla screen background (dirt texture in menus, blurred world in game) - a custom
 * background image was tried and dropped.
 */
public final class ConfigScreen extends Screen {
    private static final Identifier OPTIONS_BACKGROUND =
            new Identifier("textures/gui/options_background.png");
    private final Screen parent;

    public ConfigScreen(final Screen parent) {
        super(Text.literal("External Lights"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        final int cx = this.width / 2;
        int y = Math.max(60, this.height / 4);

        this.addDrawableChild(ButtonWidget.builder(masterLabel(cfg), button -> {
            cfg.enabled = !cfg.enabled;
            cfg.save();
            button.setMessage(masterLabel(cfg));
        }).dimensions(cx - 100, y, 200, 20).build());
        y += 30;

        // Slider + "match the game's render distance" button on the same row.
        this.addDrawableChild(new DistanceSlider(cx - 155, y, 240, 20, cfg));
        this.addDrawableChild(ButtonWidget.builder(Text.literal("同步到游戏渲染距离"), button -> {
            final int chunks = gameViewDistance();
            if (chunks > 0) {
                cfg.maxDistance = Math.max(32, Math.min(512, chunks * 16));
                cfg.save();
                this.clearAndInit();
            }
        }).dimensions(cx + 90, y, 75, 20).build());
        y += 30;

        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("车厂 / 侧线过滤（已屏蔽 " + cfg.mutedSidings.size() + " 条）"),
                button -> {
                    if (this.client != null) {
                        this.client.setScreen(new SidingScreen(this));
                    }
                }
        ).dimensions(cx - 150, y, 300, 20).build());
        y += 30;

        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("立即重载所有列车动态光源"),
                button -> LightController.reload()
        ).dimensions(cx - 150, y, 300, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("完成"), button -> this.close())
                .dimensions(cx - 60, this.height - 38, 120, 20).build());
    }

    /** The client's render distance in chunks, or 0 when it cannot be read. */
    private static int gameViewDistance() {
        try {
            return MinecraftClient.getInstance().options.getViewDistance().getValue();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static Text masterLabel(final HeadlightConfig cfg) {
        return Text.literal(cfg.enabled ? "模组灯光：已启用（点击停用）" : "模组灯光：已停用（点击启用）");
    }

    /** Same background as the game's own screens: dirt texture stretched over the window + dark gradient. */
    private void drawVanillaBackground(final DrawContext context) {
        context.drawTexture(OPTIONS_BACKGROUND, 0, 0, 0, 0, this.width, this.height, 32, 32);
        context.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
    }

    @Override
    public void render(final DrawContext context, final int mouseX, final int mouseY, final float delta) {
        drawVanillaBackground(context);
        super.render(context, mouseX, mouseY, delta);
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 22, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(cfg.enabled ? "动态光源：运行中" : "动态光源：已关闭"),
                this.width / 2, 38, cfg.enabled ? 0x9DFF9D : 0xFF9D9D);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("作者：厦航在拍车的无名机长 (kigurumiLTY)"),
                this.width / 2, this.height - 20, 0xB0B0B0);
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

    /** Vanilla slider styled like MTR's: plain bar, label on top, value saved on drag. */
    private static final class DistanceSlider extends SliderWidget {
        private static final double MIN = 32.0D;
        private static final double MAX = 512.0D;
        private final HeadlightConfig cfg;

        DistanceSlider(final int x, final int y, final int width, final int height, final HeadlightConfig cfg) {
            super(x, y, width, height, Text.empty(), toSlider(cfg.maxDistance));
            this.cfg = cfg;
            this.updateMessage();
        }

        private static double toSlider(final double value) {
            return (Math.max(MIN, Math.min(MAX, value)) - MIN) / (MAX - MIN);
        }

        private int blocks() {
            return (int) Math.round(this.value * (MAX - MIN) + MIN);
        }

        @Override
        protected void updateMessage() {
            // Called from SliderWidget's constructor before cfg is assigned.
            this.setMessage(this.cfg == null
                    ? Text.literal("动态光源渲染距离")
                    : Text.literal("距离：" + this.blocks() + " 格"));
        }

        @Override
        protected void applyValue() {
            if (this.cfg != null) {
                this.cfg.maxDistance = this.blocks();
                this.cfg.save();
            }
        }
    }
}
