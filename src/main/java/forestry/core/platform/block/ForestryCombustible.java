package forestry.core.platform.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

public interface ForestryCombustible extends DirectionalFireSpread {
    int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction face);
    boolean isFlammable(BlockState state, BlockGetter level, BlockPos pos, Direction face);
}
