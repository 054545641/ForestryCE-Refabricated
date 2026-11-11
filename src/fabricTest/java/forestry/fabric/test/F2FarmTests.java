package forestry.fabric.test;

import forestry.api.IForestryApi;
import forestry.api.agriculture.ForestryFarmTypes;
import forestry.api.core.fluids.FluidVolume;
import forestry.agriculture.minifarm.inventory.MinifarmInventory;
import forestry.agriculture.minifarm.tiles.AbstractMinifarmBlockEntity;
import forestry.agriculture.multifarm.multiblock.InventoryFarm;
import forestry.agriculture.multifarm.multiblock.FarmController;
import forestry.agriculture.multifarm.tiles.TileFarm;
import forestry.core.platform.multiblock.MultiblockTileEntityForestry;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.transfer.v1.fluid.*;
import net.fabricmc.fabric.api.transfer.v1.item.*;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import team.reborn.energy.api.EnergyStorage;
import static forestry.fabric.test.F1RegistrationTests.*;

public final class F2FarmTests {
    private static boolean enabled(GameTestHelper test) {
        if (FabricLoader.getInstance().isModLoaded("forestryfarms")) return true;
        require(!IForestryApi.INSTANCE.getFarmingManager().isLoaded(), "Absent farms module exposed a real manager");
        test.succeed(); return false;
    }

    @GameTest public void farmDomainAndFertilizerMapAreUsed(GameTestHelper test) {
        if (!enabled(test)) return;
        var manager = IForestryApi.INSTANCE.getFarmingManager();
        for (var type : java.util.List.of(ForestryFarmTypes.CROPS, ForestryFarmTypes.GOURD, ForestryFarmTypes.SHROOM,
                ForestryFarmTypes.INFERNAL, ForestryFarmTypes.POALES, ForestryFarmTypes.SUCCULENTES, ForestryFarmTypes.ENDER,
                ForestryFarmTypes.ARBOREAL, ForestryFarmTypes.PEAT, ForestryFarmTypes.ORCHARD, ForestryFarmTypes.COCOA))
            require(manager.getFarmType(type) != null, "Missing farm type " + type);
        var pos = new BlockPos(1, 1, 1);
        test.setBlock(pos, BuiltInRegistries.BLOCK.getValue(id("farm_crops_managed")));
        var tile = test.getBlockEntity(pos, AbstractMinifarmBlockEntity.class);
        tile.setManual(false);
        var fertilizer = new ItemStack(BuiltInRegistries.ITEM.getValue(id("fertilizer")), 2);
        require(manager.getFertilizeValue(fertilizer) == 500, "Fertilizer map not loaded");
        tile.setItem(MinifarmInventory.CONFIG.fertilizerStart, fertilizer);
        // Real farm work consumes the DataMap value into the persisted fertilizer buffer even when no crop is ready.
        tile.getEnergyManager().setEnergyStored(100);
        tile.processWorkStep(); tile.processWorkStep();
        test.runAfterDelay(2, () -> {
        require(tile.getItem(MinifarmInventory.CONFIG.fertilizerStart).getCount() == 1, "Farm did not consume its fertilizer");
        require(tile.saveWithoutMetadata(test.getLevel().registryAccess()).getIntOr("StoredFertilizer", 0) == 2000,
            "Farm lost the configured fertilizer multiplier");
        test.succeed();
        });
    }

