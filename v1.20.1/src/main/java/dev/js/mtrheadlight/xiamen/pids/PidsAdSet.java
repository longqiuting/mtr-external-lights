// 「全局广告集」的数据：一组图片 id（有序）+ 一个开关。
//
// 这个类两端都用：服务端写进 PersistentState（PidsAdSetState），客户端收 S2C 快照
// （PidsAdSetCache），所以这里不许 import 任何客户端类。
//
// 存的是图片 id 的字符串（Identifier#toString），不是下标也不是分类 id：
// 广告表来自资源包，顺序和条数随时会变；按 id 记才不会在换包以后指到另一张图上。
// 图不在了就跳过（见 resolve()），不会让整块屏变黑。
package dev.js.mtrheadlight.xiamen.pids;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.network.PacketByteBuf;

/**
 * 全局广告集：{@link #enabled()} 开着时，<b>所有</b>厦门 PIDS 与 PSD-LCD 都按这里的顺序轮播，
 * 忽略每块屏自己的广告分类、以及老的「单张全局覆盖」。
 *
 * <h2>为什么是「集」而不是「一张」</h2>
 * <p>老的 {@link PidsXiamenSettings#adOverrideImage()} 只能盖一张图；真实场景要的是
 * 「这一站这一季就播这三张，按顺序转」。所以这里是一个有序列表：
 * <ul>
 *   <li>0 张 = 等于没配（{@link #resolve()} 返回 {@code null}，调用方退回原行为）；</li>
 *   <li>1 张 = 和老的单张覆盖等价；</li>
 *   <li>N 张 = 按列表顺序轮播，轮播节奏复用 {@code PidsXiamenRenderer#slideOf}
 *       （按标语滚完换下一张，按世界 tick 无状态算），起点仍然是每块屏自己的
 *       {@code advertisementIndex} —— 所以同一站在不同屏上不会完全同步，看起来更自然。</li>
 * </ul>
 *
 * <h2>传输</h2>
 * <p>{@link #writeNbt}/{@link #readNbt} 给服务端存档（{@link PidsAdSetState}），
 * {@link #writeBuf}/{@link #readBuf} 给 {@link PidsAdSetNet} 的快照 / 保存包。
 * 两条路都按同样的上限（{@value #MAX_IMAGES}）截断，坏包不可能把列表灌爆。
 */
public final class PidsAdSet {

    /** 一套最多几张（够长了，再长也没人看得完；同时是坏包的上限）。 */
    public static final int MAX_IMAGES = 64;

    private static final String ENABLED_TAG = "enabled";
    private static final String IMAGES_TAG = "images";

    private boolean enabled;
    private final List<String> images = new ArrayList<>();

    /** 空集（关着）。 */
    public PidsAdSet() {
    }

    public PidsAdSet(final boolean enabled, final List<String> images) {
        this.enabled = enabled;
        if (images != null) {
            for (final String image : images) {
                this.add(image);
            }
        }
    }

    // ------------------------------------------------------------------ 读

    public boolean enabled() {
        return this.enabled;
    }

    /** 已加入的图片 id，按加入顺序。 */
    public List<String> images() {
        return Collections.unmodifiableList(this.images);
    }

    public int size() {
        return this.images.size();
    }

    public boolean contains(final String image) {
        return image != null && this.images.contains(image);
    }

    // ------------------------------------------------------------------ 写

    public void setEnabled(final boolean on) {
        this.enabled = on;
    }

    /** 追加一张（已存在 / 空 / 超上限就什么都不做）。 */
    public void add(final String image) {
        if (image == null || image.isBlank() || this.images.size() >= MAX_IMAGES) {
            return;
        }
        if (!this.images.contains(image)) {
            this.images.add(image);
        }
    }

    /** 去掉一张（返回是否真的去掉了）。 */
    public boolean remove(final String image) {
        return image != null && this.images.remove(image);
    }

    public void clear() {
        this.images.clear();
    }

    public PidsAdSet copy() {
        return new PidsAdSet(this.enabled, this.images);
    }

    /** 整份覆盖（服务端收到保存包时用）。 */
    public void copyFrom(final PidsAdSet other) {
        this.enabled = other != null && other.enabled;
        this.images.clear();
        if (other != null) {
            for (final String image : other.images) {
                this.add(image);
            }
        }
    }

    /**
     * 这套广告现在真正能播的那几张：按顺序解析成 {@link PidsXiamenAds.Ad}，
     * <b>资源包里已经不存在的图直接跳过</b>。
     *
     * @return {@code null} = 这个覆盖不生效（关着 / 0 张 / 一张都解析不出来）→ 调用方退回原行为
     */
    public List<PidsXiamenAds.Ad> resolve() {
        if (!this.enabled || this.images.isEmpty()) {
            return null;
        }
        final List<PidsXiamenAds.Ad> out = new ArrayList<>(this.images.size());
        for (final String image : this.images) {
            final PidsXiamenAds.Ad ad = PidsXiamenAds.adWithImage(image);
            if (ad != null && ad.image() != null) {
                out.add(ad);
            }
        }
        return out.isEmpty() ? null : Collections.unmodifiableList(out);
    }

    // ------------------------------------------------------------------ 存档

    public NbtCompound writeNbt(final NbtCompound nbt) {
        nbt.putBoolean(ENABLED_TAG, this.enabled);
        final NbtList list = new NbtList();
        for (final String image : this.images) {
            list.add(NbtString.of(image));
        }
        nbt.put(IMAGES_TAG, list);
        return nbt;
    }

    public static PidsAdSet readNbt(final NbtCompound nbt) {
        final PidsAdSet out = new PidsAdSet();
        if (nbt == null) {
            return out;
        }
        out.enabled = nbt.getBoolean(ENABLED_TAG);
        final NbtList list = nbt.getList(IMAGES_TAG, NbtElement.STRING_TYPE);
        for (int i = 0; i < list.size(); i++) {
            out.add(list.getString(i));
        }
        return out;
    }

    // ------------------------------------------------------------------ 网络

    public void writeBuf(final PacketByteBuf buf) {
        buf.writeBoolean(this.enabled);
        buf.writeVarInt(this.images.size());
        for (final String image : this.images) {
            buf.writeString(image);
        }
    }

    public static PidsAdSet readBuf(final PacketByteBuf buf) {
        final PidsAdSet out = new PidsAdSet();
        out.enabled = buf.readBoolean();
        final int count = Math.max(0, Math.min(buf.readVarInt(), MAX_IMAGES));
        for (int i = 0; i < count; i++) {
            out.add(buf.readString(256));
        }
        return out;
    }
}
