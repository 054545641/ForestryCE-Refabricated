package forestry.core.platform.mixin;

import forestry.core.platform.block.DirectionalFireSpread;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FireBlock.class)
abstract class FireBlockMixin {
    @Shadow private void checkBurnOut(net.minecraft.world.level.Level level, BlockPos pos, int chance, net.minecraft.util.RandomSource random, int age) { throw new AssertionError(); }

    @org.spongepowered.asm.mixin.injection.Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/FireBlock;checkBurnOut(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;ILnet/minecraft/util/RandomSource;I)V"))
    private void forestry$burnFace(FireBlock block, net.minecraft.world.level.Level level, BlockPos pos, int chance, net.minecraft.util.RandomSource random, int age,
            BlockState fire, net.minecraft.server.level.ServerLevel server, BlockPos origin, net.minecraft.util.RandomSource tickRandom) {
        var state = level.getBlockState(pos);
        var face = Direction.getApproximateNearest(origin.getX() - pos.getX(), origin.getY() - pos.getY(), origin.getZ() - pos.getZ());
        if (state.getBlock() instanceof forestry.core.platform.block.ForestryCombustible rule && !rule.isFlammable(state, level, pos, face)) return;
        checkBurnOut(level, pos, chance, random, age);
    }

    @org.spongepowered.asm.mixin.injection.Redirect(method = "checkBurnOut", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean forestry$meltBurningWax(net.minecraft.world.level.Level level, BlockPos pos, BlockState replacement, int flags) {
        if (level.getBlockState(pos).getBlock() instanceof forestry.apiculture.bees.BlockWax wax && wax.melts()) return wax.melt(level, pos);
        return level.setBlock(pos, replacement, flags);
    }

    @org.spongepowered.asm.mixin.injection.Redirect(method = "checkBurnOut", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean forestry$meltConsumedWax(net.minecraft.world.level.Level level, BlockPos pos, boolean moving) {
        if (level.getBlockState(pos).getBlock() instanceof forestry.apiculture.bees.BlockWax wax && wax.melts()) return wax.melt(level, pos);
        return level.removeBlock(pos, moving);
    }

    @Shadow protected abstract int getIgniteOdds(BlockState state);

    @Inject(method = "getIgniteOdds(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)I", at = @At("HEAD"), cancellable = true)
    private void forestry$directionalSpread(LevelReader level, BlockPos pos, CallbackInfoReturnable<Integer> result) {
        if (!level.isEmptyBlock(pos)) return;
        boolean custom = false;
        int maximum = 0;
        for (Direction side : Direction.values()) {
            BlockPos neighbor = pos.relative(side);
            BlockState state = level.getBlockState(neighbor);
            int odds;
            if (state.getBlock() instanceof DirectionalFireSpread rule) {
                custom = true;
                odds = state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
                    && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED) ? 0
                    : rule.getFireSpreadSpeed(state, level, neighbor, side.getOpposite());
            } else odds = getIgniteOdds(state);
            maximum = Math.max(maximum, odds);
        }
        if (custom) result.setReturnValue(maximum);
    }
}