    @GameTest public void planterLookupRollbackAndNativePersistence(GameTestHelper test) {
        if (!enabled(test)) return;
        var pos = new BlockPos(1, 1, 1);
        test.setBlock(pos, BuiltInRegistries.BLOCK.getValue(id("arboretum_managed")));
        var tile = test.getBlockEntity(pos, AbstractMinifarmBlockEntity.class); tile.setManual(false);
        var items = ItemStorage.SIDED.find(test.getLevel(), tile.getBlockPos(), Direction.UP);
        var fluids = FluidStorage.SIDED.find(test.getLevel(), tile.getBlockPos(), Direction.NORTH);
        var energy = EnergyStorage.SIDED.find(test.getLevel(), tile.getBlockPos(), Direction.DOWN);
        require(items != null && fluids != null && energy != null, "Planter lookup is incomplete");
        var fertilizer = ItemVariant.of(BuiltInRegistries.ITEM.getValue(id("fertilizer")));
        try (var outer = Transaction.openOuter()) {
            try (var nested = Transaction.openNested(outer)) {
                require(items.insert(fertilizer, 2, nested) == 2, "Fertilizer insertion failed");
                require(fluids.insert(FluidVariant.of(Fluids.WATER), FluidConstants.BUCKET + 1, nested) == FluidConstants.BUCKET + 1, "Water insertion rounded droplets");
                require(energy.insert(120, nested) == 120, "Energy insertion failed");
                nested.commit();
            }
        }
        require(tile.getItem(MinifarmInventory.CONFIG.fertilizerStart).isEmpty() && tile.getTankManager().getTank(0).isEmpty()
            && energy.getAmount() == 0, "Outer rollback failed across the actual machine stores");
        try (var transaction = Transaction.openOuter()) {
            items.insert(fertilizer, 2, transaction); fluids.insert(FluidVariant.of(Fluids.WATER), FluidConstants.BUCKET + 1, transaction);
            energy.insert(120, transaction); transaction.commit();
        }
        try (var transaction = Transaction.openOuter()) { tile.processWorkStep(); }
        require(tile.getWorkCounter() == 0 && energy.getAmount() == 120, "Aborted work charged energy or advanced progress");
        var data = tile.saveWithFullMetadata(test.getLevel().registryAccess());
        var copy = (AbstractMinifarmBlockEntity) BlockEntity.loadStatic(tile.getBlockPos(), tile.getBlockState(), data, test.getLevel().registryAccess());
        require(copy != null && copy.getEnergyManager().getAmount() == 120 && copy.getTankManager().getTank(0).getAmount() == FluidConstants.BUCKET + 1,
            "Native planter persistence lost resources");
        require(copy.getItem(MinifarmInventory.CONFIG.fertilizerStart).getCount() == 2, "Native planter inventory changed");
        test.succeed();
    }

    @GameTest(maxTicks = 150) public void farmPortsAndHolderSurviveBreakAndReassembly(GameTestHelper test) {
        if (!enabled(test)) return;
        var base = new BlockPos(1, 1, 1);
        for (int x = 0; x < 3; x++) for (int y = 0; y < 4; y++) for (int z = 0; z < 3; z++)
            test.setBlock(base.offset(x, y, z), BuiltInRegistries.BLOCK.getValue(id("stone_brick_farm_block")));
        var gear = base.offset(1, 0, 0); var hatch = base.offset(2, 0, 0); var valve = base.offset(0, 0, 1);
        test.setBlock(hatch, BuiltInRegistries.BLOCK.getValue(id("stone_brick_farm_hatch")));
        test.setBlock(valve, BuiltInRegistries.BLOCK.getValue(id("stone_brick_farm_valve")));
        test.setBlock(gear, BuiltInRegistries.BLOCK.getValue(id("stone_brick_farm_gearbox")));
        test.runAfterDelay(2, () -> {
            var holder = test.getBlockEntity(base, TileFarm.class);
            var controller = holder.getMultiblockLogic().getController();
            require(controller.isAssembled(), "Native placement callbacks did not assemble the farm");
            var items = ItemStorage.SIDED.find(test.getLevel(), test.absolutePos(hatch), Direction.DOWN);
            var fluid = FluidStorage.SIDED.find(test.getLevel(), test.absolutePos(valve), Direction.NORTH);
            var energy = EnergyStorage.SIDED.find(test.getLevel(), test.absolutePos(gear), Direction.SOUTH);
            require(items != null && fluid != null && energy != null, "Multifarm port lookup missing");
            controller.getInternalInventory().setItem(InventoryFarm.CONFIG.productionStart, new ItemStack(Items.WHEAT, 7));
            // Fabric may provide a zero-slot fallback for WorldlyContainer. Assert the observable policy.
            var plain = ItemStorage.SIDED.find(test.getLevel(), holder.getBlockPos(), Direction.DOWN);
            try (var transaction = Transaction.openOuter()) {
                require(plain == null || plain.extract(ItemVariant.of(Items.WHEAT), 1, transaction) == 0,
                    "Plain farm blocks exposed automation");
            }
            try (var transaction = Transaction.openOuter()) {
                require(items.extract(ItemVariant.of(Items.WHEAT), 2, transaction) == 2, "Hatch extraction failed");
            }
            require(controller.getInternalInventory().getItem(InventoryFarm.CONFIG.productionStart).getCount() == 7, "Aborted hatch extraction changed inventory");
            try (var transaction = Transaction.openOuter()) {
                fluid.insert(FluidVariant.of(Fluids.WATER), FluidConstants.BUCKET, transaction); energy.insert(100, transaction); transaction.commit();
            }
            require(holder.saveWithoutMetadata(test.getLevel().registryAccess()).contains(MultiblockTileEntityForestry.PAYLOAD_KEY), "Holder did not save shared data");
            test.setBlock(gear, Blocks.AIR);
            test.setBlock(gear, BuiltInRegistries.BLOCK.getValue(id("stone_brick_farm_gearbox")));
            var restored = holder.getMultiblockLogic().getController();
            require(restored.isAssembled(), "Farm did not reassemble after replacing gearbox");
            require(restored.getInternalInventory().getItem(InventoryFarm.CONFIG.productionStart).getCount() == 7, "Shared inventory was lost or duplicated across reassembly");
            require(restored.getTankManager().getTank(0).getAmount() == FluidConstants.BUCKET, "Shared tank was lost across reassembly");
            test.succeed();
        });
    }

