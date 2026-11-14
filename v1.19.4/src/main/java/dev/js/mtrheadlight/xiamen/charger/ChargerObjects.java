// 充电宝租借机的注册：两块方块 + 方块物品 + 方块实体类型 + 充电宝物品 + 投掷实体类型 + 创造页签。
package dev.js.mtrheadlight.xiamen.charger;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.xiamen.XiamenObjects;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;
import net.minecraft.block.Material;

/**
 * 共享充电宝机器「充电宝租借机」的注册入口（两端都要跑）。
 *
 * <h2>注册了哪些条目</h2>
 * <table border="1">
 *   <caption>id 一览</caption>
 *   <tr><th>id</th><th>类型</th><th>说明</th></tr>
 *   <tr><td>{@code mtrlte:charger_top}</td><td>方块 + 方块物品</td>
 *       <td>上半部分：右键租借 / 归还。模型 {@code rawmodels/charger_top.json}（BER 画）</td></tr>
 *   <tr><td>{@code mtrlte:charger_bottom}</td><td>方块 + 方块物品</td>
 *       <td>下半部分：纯机座，无交互。模型 {@code rawmodels/charger_bottom.json}</td></tr>
 *   <tr><td>{@code mtrlte:charger}</td><td>方块实体类型</td>
 *       <td>两块<b>共用</b>一个类型（各自是不同位置的实例）</td></tr>
 *   <tr><td>{@code mtrlte:power_bank}</td><td>物品</td>
 *       <td>充电宝：租借凭证（NBT {@code rentedAt}）+ 右键空气投掷</td></tr>
 *   <tr><td>{@code mtrlte:power_bank_projectile}</td><td>实体类型</td>
 *       <td>{@link PowerBankProjectileEntity}：落地粒子爆炸 + 半径 2 格 2 点伤害</td></tr>
 * </table>
 *
 * <h2>为什么两块不用一个 id + HALF</h2>
 * <p>需求是「上下完全独立」：两块各自放置、各自掉落、拆一半不带走另一半。
 * 一个 id 走 {@code half=lower|upper} 就必然要写「另一半没了我也没了」的邻居更新与
 * 「拆一半 = 拆整块」的掉落逻辑（见 {@code XiamenMachineBlock}），那正是这里不要的行为。
 * 两个 id 的代价只是玩家要摆两次；换来的独立性和 {@code ticket_gate_*_side} 一致。
 *
 * <h2>掉落表</h2>
 * <p>{@code data/mtrlte/loot_tables/blocks/charger_top.json} 与 {@code charger_bottom.json}
 * 各自掉自己（原版 {@code Block#onStateReplaced} 走战利品表，没有手写 {@code onBreak}）。
 *
 * <p>由 {@code LteServer} 在厦门售票机 / 余额查询机之后调用；条目进
 * 「厦门风格物件」页签（{@link XiamenObjects#CORE_TAB_ID}）。
 */
public final class ChargerObjects {

    /** 模组命名空间（与 {@link XiamenObjects#NAMESPACE} 一致）。 */
    public static final String NAMESPACE = XiamenObjects.NAMESPACE;

    /** 上半部分（租借 / 归还）。 */
    public static final Identifier TOP_ID = new Identifier(NAMESPACE, "charger_top");

    /** 下半部分（机座）。 */
    public static final Identifier BOTTOM_ID = new Identifier(NAMESPACE, "charger_bottom");

    /** 方块实体类型（两块共用）。 */
    public static final Identifier BE_ID = new Identifier(NAMESPACE, "charger");

    /** 充电宝。 */
    public static final Identifier POWER_BANK_ID = new Identifier(NAMESPACE, "power_bank");

    /** 投掷实体的类型。 */
    public static final Identifier PROJECTILE_ID = new Identifier(NAMESPACE, "power_bank_projectile");

    private static ChargerBlock top;
    private static ChargerBlock bottom;
    private static Item topItem;
    private static Item bottomItem;
    private static PowerBankItem powerBank;
    private static BlockEntityType<ChargerBlockEntity> blockEntityType;
    private static EntityType<PowerBankProjectileEntity> projectileType;
    private static boolean registered;

    private ChargerObjects() {
    }

    public static ChargerBlock top() {
        return top;
    }

    public static ChargerBlock bottom() {
        return bottom;
    }

    public static PowerBankItem powerBankItem() {
        return powerBank;
    }

    public static BlockEntityType<ChargerBlockEntity> blockEntityType() {
        return blockEntityType;
    }

    public static EntityType<PowerBankProjectileEntity> projectileType() {
        return projectileType;
    }

    /** 方块 + 物品 + 方块实体 + 投掷实体 + 创造页签条目。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        top = Registry.register(Registries.BLOCK, TOP_ID,
                new ChargerBlock(settings(true), true, "charger_top", "tooltip.mtrlte.charger_top"));
        topItem = Registry.register(Registries.ITEM, TOP_ID, new BlockItem(top, new Item.Settings()));

        bottom = Registry.register(Registries.BLOCK, BOTTOM_ID,
                new ChargerBlock(settings(false), false, "charger_bottom", "tooltip.mtrlte.charger_bottom"));
        bottomItem = Registry.register(Registries.ITEM, BOTTOM_ID, new BlockItem(bottom, new Item.Settings()));

        // 一个方块实体类型对两块方块（各自位置的实例）。
        blockEntityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, BE_ID,
                FabricBlockEntityTypeBuilder.create(ChargerBlockEntity::new, top, bottom).build());

        powerBank = Registry.register(Registries.ITEM, POWER_BANK_ID,
                new PowerBankItem(new Item.Settings().maxCount(1)));

        // 投掷实体：尺寸比照雪球（0.25 格），跟踪范围 4 格区块。
        projectileType = Registry.register(Registries.ENTITY_TYPE, PROJECTILE_ID,
                FabricEntityTypeBuilder.<PowerBankProjectileEntity>create(SpawnGroup.MISC,
                                PowerBankProjectileEntity::new)
                        .dimensions(EntityDimensions.fixed(0.25F, 0.25F))
                        .trackRangeBlocks(4)
                        .trackedUpdateRate(10)
                        .build());

        try {
            ItemGroupEvents.modifyEntriesEvent(XiamenObjects.CORE_TAB_ID)
                    .register(entries -> {
                        entries.add(topItem);
                        entries.add(bottomItem);
                        entries.add(powerBank);
                    });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] power bank charger: could not add the creative tab entries", t);
        }

        LteLog.LOGGER.info("[lte] power bank charger: {} / {} / {} / {}",
                TOP_ID, BOTTOM_ID, POWER_BANK_ID, PROJECTILE_ID);
    }

    /**
     * 两块各一份设置（不能共用一个实例，见 {@code XiamenMachines#settings()}）。
     *
     * @param glowing 是否自带亮度：只有上半部分为 {@code true}
     *                （{@link ChargerBlock#TOP_LUMINANCE}，下半部分保持 0）
     */
    private static AbstractBlock.Settings settings(final boolean glowing) {
        final AbstractBlock.Settings settings = AbstractBlock.Settings.of(Material.STONE)
                .mapColor(MapColor.YELLOW)
                .requiresTool()
                .strength(2.0F)
                .sounds(BlockSoundGroup.METAL)
                .nonOpaque();
        // 方块光照（不是渲染发光）：整块恒定等级，与 blockstate / BER 无关。
        return glowing ? settings.luminance(state -> ChargerBlock.TOP_LUMINANCE) : settings;
    }
}
