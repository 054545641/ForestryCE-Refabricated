package forestry.core.platform.fluids;

import net.minecraft.world.entity.InsideBlockEffectApplier;

import forestry.core.platform.registration.FeatureFluid;
import forestry.core.platform.registration.FluidProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;

public class BlockForestryFluid extends LiquidBlock {
	private final boolean spreadsFire;
	private final int flammability;
	private final int color;
	private final boolean freezing;
	private final boolean burning;
	private final float explosionPower;
	private final boolean explodes;

	public BlockForestryFluid(BlockBehaviour.Properties blockProperties, FeatureFluid feature) {
		super(feature.fluid(), createBlockProperties(blockProperties, feature.properties()));
		FluidProperties properties = feature.properties();
		this.flammability = properties.flammability;
		this.spreadsFire = properties.spreadsFire;
		this.color = properties.particleColor;
		this.freezing = properties.temperature < 270;
		this.burning = properties.temperature > 505;
		// Explosion size is determined by flammability, up to size 4.
		this.explosionPower = 4F * this.flammability / 300F;
		this.explodes = this.explosionPower > 1.0f;
	}

	private static BlockBehaviour.Properties createBlockProperties(BlockBehaviour.Properties blockProperties, FluidProperties properties) {
		blockProperties.liquid().noCollision().noLootTable().replaceable();

		if (properties.luminosity > 0) {
			var luminosity = properties.luminosity;
			blockProperties.lightLevel(state -> luminosity);
		}

		return blockProperties;
	}

	@Override
	public void entityInside(BlockState pState, Level pLevel, BlockPos pPos, Entity entity, InsideBlockEffectApplier effects, boolean isPrecise) {
		if (this.freezing) {
			entity.setIsInPowderSnow(true);
		} else if (this.burning) {
			entity.igniteForSeconds(5.0F);
			if (pLevel instanceof ServerLevel server) entity.hurtServer(server, pLevel.damageSources().lava(), 1);
		}
	}

	public int getFireSpreadSpeed(BlockState state, BlockGetter world, BlockPos pos, Direction face) {
		return this.spreadsFire ? 30 : 0;
	}

	public int getFlammability(BlockState state, BlockGetter world, BlockPos pos, Direction face) {
		return this.flammability;
	}

	public boolean isFlammable(BlockState state, BlockGetter world, BlockPos pos, Direction face) {
		return this.spreadsFire;
	}

	private static boolean isFlammable(BlockGetter world, BlockPos pos) {
		BlockState blockState = world.getBlockState(pos);
		return net.fabricmc.fabric.api.registry.FlammableBlockRegistry.getDefaultInstance().get(blockState.getBlock()) != null;
	}

	public boolean isFireSource(BlockState state, LevelReader world, BlockPos pos, Direction side) {
		return this.spreadsFire && this.flammability == 0;
	}

	@Override
	public boolean isRandomlyTicking(BlockState pState) {
		return this.burning || this.explodes;
	}

	@Override
	public void randomTick(BlockState state, ServerLevel world, BlockPos pos, RandomSource rand) {
		int x = pos.getX();
		int y = pos.getY();
		int z = pos.getZ();

		// Start fires if the fluid is lava-like
		if (this.burning) {
			int rangeUp = rand.nextInt(3);

			for (int i = 0; i < rangeUp; ++i) {
				x += rand.nextInt(3) - 1;
				++y;
				z += rand.nextInt(3) - 1;
				BlockState blockState = world.getBlockState(new BlockPos(x, y, z));
				if (blockState.isAir()) {
					if (isNeighborFlammable(world, x, y, z)) {
						world.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.FIRE.defaultBlockState());
						return;
					}
				} else if (blockState.blocksMotion()) {
					return;
				}
			}

			if (rangeUp == 0) {
				int startX = x;
				int startZ = z;

				for (int i = 0; i < 3; ++i) {
					x = startX + rand.nextInt(3) - 1;
					z = startZ + rand.nextInt(3) - 1;

					BlockPos posAbove = new BlockPos(pos.getX(), y + 1, z);
					if (world.isEmptyBlock(posAbove) && isFlammable(world, new BlockPos(x, y, z))) {
						world.setBlockAndUpdate(posAbove, Blocks.FIRE.defaultBlockState());
					}
				}
			}
		}

		// explode if very flammable and near fire
		if (this.explosionPower > 1f) {
			if (isNearFire(world, pos.getX(), pos.getY(), pos.getZ())) {
				world.setBlockAndUpdate(pos, Blocks.FIRE.defaultBlockState());
				world.explode(null, pos.getX(), pos.getY(), pos.getZ(), this.explosionPower, true, Level.ExplosionInteraction.BLOCK);
			}
		}
	}

	private static boolean isNeighborFlammable(Level world, int x, int y, int z) {
		return isFlammable(world, new BlockPos(x - 1, y, z)) ||
			isFlammable(world, new BlockPos(x + 1, y, z)) ||
			isFlammable(world, new BlockPos(x, y, z - 1)) ||
			isFlammable(world, new BlockPos(x, y, z + 1)) ||
			isFlammable(world, new BlockPos(x, y - 1, z)) ||
			isFlammable(world, new BlockPos(x, y + 1, z));
	}

	private static boolean isNearFire(Level world, int x, int y, int z) {
		return BlockPos.betweenClosedStream(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1).anyMatch((pos) -> {
			BlockState state = world.getBlockState(pos);
			return state.is(BlockTags.FIRE) || state.is(Blocks.LAVA);
		});
	}
}
