package dev.js.mtrheadlight.xiamen.fangsu;

import dev.js.mtrheadlight.LteLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;

/**
 * 客户端专用：把「门动了」这件事变成真正的一声。
 *
 * <p>{@code World#playSound(null, pos, …)} 在客户端渲染线程里不保证真的进混音器，
 * 所以这里直接塞给 {@code SoundManager}，位置 / 音量 / 音高全写死。</p>
 */
public final class FangsuPsdSoundsClient {

    private static boolean logged;

    private FangsuPsdSoundsClient() {
    }

    /** @param action 1 = 开门，2 = 关门；其它值什么都不做 */
    public static void play(final BlockPos pos, final int action) {
        if (action != 1 && action != 2) {
            return;
        }
        if (FangsuPsdSounds.BRT_DOOR == null) {
            return;
        }
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getSoundManager() == null) {
            return;
        }
        client.getSoundManager().play(new PositionedSoundInstance(
                FangsuPsdSounds.BRT_DOOR, SoundCategory.BLOCKS, 1.0F, 1.0F, pos));
        if (!logged) {
            logged = true;
            LteLog.LOGGER.debug("[lte] fangsu psd door sound: played {} at {}", Integer.valueOf(action), pos);
        }
    }
}
