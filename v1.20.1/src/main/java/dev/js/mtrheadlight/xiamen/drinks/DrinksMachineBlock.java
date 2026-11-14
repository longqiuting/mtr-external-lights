// 饮料售卖机（上下两块联动）：空手右键 3 元出一瓶随机正面药水，刷子右键换药水池。
package dev.js.mtrheadlight.xiamen.drinks;

import java.util.List;

import dev.js.mtrheadlight.bind.MtrBalance;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionUtil;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

/**
 * 饮料售卖机：{@code mtrlte:drinks_machine_top}（上半，货道）与
 * {@code mtrlte:drinks_machine_bottom}（下半，取货口 + 投币）。
 *
 * <h2>两个 id、但<b>联动</b>（和充电宝机正好相反）</h2>
 * <p>充电宝机是「两块完全独立」：各自放置、各自掉落、拆一半不带走另一半。饮料机要的是反过来：
 * <b>放一块就等于放一整台，拆哪一块都拆掉整台</b>。做法写在下面三个方法里：
 * <table border="1">
 *   <caption>联动在哪实现</caption>
 *   <tr><th>方法</th><th>干什么</th></tr>
 *   <tr><td>{@link #getPlacementState}</td>
 *       <td><b>挡住放不下的位置</b>：上半要求「下面或上面至少有一格空着」，下半要求「上面那一格空着」。
 *           返回 {@code null} → 原版直接判定放置失败，<b>物品不会被消耗</b>（不会有「放了但没机器」）。</td></tr>
 *   <tr><td>{@link #onPlaced}</td>
 *       <td><b>自动补上另一半</b>（服务端，{@code setBlockState} 客户端自动同步）：
 *           放下半 → 上面补上半；放上半 → 下面补下半；<b>下面已经有方块放不进下半时，整台机器往上挪一格</b>
 *           （下半落在玩家点到的这一格、上半抬到它上面）—— 所以拿着上半对着地面点也能放成一台，
 *           不会出现「点了没反应」。</td></tr>
 *   <tr><td>{@link #getStateForNeighborUpdate}</td>
 *       <td><b>另一半没了自己也走</b>：上段只盯下面、下段只盯上面，不是那一半就返回空气。
 *           拆任一半 → 原版 {@code setBlockState(空气, NOTIFY_NEIGHBORS)} → 另一半收到更新 →
 *           同一 tick 内自己变空气，<b>不掉落</b>。所以「拆一块 = 拆两块、只掉 1 个」
 *           既不用手写 {@code onBreak}，也不会出现「半截机器卡在半空」。</td></tr>
 * </table>
 * <p>为什么不照售票机那样在 {@code onBreak} 里手动清另一半：<b>原版 1.20.1 拆方块的顺序是</b>
 * （{@code ServerPlayerInteractionManager#tryBreakBlock} 的字节码顺序）
 * <pre>
 *   Block.onBreak(...)            // 粒子 + 游戏事件，<b>不掉落</b>
 *   ServerWorld.removeBlock(...)  // → setBlockState(空气, flag 3) → <b>邻居更新</b>（就是上面那条路径）
 *   Block.onBroken(...)
 *   Block.afterBreak(...)         // → dropStacks(拆下来的那份 BlockState 快照) ← 掉落在这里
 * </pre>
 * 也就是说邻居连锁发生在掉落<b>之前</b>，而且掉落用的是「拆下来那一刻的状态快照」，
 * 所以<b>不需要</b>在 {@code onBreak} 里再动世界一下：不动就不会有「清掉另一半 → 自己也被连锁清掉 →
 * 状态快照对不上」这类顺序问题，掉落也永远不会变成 2 个。
 * <p>所以本类<b>一条 {@code onBreak} 都没写</b>：自己那一半的掉落交给战利品表（{@code afterBreak}），
 * 另一半交给邻居更新 —— 两条路互不重叠，恒为 1 个。
 *
 * <h2>{@code open}：只有下半有，两个模型</h2>
 * <p>下半有 {@code open=false|true}，{@code blockstates/drinks_machine_bottom.json} 的 8 个变体
 * （4 朝向 × 2 状态）分别指到 BER 的
 * {@code rawmodels/drinks_machine_bottom_closed.json} / {@code _bottom_opened.json}
 * （选哪份见 {@link #rawModel(BlockState)}）；上半只有 {@code facing}。
 * 状态写方块状态而不是方块实体，是因为<b>客户端要靠它换模型</b> —— 方块状态本来就会同步。
 * <p>卖出一瓶后 {@value #OPEN_TICKS} tick 由 {@link #scheduledTick} 关回 {@code false}。
 *
 * <h2>右键</h2>
 * <ol>
 *   <li><b>MTR 刷子</b> → 循环药水池（{@link DrinksMachinePotions.Preset}），动作栏打印当前这一组；
 *       预设存在下段的方块实体 NBT 里（{@link DrinksMachineBlockEntity}）；</li>
 *   <li><b>空手</b> → 买一瓶：余额不够 <b>不扣钱、不开门</b>；够就扣 {@value #PRICE} 元（MTR 的
 *       {@code mtr_balance}，走 {@link MtrBalance#add}）、「叮」一声、下半 {@code open=true}、
 *       背包里塞一瓶随机正面药水；</li>
 *   <li><b>手里有别的东西</b> → {@link ActionResult#PASS}，照常当普通方块用（能往上放方块）。</li>
 * </ol>
 * <p>点上半和点下半<b>完全等效</b>：{@link #onUse} 一律折算到下半那格去判定 / 扣钱 / 开门。
 *
 * <h2>几何：微光 + 模型</h2>
 * <p>世界里两块都走 BER 画原始模型（blockstate 指 {@code mtrlte:block/empty}），
 * 亮度<b>整块恒定</b>（{@link #TOP_LUMINANCE} / {@link #BOTTOM_LUMINANCE}，方块光照，
 * 不是渲染发光）：上半 10（≈ 荧石的一半，货道玻璃那一块本身是背光的）、下半 6。
 *
 * <p><b>素材的宽度提醒</b>：这两份 Blockbench 模型的实际范围是
 * {@code x = -8..24}（<b>两格宽</b>）、{@code y = 0..16}、{@code z = 0..16}，
 * 也就是设计上是一台「双开门」的宽机器。这里按贴图原样 1:1 画（不压缩，压了所有面板都会被拉扁），
 * 于是模型会向左右各探出半格；碰撞 / 轮廓仍然只占<b>自己这一格</b>（中间那列是实心的）。
 * 如果以后要「塞进一格」，改 {@link DrinksMachineRenderer#MODEL_X_SCALE} 一个数即可。
 */
