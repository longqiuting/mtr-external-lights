package dev.js.mtrheadlight.collision;

import dev.js.mtrheadlight.HeadlightConfig;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;

/**
 * 列车撞击伤害的来源 —— 两段自定义死亡消息都从这里出去（MC 1.20.1）。
 *
 * <h2>为什么是子类，而不是数据包注册一个 DamageType</h2>
 * 1.20.1 的 {@code DamageSource} 只有「{@code RegistryEntry<DamageType>} + 攻击者」这一组构造器，
 * 而 {@code DamageType} 是<b>动态注册表</b>里的条目：客户端模组要加一个自己的类型，就得在服务端
 * （整合服务器的动态注册表）里注册，还得让客户端认得同一个 id。本模组是纯客户端、对 MTR 只读的，
 * 做不到也不该做这件事。
 *
 * <p>所以沿用原版现成的 {@code minecraft:generic} 这个 {@link DamageType}（护甲、抗性、死亡统计
 * 都按原版 generic 走），只把 {@link #getDeathMessage(LivingEntity)} 覆写掉 —— 那个方法是
 * {@code public} 且非 final，{@code DamageTracker#getDeathMessage()} 每帧死亡都直接调它：
 *
 * <pre>
 * DamageTracker#getDeathMessage()  ->  damageSource.getDeathMessage(entity)
 * </pre>
 *
 * <p>于是死亡屏与聊天栏里的那一行就是我们的 {@code TranslatableText}，语言文件照常翻译
 * （{@code assets/mtrlte/lang/zh_cn.json} / {@code en_us.json}），和原版
 * {@code death.attack.*} 完全同一条路径。选哪一段只看撞击时的车速：
 *
 * <pre>
 * v &lt;  highDamageMaxKmh (50)  ->  death.attack.mtrlte.trainLow   （被罚 5000 HKD 气似了）
 * v &gt;= highDamageMaxKmh (50)  ->  death.attack.mtrlte.trainHigh  （随着列车的呼啸声消失）
 * </pre>
 *
 * <p>多人服务器上伤害是服务器实例算的，服务器没有这个类 —— 那边仍然是原版消息。单人（整合服务器）
 * 里伤害打的就是本进程的服务端玩家实例，走的正是这个子类。
 */
public final class TrainImpactDamageSource extends DamageSource {

    /** 低速（&lt; 50 km/h）致死。参数 {@code %1$s} = 死者名字。 */
    public static final String KEY_SLOW = "death.attack.mtrlte.trainLow";

    /** 高速（&gt;= 50 km/h）致死。参数 {@code %1$s} = 死者名字。 */
    public static final String KEY_FAST = "death.attack.mtrlte.trainHigh";

    /** true = 高速那一段文案。 */
    private final boolean fast;

    public TrainImpactDamageSource(final RegistryEntry<DamageType> type, final boolean fast) {
        super(type);
        this.fast = fast;
    }

    /** 这一击走的是高速（{@code trainHigh}）还是低速（{@code trainLow}）那句文案。 */
    public boolean high() {
        return this.fast;
    }

    /** 这一击对应的语言键 —— 日志里和 {@code DeathTracker} 实际产出的文案对照用。 */
    public String key() {
        return this.fast ? KEY_FAST : KEY_SLOW;
    }

    /** 原版把 {@code death.attack.<type>} 交给语言文件；我们换成自己的两个键。 */
    @Override
    public Text getDeathMessage(final LivingEntity entity) {
        return Text.translatable(this.fast ? KEY_FAST : KEY_SLOW, entity.getDisplayName());
    }
}
