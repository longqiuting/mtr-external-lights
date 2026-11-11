package dev.js.mtrheadlight.clientgui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;

/**
 * 1.19.2 的按钮工厂：补上 1.19.3 才有的 {@code ButtonWidget.Builder}，以及 1.19.4 才有的
 * {@code ClickableWidget#setTooltip(Tooltip)}。
 *
 * <p>1.19.2 里这两样都没有：
 * <ul>
 *   <li>{@code ButtonWidget} 只有<b>构造器</b>（第 7 个参数是 {@code TooltipSupplier}），
 *       没有 1.19.3 才加的 {@code ButtonWidget.builder(label, action).dimensions(x,y,w,h).build()}
 *       这条链；</li>
 *   <li>tooltip 是 {@code ButtonWidget.TooltipSupplier#onTooltip(button, matrices, mouseX, mouseY)}
 *       —— 由按钮自己画，不是「存一个 Tooltip 对象」。所以 {@code setTooltip} 也无从对应：
 *       必须<b>建按钮时</b>就把说明给它。</li>
 * </ul>
 *
 * <p>于是这里提供一条与 1.19.4 形状相同的小 builder，屏幕代码只需要把
 * {@code ButtonWidget.builder(..)} 换成 {@link #button(Text, ButtonWidget.PressAction)}，
 * 其余 {@code .dimensions(..).build()} 不动；带悬停说明的按钮再加一个 {@code .tooltip(Text)}。
 * 工具提示由 {@code Screen#renderTooltip} 画（1.19.2 里它是 <b>public</b>，2 参数版直接可用）。
 */
public final class Widgets {

    private Widgets() {
    }

    /** {@code Widgets.button(label, action)} 的 1.19.2 等价物。 */
    public static Builder button(final Text label, final ButtonWidget.PressAction action) {
        return new Builder(label, action);
    }

    /** 与 1.19.3+ 的 {@code ButtonWidget.Builder} 同名同形。 */
    public static final class Builder {
        private final Text label;
        private final ButtonWidget.PressAction action;
        private int x;
        private int y;
        private int width = 150;
        private int height = 20;
        private Text tooltip;

        private Builder(final Text label, final ButtonWidget.PressAction action) {
            this.label = label;
            this.action = action;
        }

        public Builder position(final int x, final int y) {
            this.x = x;
            this.y = y;
            return this;
        }

        public Builder width(final int width) {
            this.width = width;
            return this;
        }

        public Builder size(final int width, final int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder dimensions(final int x, final int y, final int width, final int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            return this;
        }

        /** 悬停说明（1.19.4 的 {@code tooltip(Tooltip)} 对应物）。 */
        public Builder tooltip(final Text tooltip) {
            this.tooltip = tooltip;
            return this;
        }

        public ButtonWidget build() {
            final Text tip = this.tooltip;
            final ButtonWidget.TooltipSupplier supplier;
            if (tip == null) {
                supplier = ButtonWidget.EMPTY;
            } else {
                supplier = (button, matrices, mouseX, mouseY) ->
                        renderTooltip(matrices, tip, mouseX, mouseY);
            }
            return new ButtonWidget(this.x, this.y, this.width, this.height,
                    this.label, this.action, supplier);
        }
    }

    /**
     * 画一行字的面板提示。1.19.2 的 {@code Screen#renderTooltip(MatrixStack, Text, int, int)}
     * 是 public，所以不必拿到调用方屏幕的引用。
     */
    public static void renderTooltip(final MatrixStack matrices, final Text text,
                                     final int mouseX, final int mouseY) {
        final Screen screen = MinecraftClient.getInstance().currentScreen;
        if (screen != null && text != null) {
            screen.renderTooltip(matrices, text, mouseX, mouseY);
        }
    }
}
