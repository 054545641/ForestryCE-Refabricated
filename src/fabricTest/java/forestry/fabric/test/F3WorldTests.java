package forestry.fabric.test;

import forestry.apiculture.worldgen.ApiaristPoolElement;
import forestry.apiculture.worldgen.VillagerJigsaw;
import forestry.arboriculture.entities.ForestryChestBoat;
import forestry.arboriculture.features.ArboricultureItems;
import forestry.arboriculture.wood.ForestryWoodType;
import forestry.core.content.decorative.BlockSheet;
import forestry.core.content.machines.features.FactoryBlocks;
import forestry.core.content.machines.blocks.BlockTypeFactoryPlain;
import forestry.core.content.machines.gui.ContainerCarpenter;
import forestry.core.content.machines.network.packets.PacketRecipeTransferRequest;
import forestry.core.content.machines.tiles.TileCarpenter;
import forestry.core.platform.config.ForestryConfig;
import forestry.core.platform.fluids.ForestryFluidMotion;
import forestry.core.platform.fluids.ForestryFluids;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import static forestry.fabric.test.F1RegistrationTests.*;

public final class F3WorldTests {
    @GameTest
    public void creativeTabsContainAllGeneticVariants(GameTestHelper test) {
        var parameters = new net.minecraft.world.item.CreativeModeTab.ItemDisplayParameters(test.getLevel().enabledFeatures(), true, test.getLevel().registryAccess());
        for (var type : java.util.stream.Stream.of(forestry.core.platform.util.SpeciesUtil.BEE_TYPE.get(), forestry.core.platform.util.SpeciesUtil.TREE_TYPE.get(), forestry.core.platform.util.SpeciesUtil.getButterflyTypeSafe()).filter(java.util.Objects::nonNull).toList()) {
            String tabName = type.id().equals(forestry.api.core.genetics.ForestrySpeciesTypes.BEE) ? "apiculture"
                : type.id().equals(forestry.api.core.genetics.ForestrySpeciesTypes.TREE) ? "arboriculture" : "lepidopterology";
            var tab = BuiltInRegistries.CREATIVE_MODE_TAB.getValue(id(tabName));
            tab.buildContents(parameters);
            verifyGeneticVariants(tab.getDisplayItems(), type, tabName);
        }
        test.succeed();
    }

    public static void verifyGeneticVariants(java.util.Collection<ItemStack> stacks, forestry.api.core.genetics.ISpeciesType<?, ?> type, String source) {
        for (var stage : type.getLifeStages()) {
            verifyGeneticStageVariants(stacks, type, stage, source);
        }
    }

    static void verifyGeneticStageVariants(java.util.Collection<ItemStack> stacks, forestry.api.core.genetics.ISpeciesType<?, ?> type,
                                          forestry.api.core.genetics.ILifeStage stage, String source) {
        var variants = stacks.stream().filter(stack -> stack.is(stage.getItemForm())).toList();
        require(variants.size() == type.getSpeciesCount(), source + " has " + variants.size() + " " + stage.getSerializedName() + " variants, expected " + type.getSpeciesCount());
        var species = new java.util.HashSet<Identifier>();
        for (var stack : variants) {
            require(forestry.api.core.genetics.capability.IIndividualHandlerItem.hasIndividual(stack), source + " contains a specimen without a genome");
            var individual = forestry.api.core.genetics.capability.IIndividualHandlerItem.getIndividual(stack);
            require(individual != null && species.add(individual.getSpecies().id()), source + " contains duplicate or invalid species");
            require(individual.getGenome().getAllelePairs().equals(type.getSpecies(individual.getSpecies().id()).getDefaultGenome().getAllelePairs()), source + " contains an outdated genome");
        }
        require(species.equals(type.getAllSpeciesIds()), source + " omitted species for " + stage.getSerializedName());
    }

