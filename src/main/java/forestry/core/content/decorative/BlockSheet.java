package forestry.core.content.decorative;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class BlockSheet extends BlockBurnable {
	public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;

	private static final float DEPTH = 1 / 16f;

	public BlockSheet(Properties properties) {
		super(properties);
		this.registerDefaultState(this.getStateDefinition().any().setValue(FACING, Direction.UP));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return switch (state.getValue(FACING)) {
			case DOWN -> Shapes.box(0, 1 - DEPTH, 0, 1, 1, 1);
			case NORTH -> Shapes.box(0, 0, 1 - DEPTH, 1, 1, 1);
			case SOUTH -> Shapes.box(0, 0, 0, 1, 1, DEPTH);
			case EAST -> Shapes.box(0, 0, 0, DEPTH, 1, 1);
			case WEST -> Shapes.box(1 - DEPTH, 0, 0, 1, 1, 1);
			default -> Shapes.box(0, 0, 0, 1, DEPTH, 1);
		};
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		Level level = context.getLevel();
		BlockPos againstPos = context.getClickedPos().relative(context.getClickedFace().getOpposite());
		BlockState againstState = level.getBlockState(againstPos);

		// copy the orientation of other sheet blocks
		if (againstState.is(this)) {
			return this.defaultBlockState().setValue(FACING, againstState.getValue(FACING));
		}
		return this.defaultBlockState().setValue(FACING, context.getClickedFace());
	}

	// A sheet only burns on the face it lies flat against, the one face it fills

	@Override
	public boolean isFlammable(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		return state.isFaceSturdy(level, pos, direction);
	}
}