    @GameTest(structure = "forestry:empty") public void wateredFarmPreservesWaterloggedBlocks(GameTestHelper test) {
        if (!enabled(test)) return;
        var farm = planter(test, new BlockPos(1, 2, 1), "farm_crops_managed");
        var resources = farm.getFarmInventory().getResourcesInventory();
        resources.setItem(0, new ItemStack(Items.DIRT, 2));
        farm.getTankManager().getTank(0).setFluid(FluidVolume.milliBuckets(Fluids.WATER, 1000));
        var wet = new BlockPos(5, 1, 5);
        var slab = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        test.setBlock(wet, slab);
        farm.getFarmLogic().cultivate(test.getLevel(), farm, test.absolutePos(wet), Direction.EAST, 1);
        require(test.getBlockState(wet).equals(slab), "Farm replaced a waterlogged slab");
        require(resources.getItem(0).getCount() == 2, "Farm consumed soil for an existing water source");
        require(farm.getTankManager().getTank(0).getAmount() == FluidConstants.BUCKET, "Farm replaced an existing water source");

        var dry = new BlockPos(9, 1, 5);
        test.setBlock(dry, Blocks.OAK_SLAB);
        farm.getFarmLogic().cultivate(test.getLevel(), farm, test.absolutePos(dry), Direction.EAST, 1);
        require(test.getBlockState(dry).is(Blocks.FARMLAND), "Farm stopped replacing dry obstructions with soil");
        require(resources.getItem(0).getCount() == 1, "Dry obstruction did not consume one soil resource");
        test.succeed();
    }

    @GameTest(structure = "forestry:empty") public void irrigationChecksTheTargetInsteadOfTheRowStart(GameTestHelper test) {
        if (!enabled(test)) return;
        var farm = planter(test, new BlockPos(1, 2, 1), "farm_crops_managed");
        var start = new BlockPos(5, 1, 5);
        var target = start.east();
        for (var direction : java.util.List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST)) {
            test.setBlock(target.relative(direction), Blocks.STONE);
        }
        test.setBlock(start, Blocks.FARMLAND);
        test.setBlock(target, Blocks.BEDROCK);
        farm.getTankManager().getTank(0).setFluid(FluidVolume.milliBuckets(Fluids.WATER, 1000));
        farm.getFarmLogic().cultivate(test.getLevel(), farm, test.absolutePos(start), Direction.EAST, 2);
        require(test.getBlockState(target).is(Blocks.BEDROCK), "Farm replaced an unbreakable irrigation target");
        require(farm.getTankManager().getTank(0).getAmount() == FluidConstants.BUCKET, "Rejected irrigation consumed water");

