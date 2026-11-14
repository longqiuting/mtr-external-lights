// 放置时的朝向约定 —— 端门 / 滑动门 / 玻璃板共用一处，改朝向只改这里。
package dev.js.mtrheadlight.xiamen.fangsu;

import net.minecraft.item.ItemPlacementContext;
import net.minecraft.util.math.Direction;

/**
 * 方速屏蔽门这套方块<b>放置时</b>的朝向（{@code FACING} 取什么）。
 *
 * <h2>约定：和 MTR 原版屏蔽门逐字节一致</h2>
 * <p>{@code FACING = 玩家水平朝向}（{@code getHorizontalPlayerFacing()}，<b>不</b>取反）。
 * MTR 的 {@code BlockDirectionalDoubleBlockBase#getPlacementState}（javap 3.6.4.2）就是这一句，
 * 含义是「门扇贴的那条边」：玩家朝北放门 → {@code FACING=NORTH} → 几何贴北侧那条边，
 * 也就是玩家对面那条边、正面<b>背离</b>玩家。完整的 javap 依据写在
 * {@link FangsuPsdDoorBlock#getPlacementState} 的注释里。
 *
 * <p>渲染器把 {@code FACING} 映射成 {@code north=0 / east=90 / south=180 / west=270} 的绕 Y 旋转
 * （见 {@link FangsuPsdDraw}），碰撞盒在此基础上再多转 180°（方速的 {@code shape} 与模型差 180°，
 * 见 {@link FangsuPsdDoorBlock#shapeQuarterTurns}）。
 *
 * <h2>为什么单独提出一个类</h2>
 * <p>现在有三个方块类（自动滑动门 {@link FangsuPsdDoorBlock}、端门 {@link FangsuPsdEndDoorBlock}、
 * 玻璃板 {@link FangsuPsdGlassBlock}）各自实现 {@code getPlacementState}，朝向约定必须
 * <b>一字不差</b>地相同 —— 否则同一排门放下去，门扇和玻璃板会朝两个方向。所以取值只在这里写一次。
 *
 * <p><b>要改朝向就改这一行</b>（历史：曾经是 {@code getOpposite()}，那会让整扇门翻到玩家这一侧，
 * 用户反馈「模型和碰撞箱都翻到内侧来了」，已在 2026-11-13 改回与 MTR 一致）。
 */
final class FangsuPsdPlacement {

    private FangsuPsdPlacement() {
    }

    /** 放置时该给 {@code FACING} 的方向。 */
    static Direction facingOf(final ItemPlacementContext ctx) {
        return ctx.getPlayerLookDirection();
    }
}
