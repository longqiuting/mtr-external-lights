// 滚动标语：按 assets/mtrlte/pids_xiamen_ticker.json（格式见 docs/pids-ticker.md）。
package dev.js.mtrheadlight.xiamen.pids;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.js.mtrheadlight.LteLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 屏幕底部那条滚动标语（地铁是白描边圆角框，BRT 是底部深色卡片）。
 *
 * <h2>接口 = {@code docs/pids-ticker.md}，一个字都没改</h2>
 * <pre>{@code
 * assets/mtrlte/pids_xiamen_ticker.json
 * {
 *   "speed": 40,                       // 像素/秒（512×235 的屏幕坐标系），越大越快
 *   "gap":   48,                       // 两条之间的空隙（像素）
 *   "messages": [
 *     { "text": "ticker.mtrlte.welcome" },                  // 先当语言键，查不到原样显示
 *     { "text": "ticker.mtrlte.mind_gap", "color": "#FFD400" },
 *     { "text": "欢迎乘坐厦门地铁", "color": "#FFFFFF", "speed": 32 }
 *   ]
 * }
 * }</pre>
 * 与广告表同构（{@link PidsXiamenAds}）：放在资源包里、换资源包即可整套换文案，
 * 资源重载（F3+T）后由 {@link #invalidate()} 丢掉缓存下次重读。
 *
 * <h2>滚动怎么做</h2>
 * <p><b>一条一条来</b>（文档写的就是这个语义）：
 * <pre>
 *   当前这条从标语条右边缘出发 → 匀速向左 → 完全出左边缘 → 等 gap 对应的那点时间 → 下一条
 * </pre>
 * 每条的速度可以是自己的 {@code speed}（没有就用全局的），所以「长短不一」的句子也不会挤在一起。
 * 一整个循环的时长 = Σ(每条滚完的时间 + 它自己的 gap 时间)。
 *
 * <p><b>不缩字，按边缘裁</b>：一条标语永远用 {@code 条高 × sizeRatio} 这个字号，
 * 长句就保持原字号滚过去，只有跨过标语条左右边缘的字被逐字裁掉 ——
 * 与天津的 {@code ScrollingText} 用 UV 在两端裁边是同一个观感（那边也是固定字号）。
 * 以前「长句整体等比缩小」会让句子越缩越小，屏上根本读不清。
 *
 * <p>横向裁切由 {@link PidsXiamenText} 在<b>顶点级</b>完成（把字形的 x 夹到条内、u 按比例插值），
 * 于是字是<b>在边缘上被切开</b>的（露出半个字），不是整字跳进跳出；也不需要
 * {@code glScissor}、不需要在 BER 中途 flush 共享 buffer。
 *
 * <h2>留好的接口（文档里“还没定的”那三条）</h2>
 * <ul>
 *   <li>{@link #override(List)} / {@link #clearOverride()} —— <b>临时插播</b>：服务端 / 其它模组可以塞一整套
 *       优先文案（例如末班车提示），渲染时 override 非空就用它，{@code null} 就回到资源包那套；
 *       不新增 JSON 字段，所以以后真要做成配置也不会破坏现有格式。</li>
 *   <li>{@link #messages()} 返回不可变快照，谁都能读来自行判定「要不要插播」。</li>
 * </ul>
 */
public final class PidsXiamenTicker {

    /** 配置文件：{@code assets/mtrlte/pids_xiamen_ticker.json}。 */
    public static final Identifier CONFIG_ID = new Identifier("mtrlte", "pids_xiamen_ticker.json");

    /** 默认滚动速度（像素/秒）。 */
    public static final float DEFAULT_SPEED = 40.0F;
    /** 默认两条标语之间的空隙（像素）。 */
    public static final float DEFAULT_GAP = 48.0F;

    /**
     * 一条标语。
     *
     * @param text  已解析好的显示文字（语言键在该解析的时候就换掉了）
     * @param color ARGB
     * @param speed 这条自己的滚速（像素/秒），{@code <= 0} = 用全局
     */
    public record Message(String text, int color, float speed) {
    }

    /** 资源重载后置空，下次渲染重新读。 */
    private static volatile List<Message> cache;

    private static volatile float speed = DEFAULT_SPEED;
    private static volatile float gap = DEFAULT_GAP;

    /** 临时插播（{@code null} = 用资源包那套）。 */
    private static volatile List<Message> override;

    private PidsXiamenTicker() {
    }

    // ------------------------------------------------------------------ 接口

    /** 资源重载时调（{@link PidsXiamenClient} 的重载监听器）。 */
    public static void invalidate() {
        cache = null;
    }

    /** 当前生效的标语表（override 优先）。 */
    public static List<Message> messages() {
        final List<Message> local = override;
        if (local != null) {
            return local;
        }
        List<Message> cached = cache;
        if (cached == null) {
            cached = load();
            cache = cached;
        }
        return cached;
    }

    /** 全局滚速（像素/秒）。 */
    public static float speed() {
        return speed;
    }

    /** 两条之间的空隙（像素）。 */
    public static float gap() {
        return gap;
    }

    /**
     * 临时插播：塞一整套文案，优先于资源包那套。
     *
     * @param messages {@code null} 或空 = 等于 {@link #clearOverride()}
     */
    public static void override(final List<Message> messages) {
        override = messages == null || messages.isEmpty()
                ? null : Collections.unmodifiableList(new ArrayList<>(messages));
    }

    /** 取消临时插播，回到资源包那套。 */
    public static void clearOverride() {
        override = null;
    }

    // ------------------------------------------------------------------ 画

    /**
     * 画一条标语（在标语条里）。
     *
     * @param matrices   已经到了屏幕平面坐标系（原点 = 屏幕左上角，单位 = 底图像素）
     * @param bar        标语条矩形（底图像素）
     * @param sizeRatio  字高 ÷ 标语条高度
     * @param gameTick   滚动相位的 tick 起点（普通状态 = 世界时间；广告轮播时 = 这张广告已经播了多少 tick）
     */
    public static void draw(final MatrixStack matrices, final TextRenderer textRenderer,
                            final VertexConsumerProvider.Immediate immediate,
                            final PidsXiamenLayout.Rect bar, final float sizeRatio, final long gameTick) {
        draw(matrices, textRenderer, immediate, bar, sizeRatio, gameTick, null);
    }

    /**
     * 同上，但可以临时指定这一帧要滚的那几条（{@code null} = 资源包那套）。
     *
     * <p>广告轮播时渲染器把「当前这张广告的 {@code text}」传进来 —— 天津的标语就是广告自带的
     * {@code text}，这样默认那张厦门风景图的标语就是「欢迎乘坐厦门地铁」，勾了别的分类也跟着换。
     */
    public static void draw(final MatrixStack matrices, final TextRenderer textRenderer,
                            final VertexConsumerProvider.Immediate immediate,
                            final PidsXiamenLayout.Rect bar, final float sizeRatio, final long gameTick,
                            final List<Message> messages) {
        final List<Message> list = messages == null || messages.isEmpty() ? messages() : messages;
        if (list.isEmpty() || bar == null) {
            return;
        }

        final float sizePx = sizePx(bar, sizeRatio);
        final float innerLeft = bar.x0() + PidsXiamenLayout.TICKER_INSET;
        final float innerRight = bar.x1() - PidsXiamenLayout.TICKER_INSET;
        final float innerWidth = innerRight - innerLeft;
        if (innerWidth <= 4.0F) {
            return;
        }
        // 不再等比缩小：长消息保持原字号滚过去，只有跨过标语条边缘的字被逐字裁掉
        // （= 天津 ScrollingText 用 UV 裁边的同一效果，见 docs/pids-ticker.md）。
        final float boxTop = bar.cy() - sizePx * 0.5F;

        // 每条的「滚完一次」用时（秒）与它后面的空档（秒）
        final int count = list.size();
        final Timing timing = timing(textRenderer, list, sizePx, innerWidth);
        if (timing.cycle() <= 0.0D) {
            return;
        }

        // 现在轮到哪一条、走到哪
        double t = (gameTick / 20.0D) % timing.cycle();
        for (int i = 0; i < count; i++) {
            if (t < timing.scroll()[i]) {
                final float progress = (float) (t / timing.scroll()[i]);
                final float x = innerRight - progress * (innerWidth + timing.width()[i]);
                PidsXiamenText.drawRun(matrices, textRenderer, immediate,
                        new PidsXiamenText.Segment[]{
                                new PidsXiamenText.Segment(list.get(i).text(), sizePx, list.get(i).color())
                        },
                        x, boxTop, sizePx, PidsXiamenLayout.HAlign.LEFT, 0.0F,
                        innerLeft, innerRight);
                return;
            }
            t -= timing.scroll()[i];
            if (t < timing.idle()[i]) {
                // 空档：什么都不画
                return;
            }
            t -= timing.idle()[i];
        }
    }

    // ------------------------------------------------------------------ 时长（广告换图时机）

    /**
     * 一套标语的时长表（秒）。
     *
     * @param width  每条的自然宽度（px，{@code draw} 里算 x 用）
     * @param scroll 每条从右边缘滑到「整条出左边缘」的用时
     * @param idle   每条滚完之后、下一条进来之前的空档
     * @param cycle  一整个循环（Σ scroll + 每条自己的 idle）
     * @param span   从开始滚到<b>最后一条整条滚出左边缘</b>的用时（最后一条后面那段空档不算）
     */
    private record Timing(float[] width, float[] scroll, float[] idle, double cycle, double span) {
    }

    private static Timing timing(final TextRenderer textRenderer, final List<Message> list,
                                 final float sizePx, final float innerWidth) {
        final int count = list.size();
        final float[] width = new float[count];
        final float[] scroll = new float[count];
        final float[] idle = new float[count];
        double cycle = 0.0D;
        double span = 0.0D;
        for (int i = 0; i < count; i++) {
            final Message message = list.get(i);
            final float natural = Math.max(1.0F, PidsXiamenText.width(textRenderer, message.text(), sizePx));
            width[i] = natural;
            final float own = message.speed() > 0.0F ? message.speed() : speed;
            final float pxPerSecond = Math.max(1.0F, own);
            // 从右边缘滑到「整条出左边缘」= 条宽 + 字宽
            scroll[i] = (innerWidth + width[i]) / pxPerSecond;
            idle[i] = Math.max(0.0F, gap) / pxPerSecond;
            cycle += scroll[i] + idle[i];
            span += scroll[i] + (i < count - 1 ? idle[i] : 0.0D);
        }
        return new Timing(width, scroll, idle, cycle, span);
    }

    /** 标语字号（px）。{@code draw} 与换图时机共用同一个值，不然两边算出来的时长对不上。 */
    public static float sizePx(final PidsXiamenLayout.Rect bar, final float sizeRatio) {
        return Math.max(4.0F, bar.h() * sizeRatio);
    }

    /**
     * 一套标语「全部滚完」要多少 tick —— 也就是广告<b>换下一张</b>的时刻。
     *
     * <p>天津就是这么换的：{@code RenderPIDSTianjin.renderAdvertisement} 调
     * {@code ScrollingText.scrollText(...)}，它返回 {@code true}（这条标语滚完了）才调
     * {@code nextSlide(...)} 把广告序号 +1。这里把「滚完」的定义算成 tick 数交给渲染器，
     * 于是换图和标语走完是同一时刻，不会出现「标语滚到一半突然换图」。
     *
     * @return tick 数（至少 1）；标语表为空 / 条太窄时返回 0（调用方自己兜底）
     */
    public static int scrollSpanTicks(final TextRenderer textRenderer, final List<Message> messages,
                                      final PidsXiamenLayout.Rect bar, final float sizeRatio) {
        if (textRenderer == null || messages == null || messages.isEmpty() || bar == null) {
            return 0;
        }
        final float innerWidth = (bar.x1() - PidsXiamenLayout.TICKER_INSET)
                - (bar.x0() + PidsXiamenLayout.TICKER_INSET);
        if (innerWidth <= 4.0F) {
            return 0;
        }
        final double span = timing(textRenderer, messages, sizePx(bar, sizeRatio), innerWidth).span();
        return span <= 0.0D ? 0 : Math.max(1, (int) Math.round(span * 20.0D));
    }

    // ------------------------------------------------------------------ 读取

    private static List<Message> load() {
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return Collections.emptyList();
        }
        try {
            final var resource = client.getResourceManager().getResource(CONFIG_ID);
            if (resource.isEmpty()) {
                // 资源包里没有这张表 = 不显示标语，不是错误。
                return Collections.emptyList();
            }
            try (InputStream in = resource.get().getInputStream()) {
                final JsonElement root = JsonParser.parseReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
                return parse(root);
            }
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] xiamen pids ticker: could not read {}", CONFIG_ID, t);
            return Collections.emptyList();
        }
    }

    /** 解析（{@code static} 给单元测试 / 调试用）。 */
    static List<Message> parse(final JsonElement root) {
        final List<Message> out = new ArrayList<>();
        if (root == null || !root.isJsonObject()) {
            return Collections.emptyList();
        }
        final JsonObject top = root.getAsJsonObject();

        try {
            final JsonElement speedElement = top.get("speed");
            if (speedElement != null && speedElement.getAsJsonPrimitive().isNumber()) {
                speed = speedElement.getAsFloat();
            }
            final JsonElement gapElement = top.get("gap");
            if (gapElement != null && gapElement.getAsJsonPrimitive().isNumber()) {
                gap = gapElement.getAsFloat();
            }
        } catch (Throwable ignored) {
            // 写坏了就用默认值。
        }

        final JsonElement messagesElement = top.get("messages");
        if (messagesElement == null || !messagesElement.isJsonArray()) {
            return Collections.emptyList();
        }
        final JsonArray array = messagesElement.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            try {
                final JsonElement entryElement = array.get(i);
                if (entryElement == null || !entryElement.isJsonObject()) {
                    continue;
                }
                final JsonObject entry = entryElement.getAsJsonObject();
                final JsonElement textElement = entry.get("text");
                if (textElement == null || textElement.isJsonNull()) {
                    continue;
                }
                final String text = resolve(textElement.getAsString());
                if (text.isEmpty()) {
                    continue;
                }
                int color = PidsXiamenLayout.COLOR_WHITE;
                final JsonElement colorElement = entry.get("color");
                if (colorElement != null && !colorElement.isJsonNull()) {
                    color = parseColor(colorElement.getAsString());
                }
                float own = 0.0F;
                final JsonElement speedElement = entry.get("speed");
                if (speedElement != null && speedElement.getAsJsonPrimitive().isNumber()) {
                    own = speedElement.getAsFloat();
                }
                out.add(new Message(text, color, own));
            } catch (Throwable t) {
                LteLog.LOGGER.warn("[lte] xiamen pids ticker: bad message #{} in {}", i, CONFIG_ID);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * 文档规定的解析顺序：<b>先当语言键</b>，查不到就把原字符串原样显示。
     *
     * <p>{@code Text.translatable} 查不到时返回键本身，所以「翻译结果 == 原文」就是查不到。
     * 这样中文可以直接写进 JSON（{@code "欢迎乘坐厦门地铁"}），也可以写键名。
     */
    static String resolve(final String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        try {
            final String translated = Text.translatable(raw).getString();
            return translated == null || translated.isEmpty() ? raw : translated;
        } catch (Throwable t) {
            return raw;
        }
    }

    /** {@code #RRGGBB} → ARGB；写坏了返回白色。 */
    static int parseColor(final String raw) {
        try {
            final String hex = raw.startsWith("#") ? raw.substring(1) : raw;
            final int rgb = Integer.parseInt(hex, 16);
            return 0xFF000000 | (rgb & 0xFFFFFF);
        } catch (Throwable t) {
            return PidsXiamenLayout.COLOR_WHITE;
        }
    }
}
