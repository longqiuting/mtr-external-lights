package dev.js.mtrheadlight.security;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.PotionUtil;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * 液体安检机 {@code mtrlte:liquid_scanner} —— 拿药水 / 水瓶右键，机器「验」一遍：
 * 无害的（原版水瓶、正面药水）还给你，有害的（负面效果药水）当场没收。
 *
 * <h2>行为（逐条对齐素材里的 {@code LiquidScannerBlock}）</h2>
 * <ul>
 *   <li>手持物品<b>不是</b> {@code minecraft:potion} → {@link ActionResult#PASS}，
 *       不吞掉右键（方块交互 / 放置照常）。</li>
 *   <li>是药水：客户端立刻返回 {@link ActionResult#SUCCESS}（挥手动画），逻辑只在服务端跑
 *       —— {@code world.isClient} 早退，不会出现「两边各扣一瓶」。</li>
 *   <li>判定「有害」：{@link PotionUtil#getPotionEffects(ItemStack)} 里<b>只要有一个</b>效果的
 *       {@link StatusEffectCategory} 是 {@code HARMFUL} 就算。
 *       原版水瓶没有任何效果 → 无害 → 放行（这正是「检测原版水瓶液体」那条需求）。</li>
 *   <li>有害：手里那瓶<b>减 1</b>（没收），铁砧落地音效 + 动作栏提示。</li>
 *   <li>无害：复制一份（数量 1）塞回背包，塞不下就丢在脚下，1.4 倍音高的 pling + 动作栏提示。
 *       净变化是 0 瓶 —— 检测不是消耗品。</li>
 * </ul>
 *
 * <h2>结果灯（{@link #RESULT}）</h2>
 * 正面面板上有三颗方灯，模型里是三个独立元素（{@code green} / {@code red} / {@code working}，见 {@link Result}）。
 * 检测完写一次 blockstate（{@link Result#GREEN} 放行 / {@link Result#RED} 没收），
 * blockstate 换成对应模型，{@link #LAMP_TICKS} 后 {@link #scheduledTick} 熄回 {@link Result#NONE}。
 * 第三颗 {@code working} 灯（模型元素 7）是电源 / 工作指示，<b>常亮</b>：三份模型里它一律指到
 * 贴图上的纯白（{@code #ffffff}，UV 8..8.53125 / 6.5..7），不随 {@link #RESULT} 变化；
 * 另外两颗才由 blockstate 决定亮灭（亮 = 绿 {@code #29e64d} / 红 {@code #ee0000}，灭 = 图集里的
 * 暗色块 {@code #353637}）。
 *
 * <p><b>机体本身不发光</b>：方块没有 {@code luminance}（全状态 0 级光照）。灯「亮」靠的是贴图上的
 * 亮色 + {@code shade: false}，不是真的往世界里打方块光 —— 三颗 1/8 格的小灯不该把整格机器点亮，
 * 夜里看起来像一块灯板。{@code shade:false} 反过来是必须的：没有它，指示器的每个面都要再乘一次
 * 方向光，「亮」的时候也只是比周围亮一点点（实机上就是一团微光）。
 *
 * <h2>配置：哪些液体「直接没收」（刷子右键）</h2>
 * 拿刷子右键 → 服务端把 {@link LiquidScannerBlockEntity} 里的名单推给玩家 → 客户端开
 * {@code LiquidScannerConfigScreen} → 「完成」把名单发回去落盘（{@link LiquidScannerNet}）。
 * 名单是<b>每台机器一份</b>；配过之后判定只看名单（{@link LiquidScannerBlockEntity#shouldConfiscate}），
 * 没配过的机器继续用上面那条「有负面效果就没收」的启发式 —— 老存档与从没打开过界面的机器行为不变。
 *
 * <h2>朝向：四个水平方向都能放</h2>
 * 素材的 blockstate 是 {@code facing=north/east/south/west} 四个变体（模型有正面：斜面板 + 按键 / 读数灯），
 * 朝向取「玩家朝向的反面」，与箱子 / 熔炉一致。{@link #getPlacementState} 只给出
 * {@link Direction.Type#HORIZONTAL} 里的值（玩家放下的那一刻只有水平朝向），
 * blockstate 里四个变体一一对应，所以东西方向与南北方向走的是同一条路径，
 * 不存在「只能放南北」的分支。
 *
 * <p><b>模型正面在模型空间的 -X 侧，不在 -Z 侧</b> —— 这是之前「放在地上正面朝侧面」的根因。
 * 读 {@code liquid_scanner.json} 实测：机身 x 3..13 / z 2..14，正面那块斜 45° 的操作面板
 * （{@code rotation z=45}）连同 18 块按键贴片全部落在 <b>低 x</b> 一侧（x ≈ 3.2..6.7，
 * z 从 3.1 排到 12.9），上层机身 {@code x 5.48..13} 恰恰从这一侧后退，给面板让出位置。
 * 也就是说模型自带的正面是「西」，而 vanilla 惯例（熔炉那套）是「{@code y:0} → 正面朝北」。
 * 两者差 90°，所以 blockstate 的 y 旋转整体加了 +90：
 * <pre>
 *   facing=north → y:90    facing=east → y:180
 *   facing=south → y:270   facing=west → y:0
 * </pre>
 * 90° 旋转把模型正面所在的 {@code x = MIN_X} 平面搬到 {@code z = MIN_X} 平面（-Z 侧，北），
 * 于是 {@code facing} 这个属性继续等于「正面实际指向的方向」，{@link #getPlacementState}
 * 里「取玩家朝向的反面」这套逻辑不用改 —— 玩家看着北面放，正面就朝南回看玩家。
 *
 * <h2>碰撞 / 轮廓：按模型真实包围盒，并且跟着 facing 转</h2>
 * 模型（{@code 素材/液体检测机/liquid_scanner.json}，23 个元素、含 45° 元素旋转）实际包围盒是
 * {@code x = 3..13, y = 0..8.66875, z = 2..14}（单位 1/16 格，高 ≈ 0.54 格，只占方块下半）。
 *
 * <p><b>y 轴只写 0..8.66875，绝不做 {@code 16 - y} 之类的镜像</b> —— 之前那版把包围盒上下翻过来，
 * 碰撞挂在方块顶部（悬空），人从机器下面走过去反而被挡，就是「碰撞箱垂直方向反了」的来源。
 * 这里 {@link #MIN_Y} 直接写 0，{@link #MAX_Y} 直接写模型顶面高度，中间没有任何取反。
 *
 * <p>模型会随 blockstate 绕 Y 轴转，所以碰撞箱也必须转，而且必须用<b>和 blockstate 同一个旋转量</b>
 * （见上面的 +90 偏移）：{@code SHAPE_NORTH/EAST/SOUTH/WEST} 里各存一份
 * <b>提前算好</b>的盒子，用 {@link #shapeFor} 取。不能再让四个朝向共用同一个北向形状 ——
 * 那样朝东 / 朝西放下时，机器转了 90° 而碰撞箱没转，视觉和碰撞错位，
 * 贴着机器走会被看不见的边挡住（也最容易被玩家说成「放不出来」）。
 *
 * <p>换算是纯轴交换（模型坐标 → 世界坐标，每转 90° 就是 {@code (x, z) → (16 - z, x)}，
 * 与 blockstate 的 {@code y} 旋转同一套约定）：
 * <pre>
 *   y:0   x' = x            z' = z
 *   y:90  x' = 16 - z       z' = x
 *   y:180 x' = 16 - x       z' = 16 - z
 *   y:270 x' = z            z' = 16 - x
 * </pre>
 * 手写四份而不是调 {@code VoxelShape#rotate}：本形状只有轴对齐的长方体，
 * 轴交换是精确的（宽度 10 ≠ 12 时不会因为 90° 旋转的取整丢掉那 2 格），
 * 也不受各 MC 版本 {@code rotate} 签名差异影响。
 *
 * <h2>渲染层</h2>
 * 贴图里 alpha=0 的区域（扫描口中间、机器四周留白）必须走 {@code cutout}，
 * 否则会被画成黑块 —— 见 {@link SecurityClient}。
 */
public final class LiquidScannerBlock extends HorizontalFacingBlock implements BlockEntityProvider {

    /**
     * 正面操作面板上的两盏结果灯（第三盏 {@code working} 是常亮的电源/工作指示，不参与结果）。
     *
     * <p>模型里三个灯是三个独立的小方块元素（{@code 素材/液体检测机/liquid_scanner.json} 的
     * {@code groups}：{@code green} → 元素 5、{@code red} → 元素 6、{@code working} → 元素 7），
     * 贴图里它们各自是一整片纯色（{@code #29e64d} / {@code #ee0000} / {@code #ffffff}），
     * 所以「亮 / 灭」不需要新贴图，只要把这两块元素的 UV 指到别处：
     * <pre>
     *   亮 = 原来那片纯色（绿 / 红）
     *   灭 = 图集里一块暗色平地（{@code #353637}，UV 8.3..8.7 / 0.55..0.95）
     * </pre>
     * 三份模型 {@code liquid_scanner}（两颗结果灯全灭）/ {@code _green} / {@code _red} 就是这么来的，
     * 由 {@code blockstates/liquid_scanner.json} 按 {@link #RESULT} 挑一份；{@code working} 那颗
     * 在三份里都指向白（常亮），换模型时看不出变化。灯元素本身的位置、
     * 尺寸、45° 旋转一概没动，所以正面看还是那三颗方灯。
     */
    public enum Result implements StringIdentifiable {
        /** 待机：两盏都灭。 */
        NONE("none"),
        /** 检测通过（放行 / 返还）——绿灯亮。 */
        GREEN("green"),
        /** 没收——红灯亮。 */
        RED("red");

        private final String id;

        Result(final String id) {
            this.id = id;
        }

        /** blockstates 里 {@code result=none|green|red} 就是这三个字符串。 */
        @Override
        public String asString() {
            return this.id;
        }
    }

    /** blockstate 属性 {@code result}，与 blockstates 里的变体名一一对应（小写枚举名）。 */
    public static final EnumProperty<Result> RESULT = EnumProperty.of("result", Result.class);

    /** 亮灯持续多少 tick。连刷多瓶时以<b>最后一次</b>排的 tick 为准的只有「最后一瓶」——
     * 实际上第一瓶排的那次到点就把灯熄了（3 秒内连刷看到的是连续的红/绿交替，不会卡住）。 */
    public static final int LAMP_TICKS = 60;

    /** 模型包围盒（1/16 格）：x 3..13，z 2..14，y 0..8.66875（素材 Blockbench 实测）。 */
    private static final double MIN_X = 3.0D;
    private static final double MAX_X = 13.0D;
    private static final double MIN_Z = 2.0D;
    private static final double MAX_Z = 14.0D;

    /** 底面贴 y = 0（机器坐在地上）；顶面是模型最高点 ≈ 0.542 格。不做任何翻转。 */
    private static final double MIN_Y = 0.0D;
    private static final double MAX_Y = 8.66875D;

    /**
     * 四个朝向的碰撞 / 轮廓盒子。括号里的角度必须与
     * {@code blockstates/liquid_scanner.json} 里该变体的 {@code y} 值一模一样 ——
     * 模型转多少，盒子就转多少，否则视觉和碰撞再次错位。
     * <p>角度整体比 vanilla 惯例高 90°，因为模型正面在 -X 侧（详见类注释）。
     */
    private static final VoxelShape SHAPE_NORTH = rotatedBox(90);
    private static final VoxelShape SHAPE_EAST = rotatedBox(180);
    private static final VoxelShape SHAPE_SOUTH = rotatedBox(270);
    /** 朝西是唯一不做旋转的变体。 */
    private static final VoxelShape SHAPE_WEST = rotatedBox(0);

    public LiquidScannerBlock() {
        // 素材的设置是 copy(IRON_BLOCK) + 3.5 硬度 + 金属音 + 需要工具。
        // 额外加 nonOpaque()：模型不是满方块，不这样相邻面的剔除会按满方块算。
        super(AbstractBlock.Settings.create()
                .mapColor(MapColor.IRON_GRAY)
                .strength(3.5F)
                .sounds(BlockSoundGroup.METAL)
                .requiresTool()
                .nonOpaque());
        // 不加 .luminance(...)：机体在任何 result 状态下都不发光（0 级光照）。
        // 三颗指示灯只是贴图上的亮色 + shade:false，不发方块光 —— 见类注释。
        setDefaultState(getStateManager().getDefaultState()
                .with(FACING, Direction.NORTH)
                .with(RESULT, Result.NONE));
    }

    /** 造一个「底面 y=0、顶面 y=MAX_Y」的轴对齐盒子（x1/x2 是 x，z1/z2 是 z）。 */
    private static VoxelShape box(final double x1, final double x2, final double z1, final double z2) {
        return Block.createCuboidShape(x1, MIN_Y, z1, x2, MAX_Y, z2);
    }

    /**
     * 把模型包围盒按 blockstate 的 {@code y} 旋转转成世界坐标的盒子。
     * 每 90° 就是 {@code (x, z) → (16 - z, x)}：正面所在的 {@code x = MIN_X} 平面
     * 转 90° 后落到 {@code z = MIN_X}（北），转 180° 落到 {@code x = MAX_X}（东），依此类推。
     */
    private static VoxelShape rotatedBox(final int yRotation) {
        switch (((yRotation % 360) + 360) % 360) {
            case 90:
                return box(16.0D - MAX_Z, 16.0D - MIN_Z, MIN_X, MAX_X);
            case 180:
                return box(16.0D - MAX_X, 16.0D - MIN_X, 16.0D - MAX_Z, 16.0D - MIN_Z);
            case 270:
                return box(MIN_Z, MAX_Z, 16.0D - MAX_X, 16.0D - MIN_X);
            case 0:
            default:
                return box(MIN_X, MAX_X, MIN_Z, MAX_Z);
        }
    }

    /** 取当前 facing 对应的（已经转好的）碰撞 / 轮廓形状。 */
    private static VoxelShape shapeFor(final Direction facing) {
        switch (facing) {
            case EAST:
                return SHAPE_EAST;
            case SOUTH:
                return SHAPE_SOUTH;
            case WEST:
                return SHAPE_WEST;
            case NORTH:
            default:
                // facing 只可能是四个水平方向（FACING 用的是 HORIZONTAL_FACING），这里的 default
                // 只是给「状态里莫名出现别的值」兜底，不会在正常游玩里被走到。
                return SHAPE_NORTH;
        }
    }

    @Override
    protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, RESULT);
    }

    /**
     * 正面朝向玩家（取反 = 面向玩家）。
     *
     * <p>{@link ItemPlacementContext#getHorizontalPlayerFacing()} 只会返回四个水平方向
     * （玩家 pitch 再陡也一样），取反还是水平方向 —— 四个方向都能落到 blockstate 的四个变体上。
     * 没有玩家（发射器 / 命令 / 结构方块）时该方法自带 NORTH 兜底，不会 NPE。
     */
    @Override
    public BlockState getPlacementState(final ItemPlacementContext ctx) {
        final Direction facing = ctx.getHorizontalPlayerFacing().getOpposite();
        return getDefaultState().with(FACING, facing);
    }

    @Override
    public VoxelShape getOutlineShape(final BlockState state, final BlockView world,
                                      final BlockPos pos, final ShapeContext context) {
        return shapeFor(state.get(FACING));
    }

    @Override
    public VoxelShape getCollisionShape(final BlockState state, final BlockView world,
                                        final BlockPos pos, final ShapeContext context) {
        return shapeFor(state.get(FACING));
    }

    @Override
    public BlockEntity createBlockEntity(final BlockPos pos, final BlockState state) {
        return new LiquidScannerBlockEntity(pos, state);
    }

    /** 「有负面效果就算有害」—— 机器没配过名单时的默认判定（也是界面「恢复默认」预选的那份）。 */
    public static boolean isHarmful(final ItemStack stack) {
        return PotionUtil.getPotionEffects(stack).stream()
                .map(StatusEffectInstance::getEffectType)
                .anyMatch(effect -> effect.getCategory() == StatusEffectCategory.HARMFUL);
    }

    /**
     * 亮灯：写 {@link #RESULT} + 排一次熄灯 tick。
     *
     * <p>只有服务端改 blockstate（{@code world.setBlockState} 在客户端改只会本地闪烁然后被同步覆盖）。
     * 连刷时每次都会覆盖 {@code RESULT}，灯始终反映<b>最近一瓶</b>的结果；3 秒后统一熄灭。
     */
    private void showLamp(final World world, final BlockPos pos, final BlockState state,
                          final Result result) {
        if (!(world instanceof ServerWorld)) {
            return;
        }
        world.setBlockState(pos, state.with(RESULT, result), Block.NOTIFY_ALL);
        world.scheduleBlockTick(pos, this, LAMP_TICKS);
    }

    /** 到点熄灯。状态已经不是本方块（被替换 / 被拆）时什么也不做。 */
    @Override
    public void scheduledTick(final BlockState state, final ServerWorld world, final BlockPos pos,
                              final Random random) {
        if (state.get(RESULT) != Result.NONE) {
            world.setBlockState(pos, state.with(RESULT, Result.NONE), Block.NOTIFY_ALL);
        }
    }

    @Override
    public ActionResult onUse(final BlockState state, final World world, final BlockPos pos,
                              final PlayerEntity player, final Hand hand, final BlockHitResult hit) {
        final ItemStack held = player.getStackInHand(hand);

        // ① 刷子 → 打开配置界面（选哪些液体「直接没收」）。客户端只做挥手，
        //    数据由服务端从方块实体读出来再下发，见 LiquidScannerNet。
        if (held.isOf(mtr.Items.BRUSH.get())) {
            // 客户端只挥手；服务端才发包（模式匹配而不是硬转：假玩家 / 模组玩家实体不该类转换崩溃）。
            if (world.isClient || !(player instanceof ServerPlayerEntity serverPlayer)) {
                return ActionResult.SUCCESS;
            }
            LiquidScannerNet.sendOpen(serverPlayer, pos,
                    world.getBlockEntity(pos) instanceof LiquidScannerBlockEntity entity ? entity : null);
            return ActionResult.SUCCESS;
        }

        if (!held.isOf(Items.POTION)) {
            return ActionResult.PASS;
        }
        // 客户端只做挥手；扣瓶 / 回瓶 / 音效 / 亮灯全部服务端权威（单人时的服务端就是同一个进程）。
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }

        // ② 检测：配过名单就只看名单，没配过退回「有负面效果就没收」。
        final LiquidScannerBlockEntity entity =
                world.getBlockEntity(pos) instanceof LiquidScannerBlockEntity be ? be : null;
        final boolean confiscate = entity == null ? isHarmful(held) : entity.shouldConfiscate(held);

        if (confiscate) {
            // 没收：手里那一瓶减 1，罐子一起收走（与素材一致）。
            held.decrement(1);
            world.playSound(null, pos, SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.BLOCKS, 0.8F, 0.7F);
            player.sendMessage(Text.translatable("message.mtrlte.liquid_scanner.confiscated"), true);
            showLamp(world, pos, state, Result.RED);
            return ActionResult.CONSUME;
        }

        // 放行：还你一瓶（净变化 0），背包满了就丢在脚下。
        final ItemStack scanned = held.copyWithCount(1);
        held.decrement(1);
        if (!player.getInventory().insertStack(scanned)) {
            player.dropItem(scanned, false);
        }
        world.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(),
                SoundCategory.BLOCKS, 1.0F, 1.4F);
        player.sendMessage(Text.translatable("message.mtrlte.liquid_scanner.approved"), true);
        showLamp(world, pos, state, Result.GREEN);
        return ActionResult.CONSUME;
    }
}
