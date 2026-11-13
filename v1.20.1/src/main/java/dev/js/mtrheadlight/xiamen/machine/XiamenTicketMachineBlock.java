// 厦门地铁售票机：右键出票，刷子右键循环「方速界面 → 原版 MTR 界面 → 节能关机」。
package dev.js.mtrheadlight.xiamen.machine;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * 厦门地铁售票机 {@code mtrlte:ticket_machine_xiamen}（两格高，下段有物品）。
 *
 * <h2>三种运行状态，一套方块</h2>
 * <table border="1">
 *   <caption>同一台机器，三种状态（刷子循环切换）</caption>
 *   <tr><th></th><th>方速（fangsu）</th><th>原版（vanilla）</th><th>节能关机（eco）</th></tr>
 *   <tr><td>谁开界面</td><td><b>纯客户端</b>：{@code ClientHooks.openTicketMachineScreen(Text, BlockPos)}</td>
 *       <td><b>服务端</b>：{@code PacketTrainDataGuiServer.openTicketMachineScreenS2C(World, ServerPlayerEntity)}</td>
 *       <td><b>不开界面</b>：只回一句操作栏「售票机已关机」</td></tr>
 *   <tr><td>用车站在哪</td><td>界面自己按 <b>pos</b> 找最近车站（{@code MtrUtil.getStationAt}），
 *       <b>不校验</b>这个 pos 上是不是方速自己的方块</td>
 *       <td>不用 pos，MTR 的界面只认玩家</td>
 *       <td>—</td></tr>
 *   <tr><td>贴图</td><td colspan="2">{@code amtr_ticket.png}（模型 {@code block/ticket_machine_xiamen_{bottom,top}}）</td>
 *       <td>{@code amtr_ticket_closed.png}（模型 {@code block/ticket_machine_xiamen_{bottom,top}_closed}，
 *       只是换贴图的子模型，几何一模一样）</td></tr>
 * </table>
 * <p>所以两种界面都能对着<b>我们自己的方块</b>开，不需要 mixin、不需要往世界里塞隐形方速方块，
 * 也不需要在编译期依赖方速（{@link FangsuTicketUi} 是纯反射）。
 *
 * <h2>状态存在方块状态里（{@code ui}）</h2>
 * <p>只用方块实体存是不够的：方速界面要在<b>客户端</b>开，而客户端读不到服务端方块实体的数据。
 * 方块状态本来就双向同步，所以「这台机器处在哪个状态」直接做成
 * {@code ui=fangsu|vanilla|eco} 属性，上段跟着下段走，刷子切换时两格一起改。
 * 关机态也复用这一个属性（{@code ui=eco} 就是断电），它同时决定 blockstate 选哪套模型 ——
 * 换句话说<b>贴图切换不需要额外发包</b>，状态一变客户端自然换成关机贴图。
 *
 * <p>默认值 = 服务端有没有装方速（{@link #defaultUi()}），也就是「装了方速就默认方速界面（开机）」。
 *
 * <h2>刷子循环</h2>
 * <pre>
 *   方速界面 ──刷子──▶ 原版界面 ──刷子──▶ 节能关机 ──刷子──▶ 方速界面（恢复默认）
 * </pre>
 * <ul>
 *   <li><b>空手 / 任意物品右键</b> → 按 {@code ui} 开对应界面；关机态只回一句提示；</li>
 *   <li><b>MTR 刷子右键</b> → 按上表循环一步（服务端权威），操作栏提示切换后的状态；</li>
 *   <li>没装方速时跳过方速这一档（提示「未检测到方速」），落回原版界面；</li>
 *   <li>客户端没装方速但方块是 {@code ui=fangsu} 时，给出「用刷子切到原版」的提示，而不是点了没反应。</li>
 * </ul>
 */
public class XiamenTicketMachineBlock extends XiamenMachineBlock {

    /** 这台机器用哪套界面 / 是不是关机。 */
    public static final EnumProperty<TicketUi> UI = EnumProperty.of("ui", TicketUi.class);

    /** 售票机的外形包围盒：下段整格（主体 + 正面斜台），上段比下段薄（正面缩到 z≈14.3）。 */
    private static final double[] LOWER_BOX = {0.0D, 16.0D, 0.0D, 16.0D};
    private static final double[] UPPER_BOX = {0.0D, 16.0D, 0.0D, 14.31D};

    /** 售票界面 / 电源状态（刷子循环：方速 → 原版 → 节能关机 → 方速）。 */
    public enum TicketUi implements StringIdentifiable {
        /** 方速的售票界面（装了就默认用它，也就是「开机」的默认档）。 */
        FANGSU("fangsu"),
        /** MTR 原版售票机界面。 */
        VANILLA("vanilla"),
        /** 节能关机：整机断电，贴图换成 {@code amtr_ticket_closed}，右键不给界面。 */
        ECO("eco");

        private final String name;

        TicketUi(final String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return this.name;
        }

        /** 刷子的下一档：方速 → 原版 → 节能关机 → 方速。 */
        public TicketUi next() {
            return switch (this) {
                case FANGSU -> VANILLA;
                case VANILLA -> ECO;
                case ECO -> FANGSU;
            };
        }

        /** 关机态：不开界面、整机用关机贴图。 */
        public boolean poweredOff() {
            return this == ECO;
        }
    }

    public XiamenTicketMachineBlock(final AbstractBlock.Settings settings) {
        super(settings, LOWER_BOX, UPPER_BOX, "tooltip.mtrlte.ticket_machine_xiamen");

    }

    /** 新放下的机器默认用哪套界面：装了方速就方速（= 开机默认档；没装方速就是原版）。 */
    public static TicketUi defaultUi() {
        return FangsuTicketUi.loaded() ? TicketUi.FANGSU : TicketUi.VANILLA;
    }

    @Override
    protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(UI);
    }

    /**
     * 放置时把 {@code ui} 定成「装了方速就方速」。
     *
     * <p>默认状态本身保持枚举的第一个值（{@code fangsu}），<b>不在构造器里 setDefaultState</b>：
     * 那会在方块注册完成之前就把 {@code getDefaultState()} 用起来，和方速一起装上时会让
     * Minecraft 在 registry 冻结时抛
     * {@code IllegalStateException: Some intrusive holders were not registered}（客户端启动即崩）。
     * 放置路径本来就会写全 FACING / HALF，这里顺手把 UI 也写进去即可。
     */
    @Override
    public BlockState getPlacementState(final ItemPlacementContext ctx) {
        final BlockState placed = super.getPlacementState(ctx);
        return placed == null ? null : placed.with(UI, defaultUi());
    }

    // ------------------------------------------------------------------ 右键

    @Override
    public ActionResult onUse(final BlockState state, final World world, final BlockPos pos,
                              final PlayerEntity player, final Hand hand, final BlockHitResult hit) {
        if (hand != Hand.MAIN_HAND) {
            return ActionResult.PASS;
        }
        final BlockPos base = lowerPos(state, pos);

        // ① 刷子：循环 方速 → 原版 → 节能关机 → 方速。服务端权威，客户端只做挥手。
        if (player.isHolding(mtr.Items.BRUSH.get())) {
            if (world.isClient) {
                return ActionResult.SUCCESS;
            }
            final TicketUi next = state.get(UI).next();
            if (next == TicketUi.FANGSU && !FangsuTicketUi.loaded()) {
                // 这一档要回到方速，但服务端没装方速：跳过它落回原版，免得刷子「点到没变化」。
                setUi(world, base, TicketUi.VANILLA);
                player.sendMessage(Text.translatable("message.mtrlte.ticket_machine.no_fangsu"), true);
                return ActionResult.SUCCESS;
            }
            setUi(world, base, next);
            player.sendMessage(next.poweredOff()
                    ? Text.translatable("message.mtrlte.ticket_machine.eco")
                    : Text.translatable("message.mtrlte.ticket_machine.ui",
                            Text.translatable("message.mtrlte.ticket_machine.ui." + next.asString())), true);
            return ActionResult.SUCCESS;
        }

        // ② 节能关机：不给界面，只回一句提示（服务端说，客户端不重复）。
        if (state.get(UI).poweredOff()) {
            if (!world.isClient) {
                player.sendMessage(Text.translatable("message.mtrlte.ticket_machine.powered_off"), true);
            }
            return ActionResult.SUCCESS;
        }

        // ③ 方速界面：纯客户端，服务端什么都不做。
        if (state.get(UI) == TicketUi.FANGSU) {
            if (world.isClient) {
                if (FangsuTicketUi.open(Text.translatable("screen.mtrlte.ticket_machine"), base)) {
                    return ActionResult.SUCCESS;
                }
                // 客户端没有方速（服务端装了、客户端没装）：提示一次。
                // 不返回 PASS —— 否则手里的方块会被顺手放在机器上。
                player.sendMessage(Text.translatable("message.mtrlte.ticket_machine.client_missing"), true);
                return ActionResult.SUCCESS;
            }
            // 服务端自己都没装方速（客户端装了、服务端没装）：退回原版界面，免得点了没反应。
            if (!FangsuTicketUi.loaded()) {
                openVanilla(world, player);
            }
            return ActionResult.SUCCESS;
        }

        // ④ 原版 MTR 界面：服务端发包（客户端不用做任何事）。
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        openVanilla(world, player);
        return ActionResult.SUCCESS;
    }

    /** 原版（MTR）售票界面的入口：服务端把「开界面」这个包发给点方块的玩家。 */
    private static void openVanilla(final World world, final PlayerEntity player) {
        if (player instanceof ServerPlayerEntity serverPlayer) {
            mtr.packet.PacketTrainDataGuiServer.openTicketMachineScreenS2C(world, serverPlayer);
        }
    }

    /** 上下两格一起改（方块状态变了客户端会自动收到更新，不需要额外的包）。 */
    private static void setUi(final World world, final BlockPos base, final TicketUi ui) {
        for (final BlockPos p : new BlockPos[]{base, base.up()}) {
            final BlockState s = world.getBlockState(p);
            if (s.getBlock() instanceof XiamenTicketMachineBlock) {
                world.setBlockState(p, s.with(UI, ui), Block.NOTIFY_ALL);
            }
        }
    }
}
