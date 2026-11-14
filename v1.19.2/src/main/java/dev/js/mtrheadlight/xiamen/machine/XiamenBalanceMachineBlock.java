// 厦门地铁余额查询机：右键 → 服务端读记分板 mtr_balance → 一句文字报余额。
package dev.js.mtrheadlight.xiamen.machine;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardPlayerScore;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * 厦门地铁余额查询机 {@code mtrlte:balance_machine_xiamen}（两格高，下段有物品）。
 *
 * <h2>不做屏幕，就一句文字</h2>
 * <p>照 MTR 原版的做法：余额本身就是记分板里的一格分数，没有「界面」可言，所以右键直接读、直接报。
 *
 * <h2>为什么必须服务端读</h2>
 * <p>余额在 {@code mtr_balance} 这个 objective 里（{@code mtr.data.TicketSystem.BALANCE_OBJECTIVE}），
 * 它<b>不在侧边栏</b>，客户端因此收不到它的分数 —— 客户端读只会得到 0。
 * 方速的 {@code MtrTicketSystem} 用的是<b>同一个键</b>，所以玩家用哪家的闸机进站，这里读到的都是同一份余额。
 *
 * <h2>没有 objective / 没有分数时</h2>
 * <p>玩家从没坐过车时 {@code mtr_balance} 这个 objective 往往根本不存在。这里先
 * {@code getNullableObjective} 再取分，任何一步缺东西都按 <b>0</b> 报，不会 NPE。
 */
public class XiamenBalanceMachineBlock extends XiamenMachineBlock implements BlockEntityProvider {

    /** 查询机的外形包围盒：机身宽 14（x 1..15），屏幕与读卡区向前伸出到 z≈13.4 / 11.7。 */
    private static final double[] LOWER_BOX = {1.0D, 15.0D, 0.0D, 13.42D};
    private static final double[] UPPER_BOX = {1.0D, 15.0D, 0.0D, 11.7D};

    public XiamenBalanceMachineBlock(final AbstractBlock.Settings settings) {
        // 微光：上段（屏幕）8 级、下段（读卡区）6 级 —— 与售票机同一套（见 XiamenMachineBlock#screenGlow）。
        super(screenGlow(settings), LOWER_BOX, UPPER_BOX, "tooltip.mtrlte.balance_machine_xiamen");

    }

    /**
     * 方块实体（纯锚点，见 {@link XiamenBalanceMachineBlockEntity}）：上下两段各一个。
     *
     * <p>几何由 {@link XiamenBalanceMachineRenderer} 运行时读 rawmodels 里的 Blockbench 原始 JSON 画
     * —— 屏幕是 {@code x:-12.5} 的斜面，原版模型格式写不出来（单元素只能单轴，也不能跨格）。
     */
    @Override
    public BlockEntity createBlockEntity(final BlockPos pos, final BlockState state) {
        return new XiamenBalanceMachineBlockEntity(pos, state);
    }

    @Override
    public ActionResult onUse(final BlockState state, final World world, final BlockPos pos,
                              final PlayerEntity player, final Hand hand, final BlockHitResult hit) {
        if (hand != Hand.MAIN_HAND) {
            return ActionResult.PASS;
        }
        // 客户端只做挥手：余额只有服务端的记分板是权威。
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        // 「嘟」一声：用用户提供的读卡器提示音（assets/mtrlte/sounds/ticket_processor_entry.ogg）。
        world.playSound(null, pos,
                new net.minecraft.sound.SoundEvent(new net.minecraft.util.Identifier("mtrlte", "ticket_processor_entry")),
                net.minecraft.sound.SoundCategory.BLOCKS, 0.7F, 1.0F);
        player.sendMessage(Text.translatable("message.mtrlte.balance_machine", balanceOf(world, player)), true);
        return ActionResult.SUCCESS;
    }

    /**
     * 读 {@code mtr_balance}（MTR / 方速共用的余额 objective）。
     *
     * @return 余额；objective 不存在、玩家还没有分数、或任何一步抛异常时都是 {@code 0}
     */
    private static int balanceOf(final World world, final PlayerEntity player) {
        try {
            final Scoreboard scoreboard = world.getScoreboard();
            final ScoreboardObjective objective =
                    scoreboard.getNullableObjective(mtr.data.TicketSystem.BALANCE_OBJECTIVE);
            if (objective == null) {
                return 0;
            }
            final ScoreboardPlayerScore score =
                    scoreboard.getPlayerScore(player.getGameProfile().getName(), objective);
            return score == null ? 0 : score.getScore();
        } catch (Throwable t) {
            return 0;
        }
    }
}
