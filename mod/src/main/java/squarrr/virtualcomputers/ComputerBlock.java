package squarrr.virtualcomputers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

public abstract class ComputerBlock extends BaseEntityBlock {
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;

    protected ComputerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    protected abstract DeviceKind kind();

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide()
                && level.getBlockEntity(pos) instanceof ComputerBlockEntity computer) {
            computer.adoptFrom(stack);
        }
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level.getBlockEntity(pos) instanceof ComputerBlockEntity computer) {
            String machineId = computer.machineId();
            if (level.isClientSide()) {
                VirtualComputers.interaction.broken(kind(), machineId);
            } else {
                if (!player.getAbilities().instabuild) {
                    Block.popResource(level, pos, computer.withIdentity(new ItemStack(this)));
                }
                onBrokenServerSide(level, pos);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    protected void onBrokenServerSide(Level level, BlockPos pos) {
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                          BlockPos pos, Player player, InteractionHand hand,
                                          BlockHitResult hitResult) {
        if (!(stack.getItem() instanceof OsBoxItem box)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (!level.isClientSide()) {
            return InteractionResult.CONSUME;
        }
        String machineId = level.getBlockEntity(pos) instanceof ComputerBlockEntity computer
                ? computer.machineId() : null;

        return VirtualComputers.interaction.insertMedia(kind(), machineId, box.entryId(stack),
                player.isShiftKeyDown())
                ? InteractionResult.SUCCESS
                : InteractionResult.CONSUME;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            return InteractionResult.CONSUME;
        }
        String machineId = level.getBlockEntity(pos) instanceof ComputerBlockEntity computer
                ? computer.machineId() : null;
        return VirtualComputers.interaction.use(kind(), machineId, pos,
                state.getValue(FACING), hitResult.getLocation())
                ? InteractionResult.SUCCESS
                : InteractionResult.CONSUME;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }
}
