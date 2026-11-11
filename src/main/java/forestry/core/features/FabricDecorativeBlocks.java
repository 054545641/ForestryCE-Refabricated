package forestry.core.features;

import forestry.core.content.decorative.*;
import forestry.core.content.lighting.BlockWaterloggableTorch;
import forestry.core.content.lighting.BlockWaterloggableWallTorch;
import forestry.core.platform.item.ItemBlockForestry;
import forestry.core.platform.registration.FeatureRegistry;
import forestry.core.platform.registration.IItemFeature;
import net.minecraft.core.Direction;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import java.util.ArrayList;
import java.util.List;

public final class FabricDecorativeBlocks {
    public static List<IItemFeature<?>> register(FeatureRegistry registry) {
        var features = new ArrayList<IItemFeature<?>>();
        features.add(registry.block(Block::new, () -> Properties.ofFullCopy(Blocks.GRASS_BLOCK), ItemBlockForestry::new, "turf_block"));
        features.add(registry.block(CarpetBlock::new, () -> Properties.ofFullCopy(Blocks.GRASS_BLOCK), ItemBlockForestry::new, "turf"));
        features.add(registry.block(BlockSheet::new, () -> Properties.ofFullCopy(Blocks.OAK_PLANKS).pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY), ItemBlockForestry::new, "plywood"));
        features.add(registry.block(BlockPlywoodBlock::new, () -> Properties.ofFullCopy(Blocks.OAK_PLANKS).ignitedByLava(), ItemBlockForestry::new, "plywood_block"));
        features.add(registry.block(CorkBlock::new, () -> Properties.ofFullCopy(Blocks.OAK_PLANKS).sound(SoundType.CHERRY_WOOD).strength(2, 3).ignitedByLava(), ItemBlockForestry::new, "cork"));
        features.add(registry.block(ChainBlock::new, () -> Properties.ofFullCopy(Blocks.IRON_CHAIN), ItemBlockForestry::new, "tin_chain"));
        features.add(registry.block(LanternBlock::new, () -> Properties.ofFullCopy(Blocks.SOUL_LANTERN).lightLevel(state -> 13), ItemBlockForestry::new, "phosphor_lantern"));
        var torch = registry.block(BlockWaterloggableTorch::new, () -> Properties.ofFullCopy(Blocks.SOUL_TORCH).lightLevel(state -> 13), null, "phosphor_torch");
        var wallTorch = registry.block(BlockWaterloggableWallTorch::new, () -> Properties.ofFullCopy(Blocks.SOUL_TORCH).lightLevel(state -> 13), null, "phosphor_wall_torch");
        features.add(registry.item(properties -> new StandingAndWallBlockItem(torch.block(), wallTorch.block(), Direction.DOWN, properties), "phosphor_torch"));
        for (var type : BlockTypeMetalPlating.values()) features.add(registry.block(Block::new,
            () -> Properties.ofFullCopy(Blocks.COPPER_BLOCK.waxed().unaffected()).mapColor(type.getMapColor()), ItemBlockForestry::new, "metal_plating_" + type.getSerializedName()));
        for (var type : BlockTypeBigCandle.values()) features.add(registry.block(properties -> new BlockBigCandle(properties, type),
            () -> Properties.ofFullCopy(Blocks.CANDLE).lightLevel(state -> state.getValue(BlockBigCandle.LIT) ? type == BlockTypeBigCandle.REFRACTORY ? 10 : 15 : 0),
            ItemBlockForestry::new, "big_candle_" + type.getSerializedName()));
        for (var type : BlockTypeJumboCandle.values()) features.add(registry.block(properties -> new BlockJumboCandle(properties, type),
            () -> Properties.ofFullCopy(Blocks.CANDLE).mapColor(type.getMapColor()).lightLevel(state -> state.getValue(BlockJumboCandle.LIT) ? type == BlockTypeJumboCandle.REFRACTORY ? 10 : 15 : 0),
            ItemBlockForestry::new, "jumbo_candle_" + type.getSerializedName()));
        features.add(registry.block(CandleBlock::new, () -> Properties.ofFullCopy(Blocks.DYED_CANDLE.magenta()), ItemBlockForestry::new, "rainbow_candle"));
        features.add(registry.block(CandleRefractory::new, () -> Properties.ofFullCopy(Blocks.DYED_CANDLE.red())
            .lightLevel(state -> state.getValue(CandleBlock.LIT) ? state.getValue(CandleBlock.CANDLES) * 2 : 0), ItemBlockForestry::new, "refractory_candle"));
        registry.addRegistryListener(net.minecraft.core.registries.Registries.BLOCK, () -> {
            var flammables = net.fabricmc.fabric.api.registry.FlammableBlockRegistry.getDefaultInstance();
            for (var block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
                if (block instanceof forestry.core.platform.block.ForestryCombustible) flammables.add(block, 5, 20);
            }
        });
        return features;
    }
}