    @GameTest
    public void biomeClimateUsesTagsAndNativeValues(GameTestHelper test) {
        var biomes = test.getLevel().registryAccess().lookupOrThrow(Registries.BIOME);
        var climates = forestry.api.IForestryApi.INSTANCE.getClimateManager();
        var expected = java.util.Map.of(
            net.minecraft.world.level.biome.Biomes.FOREST, new forestry.api.core.climate.ClimateState(forestry.api.core.TemperatureType.NORMAL, forestry.api.core.HumidityType.NORMAL),
            net.minecraft.world.level.biome.Biomes.JUNGLE, new forestry.api.core.climate.ClimateState(forestry.api.core.TemperatureType.WARM, forestry.api.core.HumidityType.DAMP),
            net.minecraft.world.level.biome.Biomes.BAMBOO_JUNGLE, new forestry.api.core.climate.ClimateState(forestry.api.core.TemperatureType.WARM, forestry.api.core.HumidityType.DAMP),
            net.minecraft.world.level.biome.Biomes.DESERT, new forestry.api.core.climate.ClimateState(forestry.api.core.TemperatureType.HOT, forestry.api.core.HumidityType.ARID),
            net.minecraft.world.level.biome.Biomes.NETHER_WASTES, new forestry.api.core.climate.ClimateState(forestry.api.core.TemperatureType.HELLISH, forestry.api.core.HumidityType.ARID),
            net.minecraft.world.level.biome.Biomes.LUSH_CAVES, new forestry.api.core.climate.ClimateState(forestry.api.core.TemperatureType.WARM, forestry.api.core.HumidityType.DAMP));
        expected.forEach((key, state) -> {
            var biome = biomes.getOrThrow(key);
            require(climates.getTemperature(biome) == state.temperature() && climates.getHumidity(biome) == state.humidity(), "Incorrect biome climate: " + key.identifier());
            require(climates.getTemperature(key) == state.temperature() && climates.getHumidity(key) == state.humidity(), "Incorrect keyed climate: " + key.identifier());
        });
        test.succeed();
    }

    @GameTest
    public void jungleHivesAreTropicalAndForestHivesStayTemperate(GameTestHelper test) {
        var level = test.getLevel();
        var pos = test.absolutePos(new BlockPos(3, 3, 3));
        var hives = forestry.api.IForestryApi.INSTANCE.getHiveManager().getHives();
        var forest = hives.stream().filter(hive -> hive.getDefinition() == forestry.apiculture.hives.HiveDefinition.FOREST).findFirst().orElseThrow();
        var tropical = hives.stream().filter(hive -> hive.getDefinition() == forestry.apiculture.hives.HiveDefinition.JUNGLE).findFirst().orElseThrow();
        var roof = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, pos).below();
        require(level.getBlockState(roof).is(Blocks.BARRIER), "Missing test enclosure roof");
        level.setBlockAndUpdate(roof, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(pos.above(), Blocks.JUNGLE_LEAVES.defaultBlockState());
        level.setBlockAndUpdate(pos.above(2), Blocks.JUNGLE_LEAVES.defaultBlockState());
        level.setBlockAndUpdate(pos.below(), Blocks.AIR.defaultBlockState());
        for (var biome : List.of(net.minecraft.world.level.biome.Biomes.JUNGLE, net.minecraft.world.level.biome.Biomes.BAMBOO_JUNGLE, net.minecraft.world.level.biome.Biomes.FOREST)) {
            test.setBiome(biome);
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            require(level.getBiome(pos).is(biome), "Hive fixture has the wrong biome");
            require(pos.equals(forest.getPosForHive(level, RandomSource.create(1), pos.getX(), pos.getZ())), "Tree hive fixture did not resolve its position");
            boolean jungle = biome != net.minecraft.world.level.biome.Biomes.FOREST;
            var rejected = jungle ? forest : tropical;
            var accepted = jungle ? tropical : forest;
            require(!forestry.apiculture.hives.HiveDecorator.tryGenHive(level, RandomSource.create(1), pos.getX(), pos.getZ(), rejected), "Wrong hive accepted in " + biome.identifier());
            require(forestry.apiculture.hives.HiveDecorator.tryGenHive(level, RandomSource.create(1), pos.getX(), pos.getZ(), accepted), "Expected hive did not generate in " + biome.identifier());
            require(level.getBlockState(pos).is(accepted.getHiveBlockState().getBlock()), "Generated the wrong hive block");
            var tile = (forestry.apiculture.hives.HiveBlockEntity) level.getBlockEntity(pos);
            require(tile.getContainedBee().getSpecies().id().equals(jungle ? forestry.api.apiculture.ForestryBeeSpecies.TROPICAL : forestry.api.apiculture.ForestryBeeSpecies.FOREST), "Generated hive contains the wrong bee species");
        }
        test.succeed();
    }

