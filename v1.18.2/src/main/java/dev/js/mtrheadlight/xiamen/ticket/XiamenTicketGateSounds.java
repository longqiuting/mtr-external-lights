// 闸机音效：南滨（Nanbin）的 crt_ticket / crt_ticket_error，已内置在 mtrlte 命名空间下。
package dev.js.mtrheadlight.xiamen.ticket;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.xiamen.XiamenObjects;
import net.minecraft.util.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/**
 * 闸机的两个音效事件：{@code mtrlte:crt_ticket} 与 {@code mtrlte:crt_ticket_error}。
 *
 * <h2>声音文件从哪来</h2>
 * <p>音频是南滨（Nanbin Create Mod，MIT，Nanbin-Studio / LIEH-SAD）的 {@code crt_ticket.ogg}，
 * 已经内置为 {@code assets/mtrlte/sounds/crt_ticket.ogg}，条目写在
 * {@code assets/mtrlte/sounds.json} 里（{@code crt_ticket} = 原调，
 * {@code crt_ticket_error} = 同一段音频 pitch 0.7）。<b>但 sounds.json 只是「id → 音频文件」的
 * 映射表，运行期要真正播放还必须有注册好的 {@code SoundEvent}</b>——
 * 缺这一步的症状是 {@code World#playSound} 里查不到这个 id、闸机「能过但没声音」
 * （或者直接 {@code NullPointerException}）。
 *
 * <h2>为什么 id 与 sounds.json 的 key 完全相同</h2>
 * <p>{@code SoundEvent} 靠 id 去 {@code sounds.json} 里找条目，两边的字符串必须逐字一致：
 * {@code mtrlte:crt_ticket} → {@code sounds.json} 的 {@code "crt_ticket"}。
 *
 * <p>注册在 {@code main}（{@link XiamenTicketGates#register()} 里）调一次：音效事件是 registry
 * 条目，服务端也要有（{@code World#playSound} 在服务端跑）。
 */
public final class XiamenTicketGateSounds {

    /** 刷卡成功（进 / 出站都响这一声）。 */
    public static final Identifier CRT_TICKET_ID = new Identifier(XiamenObjects.NAMESPACE, "crt_ticket");

    /** 刷卡失败（余额不足 / 未刷进站等），同一段音频降调。 */
    public static final Identifier CRT_TICKET_ERROR_ID =
            new Identifier(XiamenObjects.NAMESPACE, "crt_ticket_error");

    /** 注册好的音效事件（{@link #register()} 之后非 null）。 */
    public static SoundEvent CRT_TICKET;
    public static SoundEvent CRT_TICKET_ERROR;

    private static boolean registered;

    private XiamenTicketGateSounds() {
    }

    /** 注册两个音效事件，只需要调一次。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        // SoundEvent.of(id) = 不指定传播距离（走 sounds.json 里那个 entry 的默认音量 / 衰减）。
        CRT_TICKET = Registry.register(Registry.SOUND_EVENT, CRT_TICKET_ID, new SoundEvent(CRT_TICKET_ID));
        CRT_TICKET_ERROR = Registry.register(Registry.SOUND_EVENT, CRT_TICKET_ERROR_ID,
                new SoundEvent(CRT_TICKET_ERROR_ID));

        LteLog.LOGGER.info("[lte] ticket gate sounds: {} / {}", CRT_TICKET_ID, CRT_TICKET_ERROR_ID);
    }
}
