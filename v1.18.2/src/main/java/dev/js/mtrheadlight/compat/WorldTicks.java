package dev.js.mtrheadlight.compat;

import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.tick.OrderedTick;

/**
 * 1.18.2 stand-in for 1.19+'s {@code World#scheduleBlockTick(BlockPos, Block, int)}.
 *
 * <h2>Why this exists</h2>
 * <p>1.19.3 added {@code World#scheduleBlockTick} as a one-liner that works on both sides. 1.18.2
 * only has the raw {@code getBlockTickScheduler().scheduleTick(OrderedTick)} plumbing: the tick
 * carries its absolute trigger time ({@code world.getTime() + delay}) and a sub-tick ordering
 * value. {@code WorldAccess#getBlockTickScheduler()} is implemented by both {@code ServerWorld} and
 * {@code ClientWorld}, so this helper covers either side exactly like the 1.20.1 call did.
 *
 * <p>{@code ServerWorld} also has a {@code getBlockTickScheduler()} overload that returns
 * {@code WorldTickScheduler}; the {@code QueryableTickScheduler} interface from
 * {@code WorldAccess} is what both sides share, so it is what is called here.
 */
public final class WorldTicks {

    private WorldTicks() {
    }

    /** Schedules {@code block} at {@code pos} to receive {@code scheduledTick} in {@code delay} ticks. */
    public static void schedule(final World world, final BlockPos pos, final Block block,
                                final int delay) {
        if (world == null || pos == null || block == null) {
            return;
        }
        try {
            final long trigger = world.getTime() + Math.max(0, delay);
            world.getBlockTickScheduler().scheduleTick(
                    new OrderedTick<Block>(block, pos, trigger, world.getTickOrder()));
        } catch (Throwable ignored) {
            // A scheduling failure must never break the caller's tick/render path.
        }
    }
}