        test.setBlock(start, Blocks.BEDROCK);
        test.setBlock(target, Blocks.FARMLAND);
        farm.getFarmLogic().cultivate(test.getLevel(), farm, test.absolutePos(start), Direction.EAST, 2);
        require(test.getBlockState(start).is(Blocks.BEDROCK), "Farm replaced the unbreakable row start");
        require(test.getBlockState(target).is(Blocks.WATER), "Unbreakable row start prevented irrigation of a later valid target");
        require(farm.getTankManager().getTank(0).isEmpty(), "Successful irrigation did not consume one bucket");
        test.succeed();
    }

    @GameTest(structure = "forestry:empty", maxTicks = 100) public void soilPlacementStopsAtTheActualPlatformBlock(GameTestHelper test) {
        if (!enabled(test)) return;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            test.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
        }
        var barrier = new BlockPos(4, 1, 3);
        test.setBlock(barrier, Blocks.STONE_BRICKS);
        var members = MultiblockTestSupport.buildFarm(test, new BlockPos(6, 1, 6));
        test.runAfterDelay(2, () -> {
            var farm = (FarmController) MultiblockTestSupport.controllerAt(test.getLevel(), members.getFirst());
            require(farm != null && farm.isAssembled(), "Farm fixture did not assemble");
            farm.getFarmInventory().getResourcesInventory().setItem(0, new ItemStack(Items.MYCELIUM, 16));
            var logic = IForestryApi.INSTANCE.getFarmingManager().getFarmType(ForestryFarmTypes.SHROOM).getLogic(false);
            var start = test.absolutePos(new BlockPos(8, 1, 3));
            for (int pass = 0; pass < 9; pass++) logic.cultivate(test.getLevel(), farm, start, Direction.WEST, 9);
            for (int x = 5; x <= 8; x++) {
                require(test.getBlockState(new BlockPos(x, 1, 3)).is(Blocks.MYCELIUM), "Farm did not plant the row before its platform boundary");
            }
            require(test.getBlockState(barrier).is(Blocks.STONE_BRICKS), "Farm replaced a platform block later in the row");
            require(test.getBlockState(barrier.west()).isAir(), "Farm planted beyond the platform boundary");
            require(farm.getFarmInventory().getResourcesInventory().getItem(0).getCount() == 12, "Farm consumed soil outside the valid row");
            test.succeed();
        });
    }

    @GameTest(structure = "forestry:empty", maxTicks = 100) public void gourdFarmPlantsFruitSupportsInEveryDirection(GameTestHelper test) {
        if (!enabled(test)) return;
        var farm = planter(test, new BlockPos(8, 2, 8), "farm_gourd_managed");
        for (var direction : java.util.List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            for (int step = 2; step < 6; step++) {
                test.getLevel().setBlockAndUpdate(farm.getBlockPos().above(2).relative(direction, step), Blocks.GLOWSTONE.defaultBlockState());
            }
        }
        // light propagation must finish before random ticks can grow fruit
        test.runAfterDelay(5, () -> checkGourdSupports(test, farm));
    }

    private static void checkGourdSupports(GameTestHelper test, AbstractMinifarmBlockEntity farm) {
        var resources = farm.getFarmInventory().getResourcesInventory();
        var seeds = farm.getFarmInventory().getGermlingsInventory();
        var level = test.getLevel();
        for (var seed : java.util.List.of(Items.PUMPKIN_SEEDS, Items.MELON_SEEDS)) {
            var stem = seed == Items.PUMPKIN_SEEDS ? Blocks.PUMPKIN_STEM : Blocks.MELON_STEM;
            var fruit = seed == Items.PUMPKIN_SEEDS ? Blocks.PUMPKIN : Blocks.MELON;
            resources.setItem(0, new ItemStack(Items.DIRT, 32));
            for (int slot = 0; slot < seeds.getContainerSize(); slot++) seeds.setItem(slot, new ItemStack(seed, 8));
            for (var direction : java.util.List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
                var start = farm.getBlockPos().below().relative(direction, 2);
                for (int step = 0; step < 4; step++) {
                    var pos = start.relative(direction, step);
                    level.setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                }
                for (int pass = 0; pass < 8; pass++) farm.getFarmLogic().cultivate(level, farm, start, direction, 4);
                int tilled = 0;
                int supports = 0;
                BlockPos plantedStem = null;
                for (int step = 0; step < 4; step++) {
                    var pos = start.relative(direction, step);
                    var soil = level.getBlockState(pos);
                    if (soil.is(Blocks.FARMLAND)) {
                        tilled++;
                        require(level.getBlockState(pos.above()).is(stem), "Gourd stem was not planted on farmland");
                        plantedStem = pos.above();
                    } else {
                        require(soil.is(Blocks.DIRT), "Gourd farm left a gap where fruit needs support");
                        require(level.getBlockState(pos.above()).isAir(), "Gourd farm planted a stem on a fruit support");
                        supports++;
                    }
                }
                require(tilled == 2 && supports == 2, "Gourd farm did not alternate farmland and fruit supports");
                var mature = stem.defaultBlockState().setValue(StemBlock.AGE, 7);
                level.setBlockAndUpdate(plantedStem, mature);
                var random = RandomSource.create(341);
                for (int attempt = 0; attempt < 256 && level.getBlockState(plantedStem).is(stem); attempt++) {
                    mature.randomTick(level, plantedStem, random);
                }
                require(!level.getBlockState(plantedStem).is(stem), "Mature stem could not grow fruit on the farm's supports");
                var crops = farm.getFarmLogic().harvest(level, farm, direction, 4, start);
                require(!crops.isEmpty(), "Gourd farm did not find its fruit");
                int drops = 0;
                for (var crop : crops) {
                    require(level.getBlockState(crop.getPosition()).is(fruit), "Gourd farm harvested a non-fruit block");
                    var harvested = crop.harvest();
                    if (harvested != null) drops += harvested.stream().mapToInt(ItemStack::getCount).sum();
                }
                require(drops > 0, "Gourd harvest produced no items");
                for (int pass = 0; pass < 4; pass++) farm.getFarmLogic().cultivate(level, farm, start, direction, 4);
            }
            require(resources.getItem(0).getCount() == 16, "Gourd farm repeatedly replaced its fruit supports");
            for (int slot = 0; slot < seeds.getContainerSize(); slot++) {
                require(seeds.getItem(slot).getCount() == 6, "Gourd farm consumed seeds outside the two stem positions");
            }
        }
        test.succeed();
    }

    @GameTest(structure = "forestry:empty") public void gourdFarmPreservesNativeFruitSupportSoils(GameTestHelper test) {
        if (!enabled(test)) return;
        var farm = planter(test, new BlockPos(8, 2, 8), "farm_gourd_managed");
        var level = test.getLevel();
        var resources = farm.getFarmInventory().getResourcesInventory();
        resources.setItem(0, new ItemStack(Items.DIRT, 32));
        var start = farm.getBlockPos().below().east(2);
        for (int pass = 0; pass < 2; pass++) farm.getFarmLogic().cultivate(level, farm, start, Direction.EAST, 2);
        var support = level.getBlockState(start).is(Blocks.DIRT) ? start : start.east();
        var stem = support.equals(start) ? start.east() : start;
        require(level.getBlockState(support).is(Blocks.DIRT) && level.getBlockState(stem).is(Blocks.FARMLAND), "Gourd soil fixture is incomplete");
        for (var block : java.util.List.of(Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM,
                Blocks.MOSS_BLOCK, Blocks.PALE_MOSS_BLOCK, Blocks.MUD, Blocks.MUDDY_MANGROVE_ROOTS)) {
            level.setBlockAndUpdate(support, block.defaultBlockState());
            farm.getFarmLogic().cultivate(level, farm, start, Direction.EAST, 2);
            require(level.getBlockState(support).is(block), "Gourd farm replaced a valid native fruit support: " + block);
            require(resources.getItem(0).getCount() == 30, "Gourd farm spent dirt replacing a valid fruit support");
        }
        level.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
        farm.getFarmLogic().cultivate(level, farm, start, Direction.EAST, 2);
        require(level.getBlockState(support).is(Blocks.DIRT), "Gourd farm accepted stone as fruit support");
        require(resources.getItem(0).getCount() == 29, "Replacing invalid fruit support did not consume one dirt");
        level.setBlockAndUpdate(stem, Blocks.GRASS_BLOCK.defaultBlockState());
        farm.getFarmLogic().cultivate(level, farm, start, Direction.EAST, 2);
        require(level.getBlockState(stem).is(Blocks.FARMLAND), "Gourd farm accepted grass instead of tilling a stem position");
        require(resources.getItem(0).getCount() == 28, "Tilling the stem position did not consume one dirt");
        test.succeed();
    }

    @GameTest(structure = "forestry:empty") public void mushroomFarmGrowsHarvestsAndReplantsBothMushrooms(GameTestHelper test) {
        if (!enabled(test)) return;
        var farm = planter(test, new BlockPos(1, 2, 1), "farm_mushroom_managed");
        var level = test.getLevel();
        var soil = test.absolutePos(new BlockPos(8, 1, 8));
        var mushroomPos = soil.above();
        var seeds = farm.getFarmInventory().getGermlingsInventory();
        for (Block ground : java.util.List.of(Blocks.MYCELIUM, Blocks.PODZOL)) {
            for (Block mushroom : java.util.List.of(Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM)) {
                level.setBlockAndUpdate(mushroomPos, Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(soil, ground.defaultBlockState());
                for (int slot = 0; slot < seeds.getContainerSize(); slot++) seeds.setItem(slot, new ItemStack(mushroom, 2));
                var logic = farm.getFarmLogic();
                require(logic.cultivate(level, farm, soil, Direction.EAST, 1), "Mushroom farm did not plant from its inventory");
                require(level.getBlockState(mushroomPos).is(mushroom), "Mushroom farm planted the wrong mushroom");
                level.getRandom().setSeed(535);
                for (int attempt = 0; attempt < 20_000 && !level.getBlockState(mushroomPos).is(Blocks.MUSHROOM_STEM); attempt++) {
                    logic.cultivate(level, farm, soil, Direction.EAST, 1);
                }
                require(level.getBlockState(mushroomPos).is(Blocks.MUSHROOM_STEM), "Mushroom farm never grew a huge mushroom");
                farm.cleanExtents(Direction.EAST);
                var crops = logic.harvest(level, farm, Direction.EAST, 1, soil);
                require(crops.size() > 1, "Mushroom farm did not discover the connected stem and cap");
                int drops = 0;
                for (var crop : crops) {
                    var harvested = crop.harvest();
                    if (harvested != null) drops += harvested.stream().mapToInt(ItemStack::getCount).sum();
                }
                require(drops > 0 && level.isEmptyBlock(mushroomPos), "Mushroom harvest did not clear the stem and produce items");
                require(logic.cultivate(level, farm, soil, Direction.EAST, 1), "Mushroom farm did not replant after harvest");
                require(level.getBlockState(mushroomPos).is(mushroom), "Mushroom farm failed to restore its planted crop");
                require(seeds.getItem(3).isEmpty(), "Mushroom planting and replanting did not each consume a seed");
            }
        }
        test.succeed();
    }

    private static AbstractMinifarmBlockEntity planter(GameTestHelper test, BlockPos pos, String block) {
        test.setBlock(pos, BuiltInRegistries.BLOCK.getValue(id(block)));
        var farm = test.getBlockEntity(pos, AbstractMinifarmBlockEntity.class);
        farm.setManual(false);
        return farm;
    }
}
