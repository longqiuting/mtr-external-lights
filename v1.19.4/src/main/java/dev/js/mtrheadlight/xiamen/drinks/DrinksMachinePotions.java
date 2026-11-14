// 饮料售卖机的药水池：几组预设，刷子右键循环，统一 3 元。
package dev.js.mtrheadlight.xiamen.drinks;

import java.util.List;

import net.minecraft.potion.Potion;
import net.minecraft.potion.Potions;
import net.minecraft.text.Text;
import net.minecraft.util.math.random.Random;

/**
 * 饮料售卖机的「药水池」。
 *
 * <h2>为什么是「几组预设」而不是逐个勾选</h2>
 * <p>液体安检机那种配置界面（{@code LiquidScannerConfigScreen} + 一个自定义包）是为了让玩家
 * 精确勾选几十种液体；饮料机只有「卖哪几种药水」这一件事，一个界面 + 一个包不值当。
 * 所以照需求里「简单做法」那一档：<b>刷子右键循环几组预设</b>，动作栏打印当前这一组是什么。
 * 要换成完整界面时，只需要换掉 {@link DrinksMachineBlock#onUse} 里刷子那一段，
 * 水池本身（{@link Preset}）不用动。
 *
 * <h2>药水都是原版药水</h2>
 * <p>池子里的元素是 {@link Potions} 里的现成 {@link Potion}（不是「效果 + 时长」手搓的 NBT 药水），
 * 这样三件事自动就是对的：
 * <ul>
 *   <li>物品名 / 提示里那几行效果由原版生成（「喷溅型 / 药水」的措辞、时长、等级都对）；</li>
 *   <li>瓶子里液体的颜色由药水类型给，不用手写 {@code CustomPotionColor}；</li>
 *   <li>喝下去的效果就是原版行为，不需要我们再算一次时长。</li>
 * </ul>
 * <p><b>全部是正面效果</b>：速度 / 生命恢复 / 抗火 / 跳跃提升 / 夜视 / 水下呼吸 / 再生（瞬间治疗）。
 * 没有一瓶带负面效果 —— 这是需求里点名的「随机正面药水」。
 *
 * <h2>价格</h2>
 * <p>所有预设、所有药水<b>统一 {@value DrinksMachineBlock#PRICE} 元</b>（MTR 余额），
 * 水池可调但价格不可调 —— 需求就是这么定的。
 */
public final class DrinksMachinePotions {

    private DrinksMachinePotions() {
    }

    /**
     * 预设药水池（刷子右键循环：{@link #MIXED} → {@link #VITALITY} → {@link #NIGHTLY} →
     * {@link #STRENGTH} → {@link #MIXED}）。
     *
     * <p>第一个值是写进方块实体 NBT 的 id：<b>不要改已有的 id</b>，老存档里存的是它。
     */
    public enum Preset {
        /** 默认池（需求点名的那 7 种）。 */
        MIXED("mixed", "message.mtrlte.drinks_machine.preset.mixed",
                Potions.LONG_SWIFTNESS,
                Potions.LONG_REGENERATION,
                Potions.LONG_FIRE_RESISTANCE,
                Potions.LONG_LEAPING,
                Potions.LONG_NIGHT_VISION,
                Potions.LONG_WATER_BREATHING,
                Potions.HEALING),
        /** 回血向：进站跑图跑累了来一瓶。 */
        VITALITY("vitality", "message.mtrlte.drinks_machine.preset.vitality",
                Potions.STRONG_HEALING,
                Potions.LONG_REGENERATION,
                Potions.LONG_FIRE_RESISTANCE,
                Potions.LONG_LEAPING,
                Potions.HEALING),
        /** 夜行向：夜视 + 水下呼吸，站台下面 / 隧道里也好用。 */
        NIGHTLY("nightly", "message.mtrlte.drinks_machine.preset.nightly",
                Potions.LONG_NIGHT_VISION,
                Potions.LONG_WATER_BREATHING,
                Potions.LONG_SWIFTNESS,
                Potions.LONG_LEAPING,
                Potions.LONG_SLOW_FALLING),
        /** 力量向：跳得高、跑得快、扛得住。 */
        STRENGTH("strength", "message.mtrlte.drinks_machine.preset.strength",
                Potions.STRONG_STRENGTH,
                Potions.LONG_SWIFTNESS,
                Potions.LONG_FIRE_RESISTANCE,
                Potions.LONG_REGENERATION,
                Potions.HEALING);

        private final String id;
        private final String translationKey;
        private final List<Potion> potions;

        Preset(final String id, final String translationKey, final Potion... potions) {
            this.id = id;
            this.translationKey = translationKey;
            this.potions = List.of(potions);
        }

        /** 存进 NBT 的 id。 */
        public String id() {
            return this.id;
        }

        /** 这一组的显示名（动作栏打印用）。 */
        public Text displayName() {
            return Text.translatable(this.translationKey);
        }

        /** 池子里的药水（只读）。 */
        public List<Potion> potions() {
            return this.potions;
        }

        /** 刷子的下一档。 */
        public Preset next() {
            final Preset[] all = values();
            return all[(this.ordinal() + 1) % all.length];
        }

        /** 从池子里随机挑一瓶（空池是不可能的，但也不能因此崩）。 */
        public Potion pick(final Random random) {
            return this.potions.get(random.nextInt(this.potions.size()));
        }

        /** NBT 里的 id → 预设；认不出来就退回默认池（删了预设也不会让老存档崩）。 */
        public static Preset byId(final String id) {
            for (final Preset preset : values()) {
                if (preset.id.equals(id)) {
                    return preset;
                }
            }
            return MIXED;
        }
    }
}
