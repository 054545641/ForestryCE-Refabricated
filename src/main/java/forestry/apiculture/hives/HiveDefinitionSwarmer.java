package forestry.apiculture.hives;

import forestry.api.ForestryTags;
import forestry.api.apiculture.hives.IHiveDefinition;
import forestry.api.apiculture.hives.IHivePlacement;
import forestry.api.core.HumidityType;
import forestry.api.core.TemperatureType;
import forestry.apiculture.features.ApicultureBlocks;
import forestry.core.platform.tile.TileUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Arrays;
import java.util.List;

public class HiveDefinitionSwarmer implements IHiveDefinition {
	/** Generation chance applied by the alveary swarmer when it spawns a swarm hive. */
	public static final float SWARMER_GEN_CHANCE = 128.0f;

	private final List<ItemStack> bees;

	public HiveDefinitionSwarmer(ItemStack... bees) {
		this.bees = Arrays.asList(bees);
	}

	@Override
	public IHivePlacement getHiveGen() {
		return new GroundHivePlacement(ForestryTags.Blocks.SWARM_BEE_GROUND) {
			@Override
			public Heightmap.Types getHeightmapType() {
				// swarmers run after world generation, when the generation heightmap no longer updates
				return Heightmap.Types.WORLD_SURFACE;
			}
		};
	}

	@Override
	public BlockState getBlockState() {
		return ApicultureBlocks.HIVE.get(HiveBlockType.SWARM).defaultState();
	}

	@Override
	public boolean isGoodBiome(Holder<Biome> biome) {
		return true;
	}

	@Override
	public boolean isGoodHumidity(HumidityType humidity) {
		return true;
	}

	@Override
	public boolean isGoodTemperature(TemperatureType temperature) {
		return true;
	}

	@Override
	public void postGen(WorldGenLevel level, RandomSource rand, BlockPos pos) {
		TileUtil.actOnTile(level, pos, HiveBlockEntity.class, tile -> tile.setContained(this.bees));
	}
}