public class DrinksMachineBlock extends HorizontalFacingBlock implements BlockEntityProvider {

    /** 四向（与 {@code HorizontalFacingBlock.FACING} 同一个属性实例）。 */
    public static final DirectionProperty FACING = HorizontalFacingBlock.FACING;

    /** 取货口开着（只有下半有这个属性）。 */
    public static final BooleanProperty OPEN = BooleanProperty.of("open");

    /** 上半的自发光等级（方块光照 0..15）：货道的背光。 */
    public static final int TOP_LUMINANCE = 10;

    /** 下半的自发光等级：取货口 / 投币面板的一点微光。 */
    public static final int BOTTOM_LUMINANCE = 6;

    /** 一瓶的价格（MTR 余额，$）。需求：统一 3 元，不随药水池变化。 */
    public static final int PRICE = 3;

    /** 卖出后取货口开多久（tick）。需求：20 tick。 */
    public static final int OPEN_TICKS = 20;

    /** 轮廓 / 碰撞：整格（模型中间那一列是实心柜体）。 */
    private static final VoxelShape SHAPE = Block.createCuboidShape(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D);

    /** 运输 / 放置音效用的提示键。 */
    private final String tooltipKey;

    /** 是不是上半（只有它没有 {@code open}）。 */
    private final boolean top;

