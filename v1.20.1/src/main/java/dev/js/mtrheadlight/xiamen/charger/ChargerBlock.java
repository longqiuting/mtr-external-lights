// 充电宝租借机的一块（上 / 下）：两块完全独立，各自放置、各自掉落、各自渲染。
package dev.js.mtrheadlight.xiamen.charger;

import java.util.List;

import dev.js.mtrheadlight.bind.MtrBalance;
import dev.js.mtrheadlight.rawmodel.RawModelJson;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * 充电宝租借机的一块：{@code mtrlte:charger_top}（上，租借 / 归还）与
 * {@code mtrlte:charger_bottom}（下，机座）。
 *
 * <h2>上下两块为什么是「两个方块」</h2>
 * <p>需求要的是<b>完全独立</b>：可各自单独放置、各带各的物品、拆一半不带走另一半。
 * 这和 {@code ticket_gate_*_side} 的做法一致 —— 两个 id、两条掉落表、两件方块物品，
 * 互相之间<b>没有任何</b> {@code canPlaceAt} / {@code getStateForNeighborUpdate} / {@code onBreak}
 * 联动（{@link dev.js.mtrheadlight.xiamen.machine.XiamenMachineBlock} 那套 {@code half=lower|upper}
 * 的「拆一半 = 拆整块」在这里是反需求，所以一个字段都不共用）。
 *
 * <p>代价是「叠在一起才是一台完整的机器」这件事只靠玩家摆；收益是拆装 / 掉落 / 中键选取 /
 * 存档都简单，而且上半部分可以单独挂在任何高度（例如摆在售票机顶上）。
 *
 * <h2>几何</h2>
 * <p>两块各有一份 Blockbench 原始模型（{@code rawmodels/charger_{top,bottom}.json}），
 * 由 {@link ChargerRenderer} 运行时画 —— blockstate 指向 {@code mtrlte:block/empty}，
 * 世界里的模型<b>不</b>走原版 blockstate（上段的元素大量是绕 y 的 ±45° 旋转，
 * 而且素材本来就把上半部分切成了自己那一格的局部坐标）。
 *
 * <p>碰撞 / 轮廓<b>按模型推导</b>：注册时从 {@link RawModelJson#bounds} 读那份模型的实际包围盒，
 * 再按 {@code facing} 的四个 90° 档各转一次 —— 不是手抄的魔数，换模型自动跟着变。
 *
 * <h2>右键（只有上半部分）</h2>
 * <ol>
 *   <li><b>空手</b> → 租出一个充电宝（{@code rentedAt} 写进物品 NBT，服务端权威）。
 *       余额为负时拒绝租借；已经有一个没还的会提示「先归还」；</li>
 *   <li><b>手持充电宝 + Shift + 右键</b> → <b>强制买断</b>：按
 *       {@link PowerBankItem#OVERDUE_FEE}（与 24h 超时买断同一个常量）扣一次钱，
 *       成功后把「已买断」写进物品 NBT（{@code expiredFine}），这块充电宝从此归玩家、
 *       机器永久拒收。余额不足只红字、不扣不收。以前那套「空手右键两下买断」已取消
 *       （误触多，还要一张每人一条的全局状态表）；</li>
 *   <li><b>手持充电宝</b> → 归还：按<a href="#fee">持有时间</a>扣费（MTR 余额，见 {@link MtrBalance}）；</li>
 *   <li><b>手持别的东西</b> → 一句提示，不误触。</li>
 * </ol>
 * <p>「投掷」是另一条独立的线（不算归还）：见 {@code PowerBankItem#use} 与
 * {@link PowerBankProjectileEntity} —— 扔出去的充电宝在命中 / 落地时判定<b>损坏</b>并按买断价扣款，
 * 这块充电宝之后再也不能归还。
 * <p>下半部分 {@code charger_bottom} <b>没有任何交互</b>：它的 {@code onUse} 直接
 * {@link ActionResult#PASS}（原版默认值），等于一个普通方块 —— 空手右键没反应，
 * 手里拿着方块就照常放着对方块。没有提示消息、不读余额、不碰 NBT。
 *
 * <h2>光</h2>
 * <p>只有上半部分自带亮度 {@value #TOP_LUMINANCE}（方块光源等级 7，约等于蜡烛 / 火把的一半），
 * 是给「租借机在用」的一点微光；下半部分与普通方块一样亮度 0。亮度用
 * {@code AbstractBlock.Settings#luminance} 声明，属于<b>方块光照</b>，
 * 不经过 BER、也不改 blockstate（两份 blockstate 依然只有 {@code facing}）。
 *
 * <h2 id="fee">计费</h2>
 * <p><b>前 {@value PowerBankItem#GRACE_MINUTES} 分钟免费</b>；超过之后<b>每
 * {@value PowerBankItem#PERIOD_MINUTES} 分钟 {@value PowerBankItem#FEE_PER_PERIOD} 元</b>，
 * 不足一档向上取整；<b>24 小时封顶</b>（{@value PowerBankItem#MAX_PERIODS} 档 =
 * {@value PowerBankItem#MAX_FEE} 元）；<b>超过 24 小时不可归还</b> —— 机器拒收（物品留给玩家），
 * 扣 {@value PowerBankItem#OVERDUE_FEE} 元买断罚款，且只扣一次。
 * 时间来自物品 NBT 里的 {@code rentedAt}（服务端 {@code System.currentTimeMillis()}），
 * 所以计费与客户端时钟无关；扣费允许把余额扣成负数（欠费是「余额为负不能租借」的前提，
 * 见 {@link MtrBalance}）。
 *
 * <h2>声音</h2>
 * <p>租出与归还成功都播一声「叮」（{@code BLOCK_NOTE_BLOCK_BELL} + {@code ENTITY_EXPERIENCE_ORB_PICKUP}），
 * 音量 / 音高与 {@code DrinksMachineBlock} 的售货提示<b>逐字一致</b>；只在服务端播，
 * 客户端靠原版声音包同步。拒收（超时买断）不播 —— 那不是一次成功的归还。
 */
public class ChargerBlock extends HorizontalFacingBlock implements BlockEntityProvider {

    /** 四向（与 {@code HorizontalFacingBlock.FACING} 同一个属性实例）。 */
    public static final DirectionProperty FACING = HorizontalFacingBlock.FACING;

    /**
     * 上半部分的自发光等级（方块光照 0..15，7 ≈ 火把的一半）。
     * 只由 {@link ChargerObjects} 在注册上半块时通过 {@code Settings#luminance} 用上。
     */
    public static final int TOP_LUMINANCE = 7;

    /** 模型读不到时的兜底包围盒（模型空间 0..16）：x 3..13、整格高、z 3..13。 */
    private static final float[] FALLBACK_BOX = {3.0F, 0.0F, 3.0F, 13.0F, 16.0F, 13.0F};

    /** 是不是上半部分（只有它右键有交互）。 */
    private final boolean top;

    /** 用哪一份原始模型：{@code charger_top} / {@code charger_bottom}（渲染器直接拼 rawmodels 的 id）。 */
    private final String rawModel;

    /** 四个朝向的轮廓 / 碰撞（已按 blockstate 的 {@code y} 转好）。 */
    private final VoxelShape[] shapes = new VoxelShape[4];

    /** 物品提示的翻译键。 */
    private final String tooltipKey;

    /**
     * @param settings 方块设置（硬度 / 声音 / nonOpaque，见 {@link ChargerObjects}）
     * @param top      {@code true} = 上半部分（可租借 / 归还）
     * @param rawModel {@code rawmodels/} 下的模型名
     * @param tooltipKey 物品提示的翻译键
     */
    public ChargerBlock(final AbstractBlock.Settings settings, final boolean top,
                        final String rawModel, final String tooltipKey) {
        super(settings);
        this.top = top;
        this.rawModel = rawModel;
        this.tooltipKey = tooltipKey;
        // 有 FACING 就必须给默认状态，否则 registry 冻结时崩。
        this.setDefaultState(this.stateManager.getDefaultState().with(FACING, Direction.NORTH));

        final float[] bounds = RawModelJson.bounds(RawModelJson.id(rawModel));
        final float[] box = bounds == null ? FALLBACK_BOX : bounds;
        for (int i = 0; i < 4; i++) {
            this.shapes[i] = rotatedBox(box, i);
        }
    }

    /** {@code charger_top} / {@code charger_bottom}（渲染器用）。 */
    public String rawModel() {
        return this.rawModel;
    }

    /** 是不是上半部分。 */
    public boolean isTop() {
        return this.top;
    }

    // ------------------------------------------------------------------ 形状

    private static VoxelShape box(final double x1, final double x2, final double y1, final double y2,
                                  final double z1, final double z2) {
        return Block.createCuboidShape(x1, y1, z1, x2, y2, z2);
    }

    /**
     * 把模型空间的包围盒按 blockstate 的 {@code y}（0 / 90 / 180 / 270，每档一步）转成世界坐标。
     *
     * <p>与 {@code XiamenMachineBlock#rotatedBox} 同一套约定：每 90° 都是
     * {@code (x, z) → (16 - z, x)}，也就是原版 {@code "y": n} 的等价写法。
     */
    private static VoxelShape rotatedBox(final float[] b, final int quarterTurns) {
        final double x1 = b[0];
        final double y1 = b[1];
        final double z1 = b[2];
        final double x2 = b[3];
        final double y2 = b[4];
        final double z2 = b[5];
        return switch (quarterTurns & 3) {
            case 1 -> box(16.0D - z2, 16.0D - z1, y1, y2, x1, x2);
            case 2 -> box(16.0D - x2, 16.0D - x1, y1, y2, 16.0D - z2, 16.0D - z1);
            case 3 -> box(z1, z2, y1, y2, 16.0D - x2, 16.0D - x1);
            default -> box(x1, x2, y1, y2, z1, z2);
        };
    }

    /** facing → 形状数组下标（south = 0，与模型原生朝 +Z 一致）。 */
    private static int quarterTurns(final Direction facing) {
        return switch (facing) {
            case WEST -> 1;
            case NORTH -> 2;
            case EAST -> 3;
            default -> 0; // SOUTH
        };
    }

    @Override
    protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getPlacementState(final ItemPlacementContext context) {
        return this.getDefaultState().with(FACING, context.getHorizontalPlayerFacing().getOpposite());
    }

    @Override
    public VoxelShape getOutlineShape(final BlockState state, final BlockView world, final BlockPos pos,
                                      final ShapeContext context) {
        return this.shapes[quarterTurns(state.get(FACING))];
    }

    @Override
    public VoxelShape getCollisionShape(final BlockState state, final BlockView world, final BlockPos pos,
                                        final ShapeContext context) {
        return this.shapes[quarterTurns(state.get(FACING))];
    }

    /** 空集：不是满方块，别让邻居朝我们的面被剔除（缝隙里会看穿邻居）。 */
    @Override
    public VoxelShape getCullingShape(final BlockState state, final BlockView world, final BlockPos pos) {
        return VoxelShapes.empty();
    }

    /** 几何由 BER 画，所以每块都要一个方块实体当锚点。 */
    @Override
    public BlockEntity createBlockEntity(final BlockPos pos, final BlockState state) {
        return new ChargerBlockEntity(pos, state);
    }

    // ------------------------------------------------------------------ 右键

    /**
     * 右键：<b>只有上半部分</b>有交互。
     *
     * <p>下半部分直接走原版 {@code PASS}（也就是 {@code AbstractBlock#onUse} 的默认实现）：
     * 客户端与服务端都会当作「这个方块没兴趣」，于是行为和一个普通方块完全一样 ——
     * 空手右键什么也不发生，手里拿着方块就照常放着对方块。这里不再有任何提示消息。
     */
    @Override
    public ActionResult onUse(final BlockState state, final World world, final BlockPos pos,
                              final PlayerEntity player, final Hand hand, final BlockHitResult hit) {
        if (!this.top || hand != Hand.MAIN_HAND) {
            return ActionResult.PASS;
        }
        // 余额 / NBT / 扣费全在服务端；客户端只做挥手（返回 SUCCESS 免得顺手放方块）。
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }

        final ItemStack held = player.getStackInHand(hand);
        if (held.getItem() instanceof PowerBankItem) {
            if (player.isSneaking()) {
                // 手持充电宝 + Shift + 右键 = **强制买断**（以前是「空手右键两下」，误触多、还留一张
                // 全局状态表，已经撤掉）。价格复用 24h 超时那一档（{@link PowerBankItem#OVERDUE_FEE}）。
                forceBuyout(world, pos, player, held);
            } else {
                returnPowerBank(world, pos, player, held);
            }
            return ActionResult.SUCCESS;
        }
        if (!held.isEmpty()) {
            player.sendMessage(Text.translatable("message.mtrlte.charger.hand_not_empty"), true);
            return ActionResult.SUCCESS;
        }
        // ---- 空手：租一个（身上还有没还的会由 rentPowerBank 给出「先归还」提示）。
        // 买断改成「手持充电宝 + Shift + 右键」，空手这条路只剩租借，不再需要任何状态表。
        rentPowerBank(world, pos, player);
        return ActionResult.SUCCESS;
    }

    /**
     * 强制买断（手持充电宝 + Shift + 右键，服务端权威）：按 24h 超时那一档的价格
     * （{@link PowerBankItem#OVERDUE_FEE}）扣一次钱，然后把这块充电宝藏进物品自己的 NBT
     * （{@code expiredFine}）—— 从此机器永久拒收（和「超过 24 小时」那条路径的终态完全一样：
     * <b>充电宝归玩家</b>，只是提前付了买断价）。
     *
     * <ul>
     *   <li><b>余额不足</b>：红字提示，<b>不扣、不盖章、物品不动</b>（用户要求：余额不足只红字不扣）；</li>
     *   <li><b>写不进账本</b>（MTR 记分板缺失等）：同样红字、不盖章，下次再试 —— 与
     *       {@link #rejectOverdue} 的判定逐字一致（{@code after != before - fee} 说明没写成功）；</li>
     *   <li><b>已经买断过 / 已经损坏</b>：一声提示，不重复扣钱。</li>
     * </ul>
     *
     * <p>创造模式拿的、没有 {@code rentedAt} 的充电宝也照收：本来就没有租期，直接当「已经买断」，
     * 不收钱（没有租借记录就没有可买断的东西）。
     */
    private static void forceBuyout(final World world, final BlockPos pos, final PlayerEntity player,
                                    final ItemStack held) {
        if (PowerBankItem.isDamaged(held)) {
            player.sendMessage(Text.translatable("message.mtrlte.charger.damaged_not_returnable")
                    .formatted(Formatting.RED), true);
            return;
        }
        if (!PowerBankItem.isRented(held)) {
            player.sendMessage(Text.translatable("message.mtrlte.charger.returned_free",
                    MtrBalance.get(world, player)), true);
            return;
        }
        if (PowerBankItem.isOverdueSettled(held)) {
            player.sendMessage(Text.translatable("message.mtrlte.charger.overdue_again",
                    PowerBankItem.heldHours(held, System.currentTimeMillis()),
                    PowerBankItem.OVERDUE_FEE).formatted(Formatting.RED), false);
            return;
        }
        final int fee = PowerBankItem.OVERDUE_FEE;
        final int before = MtrBalance.get(world, player);
        if (before < fee) {
            player.sendMessage(Text.translatable("message.mtrlte.charger.buyout_no_money",
                    fee, before).formatted(Formatting.RED), false);
            return;
        }
        final int after = MtrBalance.add(world, player, -fee);
        if (after != before - fee) {
            player.sendMessage(Text.translatable("message.mtrlte.charger.buyout_failed",
                    fee).formatted(Formatting.RED), false);
            return;
        }
        PowerBankItem.markOverdueSettled(held);
        ding(world, pos);
        player.sendMessage(Text.translatable("message.mtrlte.charger.bought_out", fee, after), false);
    }

    /** 空手右键上半部分：租出一个。 */
    private static void rentPowerBank(final World world, final BlockPos pos, final PlayerEntity player) {
        if (findRented(player) != null) {
            player.sendMessage(Text.translatable("message.mtrlte.charger.already_rented"), true);
            return;
        }
        final int balance = MtrBalance.get(world, player);
        if (balance < 0) {
            player.sendMessage(Text.translatable("message.mtrlte.charger.negative_balance", balance), true);
            return;
        }

        final ItemStack powerBank = PowerBankItem.rented(ChargerObjects.powerBankItem(),
                System.currentTimeMillis());
        if (!player.getInventory().insertStack(powerBank)) {
            player.dropItem(powerBank, false);
        }
        ding(world, pos);
        player.sendMessage(Text.translatable("message.mtrlte.charger.rented"), true);
        // 计费规则只在这里说一次，而且走聊天栏（false）；不进物品 tooltip。
        player.sendMessage(Text.translatable("message.mtrlte.charger.rule",
                PowerBankItem.GRACE_MINUTES, PowerBankItem.PERIOD_MINUTES, PowerBankItem.FEE_PER_PERIOD,
                PowerBankItem.MAX_PERIODS, PowerBankItem.MAX_FEE,
                PowerBankItem.OVERDUE_HOURS, PowerBankItem.OVERDUE_FEE), false);
    }

    /**
     * 手持充电宝右键上半部分：结算后收走。
     *
     * <p><b>超过 {@value PowerBankItem#OVERDUE_HOURS} 小时</b>：机器拒收（不当作归还），
     * 红字提示 + 扣 {@value PowerBankItem#OVERDUE_FEE} 元「买断」罚款，
     * 并往物品 NBT 写 {@code expiredFine=true} —— <b>物品留在玩家手里</b>，
     * 这 {@value PowerBankItem#OVERDUE_FEE} 元只扣一次：之后再右键只重复红字提示，
     * 不再扣钱、也不再结算。惩罚允许把余额扣成负数（见 {@link MtrBalance}）。
     */
    private static void returnPowerBank(final World world, final BlockPos pos, final PlayerEntity player,
                                        final ItemStack held) {
        if (PowerBankItem.isDamaged(held)) {
            // 投掷过的充电宝已经判定「损坏」并按买断价扣过款了 —— 机器永久拒收，也不能再计费。
            player.sendMessage(Text.translatable("message.mtrlte.charger.damaged_not_returnable")
                    .formatted(Formatting.RED), true);
            return;
        }
        if (!PowerBankItem.isRented(held)) {
            // 没有租借记录（创造模式拿的 / 刷出来的）：照收，不计费。
            held.decrement(1);
            ding(world, pos);
            player.sendMessage(Text.translatable("message.mtrlte.charger.returned_free",
                    MtrBalance.get(world, player)), true);
            return;
        }

        final long now = System.currentTimeMillis();

        if (PowerBankItem.isOverdue(held, now)) {
            rejectOverdue(world, player, held, now);
            return;
        }

        final long minutes = PowerBankItem.heldMinutes(held, now);
        final long periods = PowerBankItem.billedPeriods(held, now);
        final int fee = PowerBankItem.fee(held, now);
        final int balance = MtrBalance.add(world, player, -fee);

        held.decrement(1);
        ding(world, pos);
        player.sendMessage(Text.translatable("message.mtrlte.charger.returned",
                minutes, periods, fee, balance), true);
    }

    /**
     * 超时（{@code > 24h}）拒收：红字提示，充电宝<b>不</b>被拿走。
     *
     * <p>{@value PowerBankItem#OVERDUE_FEE} 元只扣一次，账本记在物品自己的 NBT
     * （{@code expiredFine=true}）上，所以丢掉再捡回来、跨存档、换机器都不会重复扣。
     */
    private static void rejectOverdue(final World world, final PlayerEntity player,
                                      final ItemStack held, final long now) {
        final long hours = PowerBankItem.heldHours(held, now);
        if (PowerBankItem.isOverdueSettled(held)) {
            // 罚款已结算过：只重复提示，一分不扣、物品不动。
            player.sendMessage(Text.translatable("message.mtrlte.charger.overdue_again",
                    hours, PowerBankItem.OVERDUE_FEE).formatted(Formatting.RED), false);
            return;
        }
        // 第一次超时：扣买断价 → 扣成功才盖 NBT 章（盖了就不会再扣），物品留着。
        final int before = MtrBalance.get(world, player);
        final int after = MtrBalance.add(world, player, -PowerBankItem.OVERDUE_FEE);
        if (after != before - PowerBankItem.OVERDUE_FEE) {
            // 写不进去（MTR 记分板缺失等）：不盖章，下次右键再试，避免「没收钱却记成已结算」。
            player.sendMessage(Text.translatable("message.mtrlte.charger.overdue_failed",
                    hours, PowerBankItem.OVERDUE_FEE).formatted(Formatting.RED), false);
            return;
        }
        PowerBankItem.markOverdueSettled(held);
        player.sendMessage(Text.translatable("message.mtrlte.charger.overdue",
                hours, PowerBankItem.OVERDUE_FEE, after).formatted(Formatting.RED), false);
    }

    /**
     * 「叮」一声（售货机 / 归还机通用）：{@code BLOCK_NOTE_BLOCK_BELL} + {@code ENTITY_EXPERIENCE_ORB_PICKUP}。
     *
     * <p>音量 / 音高与 {@code DrinksMachineBlock} 的售货提示逐字一致（0.9/1.4 与 0.7/1.2），
     * 声音类别 {@link SoundCategory#BLOCKS}，只在服务端调用 —— 客户端由原版声音包同步播放。
     */
    private static void ding(final World world, final BlockPos pos) {
        world.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(),
                SoundCategory.BLOCKS, 0.9F, 1.4F);
        world.playSound(null, pos, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                SoundCategory.BLOCKS, 0.7F, 1.2F);
    }

    /**
     * 背包里有没有一件「还欠着」的充电宝（副手也算）。
     *
     * <p>已经买断的（{@code expiredFine}）与已经损坏的（{@code damaged}，投掷过）都不算：
     * 那是玩家的东西，机器永远拒收，再拿它去挡新租借等于永久封号 —— 所以跳过。
     *
     * <p>副手单列一次：1.20.1 的 {@code PlayerInventory#size()} 只数主背包 36 格，
     * 护甲 / 副手不在里面（主手那一路由 {@code onUse} 的「手持充电宝」分支处理）。
     */
    private static ItemStack findRented(final PlayerEntity player) {
        final ItemStack offHand = player.getOffHandStack();
        if (isOwing(offHand)) {
            return offHand;
        }
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            final ItemStack stack = player.getInventory().getStack(slot);
            if (isOwing(stack)) {
                return stack;
            }
        }
        return null;
    }

    /** 这一件是不是「还欠着」：租借中、没买断、没损坏。 */
    private static boolean isOwing(final ItemStack stack) {
        return stack.getItem() instanceof PowerBankItem && PowerBankItem.isRented(stack)
                && !PowerBankItem.isOverdueSettled(stack) && !PowerBankItem.isDamaged(stack);
    }

    // ------------------------------------------------------------------ 提示

    @Override
    public void appendTooltip(final ItemStack stack, final BlockView world,
                              final List<Text> tooltip, final TooltipContext context) {
        tooltip.add(Text.translatable(this.tooltipKey).formatted(Formatting.GRAY));
    }
}
