// MTR 余额（= 记分板 mtr_balance）的读写：一个薄封装，谁都能查、能扣。
package dev.js.mtrheadlight.bind;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardPlayerScore;
import net.minecraft.world.World;

/**
 * MTR 的「余额」接口。
 *
 * <h2>MTR 的钱在哪</h2>
 * <p>MTR 3.x 没有独立的「经济 API」类：余额就是记分板上的一个分数，objective 名由
 * {@code mtr.data.TicketSystem.BALANCE_OBJECTIVE}（{@code "mtr_balance"}）给出 ——
 * 它与 {@code mtr.data.TransportMode} 同在 {@code mtr.data} 包里，是同一层的东西。
 * 进站扣费（{@code TicketSystem.passThrough}）、方速的 {@code MtrTicketSystem} 用的都是这一份，
 * 所以在哪台闸机 / 哪台机器上读写都落在同一个数上。
 *
 * <h2>为什么必须服务端</h2>
 * <p>这个 objective <b>不在侧边栏</b>，客户端根本收不到它的分数（客户端读只会得到 0）。
 * 所有调用点都必须在服务端线程里（{@code !world.isClient}）。
 *
 * <h2>扣到负数</h2>
 * <p>MTR 自己进站时余额不够会拒绝通行，但<b>不会</b>把分数写成负数。租借机的规矩是
 * 「余额为负不能租借」，也就是说欠费是<b>允许</b>存在的状态（归还时按租期扣，可能扣穿），
 * 所以这里直接 {@link ScoreboardPlayerScore#setScore(int)}，不做任何下限钳制 —— 钳到 0 会让
 * 「欠费 $4 的人还完租继续免费租」变成可能。
 *
 * <p>objective 还不存在时先调 MTR 自己的 {@code TicketSystem.addObjectivesIfMissing(World)}
 * 把它建出来（和玩家第一次进站时 MTR 做的事一样），否则没有分数可写。
 *
 * <p>每一步都吞异常并退回 0 / 原值：MTR 没装、数据坏了都不能让方块右键崩游戏。
 */
public final class MtrBalance {

    /** MTR 的余额 objective 名（{@code mtr_balance}）；MTR 不在时给一个等价常量。 */
    private static final String OBJECTIVE = "mtr_balance";

    private MtrBalance() {
    }

    /** 读余额；没有 objective / 没有分数 / 任何异常 → {@code 0}。 */
    public static int get(final World world, final PlayerEntity player) {
        if (world == null || player == null) {
            return 0;
        }
        try {
            final Scoreboard scoreboard = world.getScoreboard();
            final ScoreboardObjective objective = scoreboard.getNullableObjective(objectiveName());
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

    /** 直接写一个数（不做下限钳制，见类注释）；失败时返回 {@code false}。 */
    public static boolean set(final World world, final PlayerEntity player, final int balance) {
        if (world == null || player == null) {
            return false;
        }
        try {
            // objective 不存在时先建出来：MTR 的 addObjectivesIfMissing 就是干这个的。
            // 用反射里的字符串常量会多一层脆弱，这里直接用 MTR 的公开方法。
            mtr.data.TicketSystem.addObjectivesIfMissing(world);
            final Scoreboard scoreboard = world.getScoreboard();
            final ScoreboardObjective objective = scoreboard.getNullableObjective(objectiveName());
            if (objective == null) {
                return false;
            }
            scoreboard.getPlayerScore(player.getGameProfile().getName(), objective).setScore(balance);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 加减余额（{@code delta} 为负就是扣）。
     *
     * @return 写入后的余额；写失败时返回写入前的余额
     */
    public static int add(final World world, final PlayerEntity player, final int delta) {
        final int before = get(world, player);
        final int after = before + delta;
        return set(world, player, after) ? after : before;
    }

    /**
     * objective 名：优先取 MTR 自己的常量（与服务端装的 MTR 版本严格一致），
     * 拿不到（MTR 类缺失）时退回 {@link #OBJECTIVE}。
     */
    private static String objectiveName() {
        try {
            return mtr.data.TicketSystem.BALANCE_OBJECTIVE;
        } catch (Throwable t) {
            return OBJECTIVE;
        }
    }
}
