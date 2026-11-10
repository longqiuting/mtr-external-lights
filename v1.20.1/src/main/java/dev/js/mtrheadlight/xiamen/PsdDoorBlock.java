// Ported from Minecraft Transit Railway (MTR), MIT License.
package dev.js.mtrheadlight.xiamen;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;

/**
 * MTR {@code mtr.block.BlockPSDAPGDoorBase} + {@code TileEntityPSDAPGDoorBase} 的移植。
 *
 * <h2>门扇开合（与 MTR 逐字段一致）</h2>
 * <p>开合状态不在 blockstate 里，而在方块实体的 {@code open}（0..32）；客户端用
 * {@code openClient} 做插值，{@code getOpen(delta)} 返回 0..1，渲染时按这个量平移整扇门。
 * {@code open} 变化会 {@code markDirty()} 并向客户端同步一次（MTR 的 {@code syncData()}）。
 *
 * <h2>锁定</h2>
 * <p>拿着刷子右键 = 锁定 / 解锁（连同上下格与左右扇）。解锁（{@code unlocked=true}）的门
 * 被 {@link #serverTick} 强制关上 —— 和 MTR 的 {@code tick()} 一样。
 *
 * <h2>自毁</h2>
 * <p>{@code getStateForNeighborUpdate} 里，另一半（{@code IBlock.getSideDirection}）没了就变空气；
 * {@code END} 由「外侧一格是不是端部收口」现算。
 */
public abstract class PsdDoorBlock extends PsdAPGBaseBlock implements BlockEntityProvider {

