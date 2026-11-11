package dev.js.mtrheadlight;

import dev.js.mtrheadlight.clientgui.Widgets;

import net.minecraft.client.util.math.MatrixStack;
import dev.js.mtrheadlight.clientgui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「导入资源包」页：选一个<b>没有</b>被游戏加载的 MTR 资源包（zip 或文件夹），
 * 读出它的 {@code mtr_custom_resources.json} 交给 3D 编辑器。
 *
 * <p>为什么不是系统文件对话框：Minecraft 客户端里没有跨平台的原生选择器，
 * 拉 AWT 的 {@code JFileChooser} 在部分平台/驱动上会把渲染线程卡住。这里用
 * 「自动扫 + 路径框」两条路：
 * <ul>
 *   <li>自动扫 {@code <game>/resourcepacks/} 与 {@code config/lte_packs/}（各两层），
 *       只列出真的带 {@code mtr_custom_resources.json} 的包，点一下就填进路径框；</li>
 *   <li>路径框接受任意 zip / 文件夹的绝对路径（粘贴即可），所以放在别处的包也能导入。</li>
 * </ul>
 */
final class PackImportScreen extends Screen {

    private static final int ROW_H = 18;
    private static final int ROW_PITCH = 20;
    private static final int PAD = 12;

    /** 上次用过的路径：重开本页时留在框里，省得再粘一遍。 */
    private static String lastPath = "";

    private final Screen parent;
    private final Listener listener;
    private final List<Path> candidates = new ArrayList<>();

    private TextFieldWidget pathBox;
    private String status = "";
    private boolean failed;

    private int listTop;
    private int listRows;

    /** 导入成功后回调（在渲染线程上调用）。 */
    interface Listener {
        void imported(CustomPack pack);
    }

    PackImportScreen(final Screen parent, final Listener listener) {
        super(Text.translatable("beam3d.import.title"));
        this.parent = parent;
        this.listener = listener;
    }

    @Override
    protected void init() {
        this.candidates.clear();
        this.candidates.addAll(CustomPack.candidates());
        LteLog.LOGGER.info("[lte] import: {} candidate pack(s)", Integer.valueOf(this.candidates.size()));

        final int boxW = Math.max(80, this.width - 2 * PAD);
        final TextFieldWidget box = new TextFieldWidget(this.textRenderer,
                PAD, 58, boxW, 18, Text.translatable("beam3d.import.path"));
        box.setMaxLength(512);
        box.setText(lastPath);
        this.pathBox = this.addDrawableChild(box);

        this.listTop = 96;
        this.listRows = Math.max(1, Math.min(this.candidates.size(),
                (this.height - 62 - this.listTop) / ROW_PITCH));

        final int buttonY = this.height - 40;
        final int half = Math.max(60, (Math.min(360, this.width - 2 * PAD) - 8) / 2);
        final int left = this.width / 2 - half - 4;
        this.addDrawableChild(Widgets.button(Text.translatable("beam3d.import.ok"),
                        b -> doImport()).dimensions(left, buttonY, half, 20).build());
        this.addDrawableChild(Widgets.button(Text.translatable("beam3d.import.cancel"),
                        b -> close()).dimensions(left + half + 8, buttonY, half, 20).build());
    }

    private void doImport() {
        final String raw = this.pathBox == null ? "" : this.pathBox.getText();
        final String cleaned = raw == null ? "" : raw.trim().replaceAll("^\"|\"$", "");
        if (cleaned.isEmpty()) {
            this.failed = true;
            this.status = Text.translatable("beam3d.import.pathEmpty").getString();
            return;
        }
        final Path path;
        try {
            path = Path.of(cleaned);
        } catch (InvalidPathException e) {
            this.failed = true;
            this.status = Text.translatable("beam3d.import.failed",
                    Text.literal(e.getMessage())).getString();
            return;
        }
        try {
            final CustomPack pack = CustomPack.open(path);
            lastPath = cleaned;
            LteLog.LOGGER.info("[lte] import: opened {} ({} trains)", pack, Integer.valueOf(
                    pack.trains().size()));
            this.listener.imported(pack);
            this.close();
        } catch (Throwable t) {
            this.failed = true;
            this.status = Text.translatable("beam3d.import.failed",
                    Text.literal(String.valueOf(t.getMessage()))).getString();
            LteLog.LOGGER.warn("[lte] import: cannot open {}", cleaned, t);
        }
    }

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        // 候选行是自绘的：点中就把路径填进框里（再按「导入」）。
        if (button == 0 && mouseX >= PAD && mouseX <= this.width - PAD) {
            for (int i = 0; i < this.listRows; i++) {
                final int y = this.listTop + i * ROW_PITCH;
                if (mouseY >= y && mouseY < y + ROW_H) {
                    final Path path = this.candidates.get(i);
                    final String text = path.toAbsolutePath().normalize().toString();
                    this.pathBox.setText(text);
                    lastPath = text;
                    this.failed = false;
                    this.status = "";
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER && this.pathBox != null && this.pathBox.isFocused()) {
            doImport();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(final MatrixStack matrices, final int mouseX, final int mouseY, final float delta) {
        final DrawContext context = new DrawContext(matrices);
        this.renderBackground(matrices);
        final int centre = this.width / 2;
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("beam3d.import.title"), centre, 16, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("beam3d.import.hint"), centre, 32, 0xA0A0A0);
        context.drawTextWithShadow(this.textRenderer,
                Text.translatable("beam3d.import.path"), PAD, 46, 0xBFBFBF);

        if (this.candidates.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal(this.textRenderer.trimToWidth(
                            Text.translatable("beam3d.import.none").getString(), this.width - 2 * PAD)),
                    PAD, this.listTop, 0x909090);
        } else {
            context.drawTextWithShadow(this.textRenderer,
                    Text.translatable("beam3d.import.found"), PAD, this.listTop - 12, 0x909090);
            for (int i = 0; i < this.listRows; i++) {
                final Path path = this.candidates.get(i);
                final int y = this.listTop + i * ROW_PITCH;
                final boolean hover = mouseY >= y && mouseY < y + ROW_H
                        && mouseX >= PAD && mouseX <= this.width - PAD;
                if (hover) {
                    context.fill(PAD, y, this.width - PAD, y + ROW_H, 0x30FFFFFF);
                }
                final String name = path.getFileName() == null ? path.toString()
                        : path.getFileName().toString();
                final String label = name + "   " + path.toAbsolutePath().getParent();
                context.drawTextWithShadow(this.textRenderer,
                        Text.literal(this.textRenderer.trimToWidth(label, this.width - 2 * PAD - 6)),
                        PAD + 3, y + 5, hover ? 0xFFFFFF : 0xC0C0C0);
            }
            if (this.candidates.size() > this.listRows) {
                context.drawTextWithShadow(this.textRenderer,
                        Text.literal("… +" + (this.candidates.size() - this.listRows)),
                        PAD, this.listTop + this.listRows * ROW_PITCH, 0x808080);
            }
        }

        if (!this.status.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal(this.textRenderer.trimToWidth(this.status, this.width - 2 * PAD)),
                    centre, this.height - 62, this.failed ? 0xFF8080 : 0x9DFF9D);
        }
        super.render(matrices, mouseX, mouseY, delta);
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

    /** 调试/日志用：把候选包压成一行。 */
    static String describe(final List<Path> paths) {
        final StringBuilder out = new StringBuilder();
        for (final Path path : paths) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(path.getFileName() == null ? path.toString()
                    : path.getFileName().toString().toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }
}