    public DrinksMachineBlock(final AbstractBlock.Settings settings, final boolean top, final String tooltipKey) {
        super(settings);
        this.top = top;
        this.tooltipKey = tooltipKey;
        // 有 FACING 就必须给默认状态，否则 registry 冻结时崩。
        final BlockState defaults = this.stateManager.getDefaultState().with(FACING, Direction.NORTH);
        this.setDefaultState(this.top ? defaults : defaults.with(OPEN, false));
    }

    /** 是不是上半。 */
    public boolean isTop() {
        return this.top;
    }

    /**
     * 这一格该画哪份原始模型（{@code rawmodels/} 下的名字，渲染器直接用）。
     *
     * <p>上半永远是 {@code drinks_machine_top}；下半按 {@code open} 换 closed / opened ——
     * 这就是「开门状态」在视觉上的全部实现（方块状态一变，客户端自然换模型，不需要额外发包）。
     */
    public static String rawModel(final BlockState state) {
        if (isTopState(state)) {
            return "drinks_machine_top";
        }
        return state.contains(OPEN) && state.get(OPEN) ? "drinks_machine_bottom_opened" : "drinks_machine_bottom_closed";
    }

    // ------------------------------------------------------------------ 形状

    @Override
    protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
        if (!this.top) {
            builder.add(OPEN);
        }
    }

    @Override
    public VoxelShape getOutlineShape(final BlockState state, final BlockView world, final BlockPos pos,
                                      final ShapeContext context) {
        return SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(final BlockState state, final BlockView world, final BlockPos pos,
                                        final ShapeContext context) {
        return SHAPE;
    }

    /** 空集：模型不是满方块（柜体还比一格宽），别让邻居朝我们的面被剔除。 */
    @Override
    public VoxelShape getCullingShape(final BlockState state, final BlockView world, final BlockPos pos) {
        return VoxelShapes.empty();
    }

    /** 几何由 BER 画，所以每块都要一个方块实体当锚点。 */
    @Override
    public BlockEntity createBlockEntity(final BlockPos pos, final BlockState state) {
        return new DrinksMachineBlockEntity(pos, state);
    }

    // ------------------------------------------------------------------ 放置（联动）

    /** 这一格空着吗（空气，或者能被替换掉的东西：草、水、雪片…）。 */
    private static boolean free(final BlockState state) {
        return state.isAir() || state.isReplaceable();
    }

    private static boolean isTopState(final BlockState state) {
        return state.getBlock() instanceof DrinksMachineBlock block && block.top;
    }

    private static boolean isBottomState(final BlockState state) {
        return state.getBlock() instanceof DrinksMachineBlock block && !block.top;
    }

    /** 造一份下半的（干净）状态：朝向跟着来，取货口默认关着。 */
    private static BlockState bottomState(final Direction facing) {
        return DrinksMachineObjects.bottom().getDefaultState().with(FACING, facing).with(OPEN, false);
    }

    /** 造一份上半的（干净）状态。 */
    private static BlockState topState(final Direction facing) {
        return DrinksMachineObjects.top().getDefaultState().with(FACING, facing);
    }

    /**
     * 放置前的「放得下吗」检查（返回 {@code null} = 原版判定放置失败，物品不消耗）。
     *
     * <p>这里<b>不</b>要求另一半已经存在 —— 放置的那一刻它当然还不存在，
     * 另一半是在 {@link #onPlaced} 里补的。这里只保证「补得进去」。
     */
    @Override
    public BlockState getPlacementState(final ItemPlacementContext ctx) {
        final World world = ctx.getWorld();
        final BlockPos pos = ctx.getBlockPos();
        if (pos.getY() >= world.getTopY() - 1) {
            // 上半要占 pos 上面那一格（或者下半上面那格），太高的地方一律不给放。
            return null;
        }
        final Direction facing = ctx.getHorizontalPlayerFacing().getOpposite();
        if (this.top) {
            // 下半放下面（常规）；下面被占了就整台往上挪一格，那时上半要落在 pos.up()。
            if (!free(world.getBlockState(pos.down())) && !free(world.getBlockState(pos.up()))) {
                return null;
            }
            return this.getDefaultState().with(FACING, facing);
        }
        // 下半：上面那一格要空着（上半要占它）。
        if (!free(world.getBlockState(pos.up()))) {
            return null;
        }
        return this.getDefaultState().with(FACING, facing).with(OPEN, false);
    }

    /**
     * 放下任何一半，都把另一半补上（服务端执行；{@code setBlockState} 会同步给客户端）。
     *
     * <p>上半的两种情形：
     * <ul>
     *   <li>下面空着 → 下半放下面（「放置上半自动放下半」的标准路径）；</li>
     *   <li>下面被占（对着地面点、或贴着别人放）→ <b>整台往上挪一格</b>：下半落在玩家点到的这一格，
     *       上半抬到它上面。{@link #getPlacementState} 已经保证上面那一格是空着的。</li>
     * </ul>
     * <p>下半的 onPlaced 只做一件事：上面补上半（已经在了就不动）。
     */
    @Override
    public void onPlaced(final World world, final BlockPos pos, final BlockState state,
                         final LivingEntity placer, final ItemStack itemStack) {
        if (world.isClient) {
            return;
        }
        final Direction facing = state.get(FACING);
        if (this.top) {
            final BlockPos below = pos.down();
            if (free(world.getBlockState(below))) {
                world.setBlockState(below, bottomState(facing), Block.NOTIFY_ALL);
                return;
            }
            final BlockPos above = pos.up();
            if (free(world.getBlockState(above))) {
                world.setBlockState(pos, bottomState(facing), Block.NOTIFY_ALL);
                world.setBlockState(above, topState(facing), Block.NOTIFY_ALL);
            }
            return;
        }
        final BlockPos above = pos.up();
        if (!isTopState(world.getBlockState(above)) && free(world.getBlockState(above))) {
            world.setBlockState(above, topState(facing), Block.NOTIFY_ALL);
        }
    }

    /**
     * 放置时不做「另一半必须存在」的检查（那一刻它还不存在，见 {@link #getPlacementState}），
     * 只挡超界。
     */
    @Override
    public boolean canPlaceAt(final BlockState state, final WorldView world, final BlockPos pos) {
        return pos.getY() < world.getTopY() - 1 && pos.getY() > world.getBottomY();
    }

    /**
     * 另一半没了 → 自己也变空气（<b>不掉落</b>：拆的那一半由战利品表掉 1 个）。
     *
     * <p>只看「朝另一半」那一个方向：上段看 DOWN、下段看 UP。别的方向（例如在头顶再放一块方块，
     * 更新方向是 UP）与这里无关，机器不会被邻居连带拆掉。
     */
    @Override
    public BlockState getStateForNeighborUpdate(final BlockState state, final Direction direction,
                                                final BlockState neighborState, final WorldAccess world,
                                                final BlockPos pos, final BlockPos neighborPos) {
        if (this.top) {
            if (direction == Direction.DOWN && !isBottomState(neighborState)) {
                return Blocks.AIR.getDefaultState();
            }
        } else if (direction == Direction.UP && !isTopState(neighborState)) {
            return Blocks.AIR.getDefaultState();
        }
        return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
    }

    // ------------------------------------------------------------------ 右键

    @Override
    public ActionResult onUse(final BlockState state, final World world, final BlockPos pos,
                              final PlayerEntity player, final Hand hand, final BlockHitResult hit) {
        if (hand != Hand.MAIN_HAND) {
            return ActionResult.PASS;
        }
        // 都折算到下半：点上半和点下半完全等效。
        final BlockPos base = this.top ? pos.down() : pos;
        final BlockState baseState = world.getBlockState(base);
        if (!isBottomState(baseState)) {
            // 理论上走不到（对不上的那一半已经被邻居更新收走了），兜底成普通方块。
            return ActionResult.PASS;
        }

        final ItemStack held = player.getStackInHand(hand);

        // ① 刷子：循环药水池。服务端权威，客户端只做挥手。
        if (held.isOf(mtr.Items.BRUSH.get())) {
            if (world.isClient) {
                return ActionResult.SUCCESS;
            }
            final DrinksMachineBlockEntity entity = blockEntity(world, base);
            if (entity == null) {
                return ActionResult.SUCCESS;
            }
            final DrinksMachinePotions.Preset next = entity.getPreset().next();
            entity.setPreset(next);
            player.sendMessage(Text.translatable("message.mtrlte.drinks_machine.preset", next.displayName()), true);
            return ActionResult.SUCCESS;
        }

        // ② 手里有别的东西：当普通方块（能往上放方块）。
        if (!held.isEmpty()) {
            return ActionResult.PASS;
        }

        // ③ 空手买一瓶：全在服务端（余额只在服务端读得到）。
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        if (baseState.get(OPEN)) {
            player.sendMessage(Text.translatable("message.mtrlte.drinks_machine.open_busy"), true);
            return ActionResult.SUCCESS;
        }

        final int balance = MtrBalance.get(world, player);
        if (balance < PRICE) {
            // 不够 / 欠费：一分不扣、门也不开。
            player.sendMessage(Text.translatable("message.mtrlte.drinks_machine.no_money", balance, PRICE), true);
            return ActionResult.SUCCESS;
        }

        final DrinksMachineBlockEntity entity = blockEntity(world, base);
        final DrinksMachinePotions.Preset preset = entity == null
                ? DrinksMachinePotions.Preset.MIXED : entity.getPreset();
        final Random random = world.getRandom();
        final Potion potion = preset.pick(random);
        final ItemStack bottle = new ItemStack(Items.POTION);
        PotionUtil.setPotion(bottle, potion);

        // 先扣钱再给货：扣钱失败（MTR 没有记分板 / 分数写不进去）就不发东西，避免白送。
        final int after = MtrBalance.add(world, player, -PRICE);
        if (after != balance - PRICE) {
            player.sendMessage(Text.translatable("message.mtrlte.drinks_machine.no_money", balance, PRICE), true);
            return ActionResult.SUCCESS;
        }
        if (!player.getInventory().insertStack(bottle)) {
            player.dropItem(bottle, false);
        }

        // 「叮」一声 + 开门 + 排一次关门 tick。
        world.playSound(null, base, SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.BLOCKS, 0.9F, 1.4F);
        world.playSound(null, base, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.BLOCKS, 0.7F, 1.2F);
        world.setBlockState(base, baseState.with(OPEN, true), Block.NOTIFY_ALL);
        world.scheduleBlockTick(base, DrinksMachineObjects.bottom(), OPEN_TICKS);

        player.sendMessage(Text.translatable("message.mtrlte.drinks_machine.bought", bottle.getName(), after), true);
        return ActionResult.SUCCESS;
    }

    /** 取货口到点关回去（{@link #OPEN_TICKS} tick 之后）。只在服务端被调用。 */
    @Override
    public void scheduledTick(final BlockState state, final ServerWorld world, final BlockPos pos,
                              final Random random) {
        if (!this.top && state.contains(OPEN) && state.get(OPEN)) {
            world.setBlockState(pos, state.with(OPEN, false), Block.NOTIFY_ALL);
        }
    }

    /** 下半那格的方块实体（预设存在它身上）；没有就 {@code null}。 */
    private static DrinksMachineBlockEntity blockEntity(final World world, final BlockPos base) {
        return world.getBlockEntity(base) instanceof DrinksMachineBlockEntity entity ? entity : null;
    }

    // ------------------------------------------------------------------ 提示

    @Override
    public void appendTooltip(final ItemStack stack, final BlockView world,
                              final List<Text> tooltip, final TooltipContext context) {
        tooltip.add(Text.translatable(this.tooltipKey).formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("tooltip.mtrlte.drinks_machine.price", PRICE).formatted(Formatting.GRAY));
    }
}