    /** MTR 的 {@code MAX_OPEN_VALUE}：open 的上限，也是除以它换成 0..1 的分母。 */
    public static final int MAX_OPEN_VALUE = 32;

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        if (PsdProps.sideDirection(state) == direction && !neighborState.isOf(this)) {
            return Blocks.AIR.getDefaultState();
        }
        BlockState superState = super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
        if (superState.getBlock() == Blocks.AIR) {
            return superState;
        }
        boolean end = world.getBlockState(pos.offset(PsdProps.sideDirection(state).getOpposite()))
                .getBlock() instanceof PsdGlassEndBlock;
        return superState.with(PsdProps.END, end);
    }

    @Override
    public void onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        BlockPos offsetPos = pos;
        if (PsdProps.prop(state, PsdProps.HALF) == DoubleBlockHalf.UPPER) {
            offsetPos = offsetPos.down();
        }
        if (PsdProps.prop(state, PsdProps.SIDE) == PsdProps.Side.RIGHT) {
            offsetPos = offsetPos.offset(PsdProps.sideDirection(state));
        }
        PsdProps.onBreakCreative(world, player, offsetPos);
        super.onBreak(world, pos, state, player);
    }

    /** MTR 的 {@code tick(state, ServerWorld, pos)}：解锁的门强制关上。 */
    @Override
    public void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        BlockEntity entity = world.getBlockEntity(pos);
        if (PsdProps.prop(state, PsdProps.UNLOCKED) && entity instanceof TileEntityPsdDoor door) {
            door.setOpen(0);
        }
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                              Hand hand, BlockHitResult hit) {
        return PsdProps.checkHoldingBrush(world, player, () -> {
            boolean unlocked = PsdProps.prop(state, PsdProps.UNLOCKED);
            for (int y = -1; y <= 1; y++) {
                BlockState scanState = world.getBlockState(pos.up(y));
                if (!state.isOf(scanState.getBlock())) {
                    continue;
                }
                lockDoor(world, pos.up(y), scanState, !unlocked);
            }
            player.sendMessage(Text.translatable(!unlocked
                    ? "message.mtrlte.psd_unlocked"
                    : "message.mtrlte.psd_locked"), true);
        });
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        BlockEntity entity = world.getBlockEntity(pos);
        return entity instanceof TileEntityPsdDoor door && door.isOpen()
                ? VoxelShapes.empty()
                : super.getCollisionShape(state, world, pos, context);
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(PsdProps.END, FACING, PsdProps.HALF, PsdProps.SIDE, PsdProps.UNLOCKED);
    }

    /** MTR 的 {@code lockDoor}：把左右两扇和这一格一起写成 unlocked。 */
    private static void lockDoor(World world, BlockPos pos, BlockState state, boolean unlocked) {
        Direction facing = PsdProps.prop(state, FACING);
        BlockPos leftPos = pos.offset(facing.rotateYClockwise());
        BlockPos rightPos = pos.offset(facing.rotateYCounterclockwise());
        BlockState leftState = world.getBlockState(leftPos);
        BlockState rightState = world.getBlockState(rightPos);
        if (leftState.isOf(state.getBlock())) {
            world.setBlockState(leftPos, leftState.with(PsdProps.UNLOCKED, unlocked));
        }
        if (rightState.isOf(state.getBlock())) {
            world.setBlockState(rightPos, rightState.with(PsdProps.UNLOCKED, unlocked));
        }
        world.setBlockState(pos, state.with(PsdProps.UNLOCKED, unlocked));
    }

    // ---- 方块实体 ----------------------------------------------------------

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        if (world.isClient) {
            return null;
        }
        return (tickWorld, tickPos, tickState, blockEntity) -> {
            if (blockEntity instanceof TileEntityPsdDoor door) {
                door.serverTick(tickWorld, tickPos, tickState);
            }
        };
    }

    /** MTR {@code BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase} 的等价物。 */
    public static class TileEntityPsdDoor extends BlockEntity {

        private static final String KEY_OPEN = "open";

        private int open;
        private float openClient;

        /** 渲染插值用的时间戳（客户端）。MTR 用全局 lastFrameDuration，这里按方块实体各算各的。 */
        private long lastFrameNanos;

        public TileEntityPsdDoor(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }

        @Override
        public void readNbt(NbtCompound nbt) {
            super.readNbt(nbt);
            this.open = nbt.getInt(KEY_OPEN);
        }

        @Override
        protected void writeNbt(NbtCompound nbt) {
            super.writeNbt(nbt);
            nbt.putInt(KEY_OPEN, this.open);
        }

        @Override
        public NbtCompound toInitialChunkDataNbt() {
            return this.createNbt();
        }

        @Override
        public Packet<ClientPlayPacketListener> toUpdatePacket() {
            return BlockEntityUpdateS2CPacket.create(this);
        }

        public void setOpen(int open) {
            if (open != this.open) {
                this.open = open;
                this.markDirty();
                this.syncData();
            }
        }

        /** MTR 的 {@code getOpen(lastFrameDuration)}：0..1 的插值开度。 */
        public float getOpen(float lastFrameDuration) {
            return this.getOpen((double) lastFrameDuration);
        }

        /** 同上，只是把时间戳换成真正的帧间隔，避免多扇门抢同一个全局 clock。 */
        public float getOpen(double lastFrameDurationTicks) {
            float change = (float) (lastFrameDurationTicks * 0.95D);
            float target = this.open - 0.1F;
            if (Math.abs(target - this.openClient) < change) {
                this.openClient = target;
            } else if (this.openClient < this.open) {
                this.openClient += change;
            } else {
                this.openClient -= change;
            }
            return this.openClient / 32.0F;
        }

        public boolean isOpen() {
            return this.open > 0;
        }

        public int getOpenValue() {
            return this.open;
        }

        /** 客户端渲染入口：自动算这一帧过去了多少 tick。 */
        public float getOpenForRender() {
            long now = System.nanoTime();
            double deltaTicks = (now - this.lastFrameNanos) / 50_000_000.0D;
            if (this.lastFrameNanos == 0L || deltaTicks < 0.0D) {
                deltaTicks = 0.0D;
            }
            deltaTicks = Math.min(deltaTicks, 1.0D);
            this.lastFrameNanos = now;
            return this.getOpen(deltaTicks);
        }

        /**
         * 服务端每 tick：MTR 的 {@code tick()} 只会「解锁就关上」，开关是列车调的。
         * 本模组不接 MTR 的站台 / 列车门逻辑，因此补一条<b>红石</b>通道（不新增 blockstate 属性）：
         * 未锁定且有红石信号 → 全开，否则全关。
         */
        public void serverTick(World world, BlockPos pos, BlockState state) {
            if (PsdProps.prop(state, PsdProps.UNLOCKED)) {
                this.setOpen(0);
                return;
            }
            this.setOpen(world.isReceivingRedstonePower(pos) ? MAX_OPEN_VALUE : 0);
        }

        /** MTR 的 {@code syncData()}：让客户端重新收一次这块 BE 的数据。 */
        private void syncData() {
            if (this.world instanceof ServerWorld serverWorld) {
                serverWorld.getChunkManager().markForUpdate(this.pos);
            }
        }

        /** MTR 的 {@code getRenderBoundingBox}：整块无限框，避免门滑出格子就消失。 */
        public Box getRenderBoundingBox() {
            return new Box(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                    Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                    Double.POSITIVE_INFINITY);
        }
    }
}