    @GameTest
    public void optionalJadeStatusContainsNoInventory(GameTestHelper test) {
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("jade")) {
            var pos = test.absolutePos(new BlockPos(2, 2, 2));
            test.getLevel().setBlockAndUpdate(pos, FactoryBlocks.PLAIN.get(BlockTypeFactoryPlain.CARPENTER).defaultState());
            var tile = (TileCarpenter) test.getLevel().getBlockEntity(pos);
            tile.getErrorLogic().setCondition(true, forestry.api.core.ForestryError.NO_POWER);
            tile.setItem(0, new ItemStack(Items.DIAMOND, 4));
            var data = new net.minecraft.nbt.CompoundTag();
            forestry.core.platform.compat.jade.ForestryJadeData.append(data, tile);
            var status = data.getCompound("forestry:status").orElseThrow();
            require(status.keySet().equals(java.util.Set.of("errors")) && status.getList("errors").orElseThrow().size() == 1,
                "Jade status leaked inventory or omitted machine errors");
        }
        test.succeed();
    }

    @GameTest
    public void replacedTileRejectsAStalePacketWithAnotherSchema(GameTestHelper test) {
        var pos = test.absolutePos(new BlockPos(2, 2, 2));
        var packet = new forestry.core.platform.network.packets.PacketTileStream(pos, id("leaves"), new byte[] {127});
        test.getLevel().setBlockAndUpdate(pos, FactoryBlocks.PLAIN.get(BlockTypeFactoryPlain.CARPENTER).defaultState());
        forestry.core.platform.network.packets.PacketTileStream.handle(packet, FakePlayer.get(test.getLevel()));
        require(test.getLevel().getBlockEntity(pos) instanceof TileCarpenter, "Stale stream replaced or decoded into another block entity");
        test.succeed();
    }

    @GameTest
    public void villagePoolsProfessionsTradesAndWorldgenAreLive(GameTestHelper test) {
        var access = test.getLevel().registryAccess();
        for (String name : List.of("ore_apatite", "ore_tin", "hive", "tree")) {
            require(access.lookupOrThrow(Registries.PLACED_FEATURE).get(id(name)).isPresent(), "Missing placed feature " + name);
        }
        var pools = access.lookupOrThrow(Registries.TEMPLATE_POOL);
        var processors = access.lookupOrThrow(Registries.PROCESSOR_LIST);
        VillagerJigsaw.init(pools, processors);
        VillagerJigsaw.init(pools, processors);
        for (String biome : List.of("plains", "snowy", "savanna", "desert", "taiga")) {
            var pool = pools.getValue(Identifier.withDefaultNamespace("village/" + biome + "/houses"));
            var additions = pool.getTemplates().stream().filter(entry -> entry.getFirst() instanceof ApiaristPoolElement).toList();
            require(additions.size() == 1 && additions.getFirst().getSecond() == 15, "Village weight or insertion idempotence changed: " + biome);
            require(test.getLevel().getStructureManager().get(id("village/apiarist_house_" + biome + "_1")).isPresent(), "Missing village template");
        }
        for (String profession : List.of("beekeeper", "arborist")) {
            require(BuiltInRegistries.VILLAGER_PROFESSION.containsKey(id(profession)), "Missing profession");
            for (int tier = 1; tier <= 4; tier++) {
                require(access.lookupOrThrow(Registries.TRADE_SET).get(id(profession + "/level_" + tier)).isPresent(), "Missing native trade set");
            }
            var gift = ResourceKey.create(Registries.LOOT_TABLE, id("gameplay/hero_of_the_village/" + profession + "_gift"));
            require(test.getLevel().getServer().reloadableRegistries().getLootTable(gift) != net.minecraft.world.level.storage.loot.LootTable.EMPTY, "Missing hero gift loot");
        }
        require(PoiTypes.forState(BuiltInRegistries.BLOCK.getValue(id("escritoire")).defaultBlockState()).isPresent(), "Escritoire POI states were not indexed");
        var villager = EntityTypes.VILLAGER.create(test.getLevel(), net.minecraft.world.entity.EntitySpawnReason.EVENT);
        villager.setVillagerData(villager.getVillagerData().withProfession(access, forestry.apiculture.apiarist.villagers.ApicultureVillagers.PROF_BEEKEEPER.getKey()));
        villager.setPos(Vec3.atCenterOf(test.absolutePos(new BlockPos(2, 2, 2))));
        test.getLevel().addFreshEntity(villager);
        require(villager.getGoalSelector().getAvailableGoals().stream().filter(goal -> goal.getGoal() instanceof forestry.apiculture.apiarist.ApiaristAI).count() == 1,
            "Beekeeper did not regain its original bee-house maintenance goal");
        villager.discard();
        test.succeed();
    }

    @GameTest
    public void oreSwitchesGateActualPlacement(GameTestHelper test) {
        var level = test.getLevel();
        var center = test.absolutePos(new BlockPos(3, 3, 3));
        var configured = level.registryAccess().lookupOrThrow(Registries.CONFIGURED_FEATURE).getValue(id("ore_apatite"));
        boolean previous = ForestryConfig.SERVER.spawnApatiteOre.get();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-3, -3, -3), center.offset(3, 3, 3))) level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
        try {
            ForestryConfig.SERVER.spawnApatiteOre.set(false);
            require(!configured.place(level, level.getChunkSource().getGenerator(), RandomSource.create(7), center), "Disabled apatite feature placed ore");
            require(level.getBlockState(center).is(Blocks.STONE), "Disabled ore modified the world");
            ForestryConfig.SERVER.spawnApatiteOre.set(true);
            require(configured.place(level, level.getChunkSource().getGenerator(), RandomSource.create(7), center), "Enabled native ore feature did not place");
        } finally {
            ForestryConfig.SERVER.spawnApatiteOre.set(previous);
        }
        test.succeed();
    }

    @GameTest
    public void grafterHarvestUsesLastDurabilityWithoutLosingSapling(GameTestHelper test) {
        var player = FakePlayer.get(test.getLevel());
        var tool = ArboricultureItems.GRAFTER.stack();
        tool.setDamageValue(tool.getMaxDamage() - 1);
        var pos = test.absolutePos(new BlockPos(2, 2, 2));
        var state = Blocks.OAK_LEAVES.defaultBlockState();
        test.getLevel().setBlockAndUpdate(pos, state);
        var drops = Block.getDrops(state, test.getLevel(), pos, null, player, tool);
        require(drops.stream().anyMatch(stack -> stack.is(ItemTags.SAPLINGS)), "A broken grafter lost its guaranteed sapling");
        require(tool.isEmpty(), "Grafter loot did not apply the original extra durability cost");
        test.succeed();
    }

    @GameTest
    public void decorativeWaterloggingFireFacesAndPistons(GameTestHelper test) {
        var pos = test.absolutePos(new BlockPos(2, 2, 2));
        var sheet = (BlockSheet) BuiltInRegistries.BLOCK.getValue(id("plywood"));
        var state = sheet.defaultBlockState();
        for (Direction face : Direction.values()) require(sheet.isFlammable(state, test.getLevel(), pos, face) == state.isFaceSturdy(test.getLevel(), pos, face), "Plywood burns on an unfilled face");
        require(state.getPistonPushReaction() == PushReaction.DESTROY, "Plywood piston reaction changed");
        var torch = BuiltInRegistries.BLOCK.getValue(id("phosphor_torch")).defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        require(torch.getFluidState().is(FluidTags.WATER) && torch.getLightEmission() > 0, "Underwater phosphor torch lost water or light");
        test.succeed();
    }

    @GameTest
    public void decorativeBlocksBurnAtPlankRateAndCanBurnAway(GameTestHelper test) {
        var level = test.getLevel();
        test.setBiome(net.minecraft.world.level.biome.Biomes.PLAINS);
        var firePos = test.absolutePos(new BlockPos(3, 3, 3));
        int previous = level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER);
        level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, -1, level.getServer());
        try {
            for (String name : List.of("cork", "plywood_block", "plywood")) {
                var block = BuiltInRegistries.BLOCK.getValue(id(name));
                var fuel = block.defaultBlockState();
                if (block instanceof BlockSheet) fuel = fuel.setValue(BlockSheet.FACING, Direction.EAST);
                var registered = net.fabricmc.fabric.api.registry.FlammableBlockRegistry.getDefaultInstance().get(block);
                require(registered != null && registered.getIgniteOdds() == 5 && registered.getBurnOdds() == 20,
                    "Incorrect plank fire rates for " + name);
                for (int roll : List.of(19, 20)) {
                    prepareFireFixture(level, firePos);
                    level.setBlockAndUpdate(firePos.east(), fuel);
                    level.getBlockState(firePos).tick(level, firePos, burnRandom(roll));
                    require(roll == 19 ? level.isEmptyBlock(firePos.east()) : level.getBlockState(firePos.east()).is(block),
                        "Fire did not respect the plank burn threshold or burn away " + name + ": " + roll);
                }
            }
            prepareFireFixture(level, firePos);
            level.setBlockAndUpdate(firePos.east(), forestry.apiculture.features.ApicultureBlocks.WAX_BLOCK.defaultState());
            level.getBlockState(firePos).tick(level, firePos, burnRandom(19));
            require(level.getFluidState(firePos.east()).is(ForestryFluids.WAX.getFluid()), "Burning wax stopped melting into wax fluid");
            prepareFireFixture(level, firePos);
            level.setBlockAndUpdate(firePos.east(), forestry.apiculture.features.ApicultureBlocks.REFRACTORY_WAX_BLOCK.defaultState());
            level.getBlockState(firePos).tick(level, firePos, burnRandom(0));
            require(level.getBlockState(firePos.east()).is(forestry.apiculture.features.ApicultureBlocks.REFRACTORY_WAX_BLOCK.block()),
                "Refractory wax became combustible");
        } finally {
            clearFireFixture(level, firePos);
            level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, previous, level.getServer());
        }
        test.succeed();
    }

    @GameTest
    public void plywoodSheetBurnsOnlyOnItsFilledFace(GameTestHelper test) {
        var level = test.getLevel();
        test.setBiome(net.minecraft.world.level.biome.Biomes.PLAINS);
        var firePos = test.absolutePos(new BlockPos(3, 3, 3));
        var sheet = BuiltInRegistries.BLOCK.getValue(id("plywood"));
        int previous = level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER);
        level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, -1, level.getServer());
        try {
            for (Direction side : Direction.values()) {
                for (boolean filled : List.of(true, false)) {
                    prepareFireFixture(level, firePos);
                    var target = firePos.relative(side);
                    var state = sheet.defaultBlockState().setValue(BlockSheet.FACING, filled ? side : side.getOpposite());
                    level.setBlockAndUpdate(target, state);
                    level.getBlockState(firePos).tick(level, firePos, burnRandom(0));
                    require(filled ? level.isEmptyBlock(target) : level.getBlockState(target).is(sheet),
                        "Plywood fire ignored its filled face: " + side + ", filled=" + filled);
                }
            }
        } finally {
            clearFireFixture(level, firePos);
            level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, previous, level.getServer());
        }
        test.succeed();
    }

    @GameTest
    public void rainExtinguishesFireAboveDecorativeWood(GameTestHelper test) {
        var level = test.getLevel();
        var firePos = test.absolutePos(new BlockPos(3, 3, 3));
        var roof = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, firePos).below();
        var roofState = level.getBlockState(roof);
        float previousRain = level.getRainLevel(1);
        int previous = level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER);
        level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, -1, level.getServer());
        test.setBiome(net.minecraft.world.level.biome.Biomes.PLAINS);
        level.setBlockAndUpdate(roof, Blocks.AIR.defaultBlockState());
        level.setRainLevel(1);
        try {
            for (String name : List.of("cork", "plywood_block", "plywood")) {
                var material = BuiltInRegistries.BLOCK.getValue(id(name));
                var fuel = material.defaultBlockState();
                if (material instanceof BlockSheet) fuel = fuel.setValue(BlockSheet.FACING, Direction.DOWN);
                clearFireFixture(level, firePos);
                level.setBlockAndUpdate(firePos.below(), fuel);
                level.setBlockAndUpdate(firePos, Blocks.FIRE.defaultBlockState());
                require(level.isRainingAt(firePos), "Rain fixture is covered or outside a rainy biome");
                level.getBlockState(firePos).tick(level, firePos, burnRandom(299));
                require(level.isEmptyBlock(firePos) && level.getBlockState(firePos.below()).is(material),
                    "Decorative wood acted as an infinite fire source: " + name);
            }
            clearFireFixture(level, firePos);
            level.setBlockAndUpdate(firePos.below(), Blocks.NETHERRACK.defaultBlockState());
            level.setBlockAndUpdate(firePos, Blocks.FIRE.defaultBlockState());
            level.getBlockState(firePos).tick(level, firePos, burnRandom(299));
            require(level.getBlockState(firePos).is(Blocks.FIRE), "Vanilla infinite fire source was disabled");
        } finally {
            clearFireFixture(level, firePos);
            level.setBlockAndUpdate(roof, roofState);
            level.setRainLevel(previousRain);
            level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, previous, level.getServer());
        }
        test.succeed();
    }

    @GameTest
    public void flowingWaterPreservesOpenForestryFenceGates(GameTestHelper test) {
        var level = test.getLevel();
        var source = test.absolutePos(new BlockPos(2, 3, 3));
        var target = source.east();
        for (var pos : List.of(source.below(), target.below(), source.west(), source.north(), source.south(), target.north(), target.south())) {
            level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
        }
        level.setBlockAndUpdate(target, Blocks.SHORT_GRASS.defaultBlockState());
        level.setBlockAndUpdate(source, Blocks.WATER.defaultBlockState());
        level.getFluidState(source).tick(level, source, level.getBlockState(source));
        require(level.getFluidState(target).is(FluidTags.WATER), "Water fixture did not flow through the target");
        for (var block : BuiltInRegistries.BLOCK) {
            if (!(block instanceof forestry.arboriculture.wood.ForestryFenceGateBlock)) continue;
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                for (boolean open : List.of(false, true)) {
                    var state = block.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing).setValue(BlockStateProperties.OPEN, open);
                    level.setBlockAndUpdate(target, state);
                    level.getFluidState(source).tick(level, source, level.getBlockState(source));
                    require(level.getBlockState(target).equals(state), "Flowing water destroyed or changed " + block + ": " + facing + ", open=" + open);
                }
            }
        }
        test.succeed();
    }

    private static void prepareFireFixture(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
        clearFireFixture(level, pos);
        level.setBlockAndUpdate(pos.below(), Blocks.NETHERRACK.defaultBlockState());
        level.setBlockAndUpdate(pos.east().below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pos, Blocks.FIRE.defaultBlockState());
    }

    private static void clearFireFixture(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
        for (BlockPos cell : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 4, 1))) {
            level.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    private static RandomSource burnRandom(int burnRoll) {
        return new net.minecraft.world.level.levelgen.LegacyRandomSource(0) {
            @Override
            public int nextInt(int bound) {
                if (bound == 250 || bound == 300) return Math.min(burnRoll, bound - 1);
                return bound - 1;
            }

            @Override
            public float nextFloat() {
                return 0;
            }
        };
    }

    @GameTest
    public void recipeGhostTransferIsAuthorizedAndImmutable(GameTestHelper test) {
        var pos = test.absolutePos(new BlockPos(3, 2, 3));
        test.getLevel().setBlockAndUpdate(pos, FactoryBlocks.PLAIN.get(BlockTypeFactoryPlain.CARPENTER).defaultState());
        var tile = (TileCarpenter) test.getLevel().getBlockEntity(pos);
        var player = FakePlayer.get(test.getLevel());
        player.setPos(pos.getX(), pos.getY(), pos.getZ());
        var pattern = NonNullList.withSize(9, ItemStack.EMPTY);
        pattern.set(0, new ItemStack(Items.DIAMOND, 64));
        var packet = new PacketRecipeTransferRequest(pos, pattern);
        pattern.getFirst().setCount(1);
        packet.craftingInventory().set(0, ItemStack.EMPTY);
        require(packet.craftingInventory().getFirst().getCount() == 64, "Transfer retained caller-owned stacks");
        player.containerMenu = player.inventoryMenu;
        PacketRecipeTransferRequest.handle(packet, player);
        require(tile.getCraftingInventory().isEmpty(), "Transfer without a menu edited the ghost grid");
        player.containerMenu = new ContainerCarpenter(24, player.getInventory(), tile);
        PacketRecipeTransferRequest.handle(new PacketRecipeTransferRequest(pos.offset(1000, 0, 1000), packet.craftingInventory()), player);
        require(tile.getCraftingInventory().isEmpty(), "Wrong-position transfer edited the grid");
        PacketRecipeTransferRequest.handle(packet, player);
        require(tile.getCraftingInventory().getItem(0).is(Items.DIAMOND) && tile.getCraftingInventory().getItem(0).getCount() == 1, "Ghost transfer did not normalize item counts");
        require(tile.isEmpty(), "Ghost recipe fabricated real inventory");
        player.containerMenu = player.inventoryMenu;
        test.succeed();
    }

    @GameTest
    public void chestBoatRetainsWoodInventoryAndSupportsHoppers(GameTestHelper test) {
        var level = test.getLevel();
        var pos = test.absolutePos(new BlockPos(3, 2, 3));
        level.setBlockAndUpdate(pos, Blocks.HOPPER.defaultBlockState());
        var boat = new ForestryChestBoat(level, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
        boat.setWoodType(ForestryWoodType.BALSA);
        boat.setItem(0, new ItemStack(Items.DIAMOND, 7));
        var output = TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess());
        boat.saveWithoutId(output);
        var restored = new ForestryChestBoat(level, boat.getX(), boat.getY(), boat.getZ());
        restored.load(TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), output.buildResult()));
        require(restored.getWoodType() == ForestryWoodType.BALSA && restored.getItem(0).getCount() == 7, "Boat save lost wood or inventory");
        level.addFreshEntity(restored);
        var hopper = (HopperBlockEntity) level.getBlockEntity(pos);
        require(HopperBlockEntity.suckInItems(level, hopper), "Native hopper did not find Forestry chest boat");
        require(restored.getItem(0).getCount() == 6 && hopper.getItem(0).getCount() == 1, "Hopper boat extraction was not conservative");
        restored.discard();
        test.succeed();
    }

    @GameTest
    public void customFluidUsesSwimmingWithoutBecomingWater(GameTestHelper test) {
        var level = test.getLevel();
        var pos = new BlockPos(3, 2, 3);
        var fluid = ForestryFluids.HONEY.getFluid();
        require(fluid.defaultFluidState().is(ForestryFluidMotion.FLUIDS) && !fluid.defaultFluidState().is(FluidTags.WATER), "Custom fluid movement tag changed water semantics");
        for (BlockPos cell : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) test.setBlock(cell, fluid.defaultFluidState().createLegacyBlock());
        var pig = test.spawn(EntityTypes.PIG, pos);
        pig.setNoAi(true);
        pig.setNoGravity(true);
        test.runAtTickTime(3, () -> {
            require(pig.getFluidHeight(ForestryFluidMotion.FLUIDS) > 0 && !pig.isInWater(), "Native entity did not track Forestry fluid");
            require(pig.isEyeInFluid(ForestryFluidMotion.FLUIDS) && pig.getAirSupply() < pig.getMaxAirSupply(), "Custom fluid did not preserve respiration");
            pig.setSprinting(true);
            pig.updateSwimming();
            require(pig.isSwimming(), "Custom fluid did not enable the native swimming pose");
            pig.setSprinting(false);
            pig.updateSwimming();
            require(!pig.isSwimming(), "Custom fluid retained swimming after sprinting stopped");
            pig.setAirSupply(-20);
            float health = pig.getHealth();
            pig.baseTick();
            require(pig.getHealth() < health, "Custom fluid did not apply native drowning damage");
            pig.setDeltaMovement(new Vec3(0.4, 0, 0));
            pig.travel(Vec3.ZERO);
            require(pig.getDeltaMovement().x < 0.36, "Custom fluid used dry-land movement instead of water drag");
            pig.discard();
            test.succeed();
        });
    }
}
