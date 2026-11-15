// 方速（BRT 高 / 低）滑动屏蔽门的开关门音效：mtrlte:brt_door。
package dev.js.mtrheadlight.xiamen.fangsu;

import dev.js.mtrheadlight.LteLog;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/**
 * 方速滑动屏蔽门（{@code fangsu_psd_screendoor_low/high_left|right}）的开关门音效事件
 * {@code mtrlte:brt_door}。
 *
 * <h2>三处必须同时对上，缺一处就是「能开关但没声音」</h2>
 * <ol>
 *   <li><b>音频文件</b>：{@code assets/mtrlte/sounds/brt_door.ogg}
 *       （Vorbis / 单声道 / 44100 Hz —— MC 的 {@code OggAudioStream} 只吃这一种，采样率过高或立体声
 *       会在加载时抛 {@code UnsupportedAudioFileException}，声音变成静音并只留一行 WARN）；</li>
 *   <li><b>映射表</b>：{@code assets/mtrlte/sounds.json} 里 key = {@code brt_door}，
 *       {@code category = block}、{@code pitch = 1.0}；</li>
 *   <li><b>注册</b>：本类把同名 {@link SoundEvent} 注册进 {@code Registries.SOUND_EVENT}。
 *       {@code sounds.json} 只是「id → 音频文件」的映射，运行期 {@code World#playSound} 拿到的
 *       {@code SoundEvent} 必须是一个注册过的条目（与 {@code XiamenTicketGateSounds} 同一套做法）。</li>
 * </ol>
 *
 * <h2>为什么注册放在 main（服务端也跑的那一段）</h2>
 * <p>{@code SoundEvent} 是 registry 条目，客户端连服务端时要靠 registry 同步拿到同一份 id →
 * 只在客户端注册会让专用服务端的 registry 里少一条（客户端和服务端的条目数不一致，进服握手会报错）。
 * 注册入口是 {@link FangsuPsdBlocks#register()} —— 它在 {@code LteServer} 的方块注册段里、两端都会跑。
 *
 * <h2>开与关是同一个音效</h2>
 * <p>素材本身是一整段「门滑动」的声音（约 2 秒），开 / 关各播一次、都按 {@code pitch 1.0}。
 * 需要区分方向就改 {@code FangsuPsdDoorBlock.FangsuPsdDoorEntity} 里播放时传的 pitch，
 * 不用在这里再加第二个 entry。
 */
public final class FangsuPsdSounds {

    /** {@code mtrlte:brt_door} —— 与 {@code sounds.json} 的 key 逐字一致。 */
    public static final Identifier BRT_DOOR_ID =
            new Identifier(FangsuPsdBlocks.NAMESPACE, "brt_door");

    /** 注册好的音效事件（{@link #register()} 之后非 null）。 */
    public static SoundEvent BRT_DOOR;

    private static boolean registered;

    private FangsuPsdSounds() {
    }

    /** 注册音效事件，只需要调一次（重复调用直接返回）。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        // SoundEvent.of(id) = 不指定传播距离，走 sounds.json 里那个 entry 的默认音量 / 衰减。
        BRT_DOOR = Registry.register(Registries.SOUND_EVENT, BRT_DOOR_ID, SoundEvent.of(BRT_DOOR_ID));

        LteLog.LOGGER.info("[lte] fangsu psd door sound: {}", BRT_DOOR_ID);
    }
}
