// 厦门 / BRT 闸机的方块本体：直接继承 MTR 的 BlockTicketBarrier，只把音效换成南滨的。
package dev.js.mtrheadlight.xiamen.ticket;

import mtr.block.BlockTicketBarrier;
import mtr.data.TicketSystem;
import mtr.mappings.Utilities;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * 一款闸机的方块：{@code BlockTicketBarrier} 的子类，进站 / 出站各一个实例。
 *
 * <h2>为什么不自己写一套闸机</h2>
 * <p>MTR 的 {@code BlockTicketBarrier} 已经带齐了闸机需要的全部东西：
 * <ul>
 *   <li>{@code OPEN} 属性（{@code TicketSystem.EnumTicketBarrierOpen}：{@code closed / open /
 *       open_concessionary} 三值）—— 模型开合就挂在这个属性上；</li>
 *   <li>{@code onEntityCollision}：玩家穿过时调 {@code TicketSystem.passThrough}，判定、扣费、
 *       记分板（{@code mtr_balance} / {@code mtr_entry_zone}）、音效全在里面；</li>
 *   <li>{@code tick} + {@code Utilities.scheduleBlockTick}：开门 40 tick 后自动关门；</li>
 *   <li>{@code getOutlineShape} / {@code getCollisionShape}：柜体（本地 x 15..16、高 24）+
 *       关着时挡住通道的那道 2/16 薄墙（本地 z 7..9）；</li>
 *   <li>{@code getPlacementState}：放下时按玩家朝向定 {@code facing}，{@code open=closed}。</li>
 * </ul>
 * 天津地铁的闸机也是这么做的（{@code extends BlockTicketBarrier}），所以这是 MTR 生态的既定做法。
 *
 * <h2>为什么必须重写 {@code onEntityCollision}</h2>
 * <p>音效是<b>作为参数</b>传进 {@code passThrough} 的（5 个 {@code SoundEvent}），而 MTR 的
 * {@code BlockTicketBarrier} 在字节码里把 {@code mtr.SoundEvents.TICKET_BARRIER} /
 * {@code TICKET_BARRIER_CONCESSIONARY} 写死了 —— 没有可覆盖的钩子，所以只能照抄那 40 行
 * 判定逻辑、把音效换成我们自己的。判定逻辑逐行对应 MTR 的反编译结果：
 *
 * <pre>
 *   if (world.isClient || !(entity instanceof PlayerEntity)) return;
 *   facing = state.get(FACING);
 *   local  = entity.getPos().subtract(pos.x+0.5, 0, pos.z+0.5).rotateY(radians(facing.asRotation()));
 *   if (open.isOpen())  { if (local.z &gt; 0) 关门; return; }      // 玩家已经穿过，收杆
 *   if (local.z &gt;= 0) return;                                   // 只认从「入口侧」走进来的
 *   result = TicketSystem.passThrough(world, pos, player, isEntrance, !isEntrance, 5 个音效, false);
 *   setBlockState(OPEN = result);
 *   if (result != CLOSED &amp;&amp; !已经排了 tick) scheduleBlockTick(this, 40);
 * </pre>
 *
 * <p><b>不需要任何 mixin</b>：{@code passThrough} 是现成的 {@code public static}，天津地铁打在上面的
 * 换乘逻辑照样生效（装了天津就自动支持换乘计时）。方速的闸机是另一套实现，与这边互不干扰。
 *
 * <h2>进出站的区别只有一处</h2>
 * <p>{@code isEntrance} → {@code passThrough(..., isEntrance = true, isExit = false, ...)}：
 * 进站走 {@code onEnter}（扣费、记入口区），出站走 {@code onExit}（清入口区、按里程扣）。模型、
 * 贴图、方块实体全一样 —— 两个方块用同一套模型（{@code rawmodels/ticket_barrier_*}）。
 *
 * <h2>几何从哪来</h2>
 * <p>方块本身只剩一份空模型（{@code mtrlte:block/empty}），几何由 {@link XiamenTicketGateRenderer}
 * 在运行时按 {@code assets/mtrlte/rawmodels/*.json}（Blockbench 原样导出）逐元素画 ——
 * 素材里的任意旋转角（{@code -11.5 / 3.25 / 44 …}）和三轴旋转原版模型格式都表达不了。
 * 碰撞 / 轮廓仍然直接用 MTR {@code BlockTicketBarrier} 那一份，<b>没有改</b>。
 */
public class XiamenTicketGate extends BlockTicketBarrier implements BlockEntityProvider {

    /** 进站（{@code true}）还是出站。{@code BlockTicketBarrier} 把它存成了私有字段，这里自己留一份。 */
    private final boolean entrance;

    /** 用哪一套原始模型（{@code ticket_barrier} = 厦门地铁，{@code brt_barrier} = 厦门BRT）。 */
    private final String rawModel;

    public XiamenTicketGate(final boolean isEntrance, final String rawModel) {
        super(isEntrance);
        this.entrance = isEntrance;
        this.rawModel = rawModel;
    }

    /** 进站闸机？ */
    public boolean entrance() {
        return this.entrance;
    }

    /** {@code ticket_barrier} / {@code brt_barrier}：{@link XiamenTicketGateRenderer} 拼 {@code _open / _closed} 用。 */
    public String rawModel() {
        return this.rawModel;
    }

    /**
     * 方块实体（纯锚点，见 {@link XiamenTicketGateBlockEntity}）。
     *
     * <p>几何由 {@link XiamenTicketGateRenderer} 在运行时按 rawmodels 里的 Blockbench 原始 JSON 画，
     * 不再有任何 blockstate 模型 —— 因为素材里的旋转角（-11.5 / 3.25 / 44 …）和三轴旋转
     * 原版模型格式表达不了。
     */
    @Override
    public BlockEntity createBlockEntity(final BlockPos pos, final BlockState state) {
        return new XiamenTicketGateBlockEntity(pos, state);
    }

    @Override
    public void onEntityCollision(final BlockState state, final World world, final BlockPos pos,
                                  final Entity entity) {
        if (world.isClient) {
            return;
        }
        if (!(entity instanceof PlayerEntity player)) {
            return;
        }

        // 把玩家位置换到「本格柜体」的局部坐标：+z 是玩家要去的方向（facing 的背面）。
        final Direction facing = state.get(FACING);
        final Vec3d local = player.getPos()
                .subtract(pos.getX() + 0.5D, 0.0D, pos.getZ() + 0.5D)
                .rotateY((float) Math.toRadians(facing.asRotation()));

        final TicketSystem.EnumTicketBarrierOpen open = state.get(OPEN);
        if (open.isOpen()) {
            // 已经开着的闸机：玩家走到「另一侧」就收杆（不等 40 tick 的定时器）。
            if (local.z > 0.0D) {
                world.setBlockState(pos, state.with(OPEN, TicketSystem.EnumTicketBarrierOpen.CLOSED));
            }
            return;
        }
        if (local.z >= 0.0D) {
            // 只有从柜体这一侧（local.z < 0）走进来的才判定，避免站在门里反复刷卡。
            return;
        }

        // 四个「成功」槽位（进 / 进·优惠 / 出 / 出·优惠）全用 crt_ticket，
        // 一个「失败」槽位用降调的 crt_ticket_error —— 见 XiamenTicketGateSounds。
        final TicketSystem.EnumTicketBarrierOpen result = TicketSystem.passThrough(world, pos, player,
                this.entrance, !this.entrance,
                XiamenTicketGateSounds.CRT_TICKET, XiamenTicketGateSounds.CRT_TICKET,
                XiamenTicketGateSounds.CRT_TICKET, XiamenTicketGateSounds.CRT_TICKET,
                XiamenTicketGateSounds.CRT_TICKET_ERROR, false);
        world.setBlockState(pos, state.with(OPEN, result));

        if (result != TicketSystem.EnumTicketBarrierOpen.CLOSED
                && !world.getBlockTickScheduler().isQueued(pos, this)) {
            // 40 tick（2 秒）后由继承来的 tick() 关门。
            Utilities.scheduleBlockTick(world, pos, this, 40);
        }
    }
}
