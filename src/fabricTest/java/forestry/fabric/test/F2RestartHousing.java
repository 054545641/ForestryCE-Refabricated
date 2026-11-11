package forestry.fabric.test;

import forestry.Forestry;
import forestry.api.apiculture.ForestryBeeSpecies;
import forestry.api.apiculture.IBeeHousing;
import forestry.api.apiculture.genetics.BeeLifeStage;
import forestry.api.core.genetics.capability.IIndividualHandlerItem;
import forestry.apiculture.alveary.multiblock.AlvearyController;
import forestry.apiculture.bees.AbstractBeeHousingBlockEntity;
import forestry.core.platform.multiblock.MultiblockTicker;
import forestry.core.platform.util.InventoryData;
import forestry.core.platform.util.SpeciesUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class F2RestartHousing {
    private static final Path EXPECTED = Path.of("f2-housing-expected.nbt");
    private static final String[] TYPES = {"apiary", "bee_house", "alveary"};
    private static final String[] SCENARIOS = {"breeding", "idle", "overflow"};
    private static final int BREEDING_TICKS = 37;

    private record Fixture(String type, String scenario, ChunkPos chunk, BlockPos pos) {
        String key() { return this.type + "/" + this.scenario; }
    }

    private F2RestartHousing() {
    }

    static void run(GameTestHelper helper) {
        String phase = System.getProperty("forestry.persistencePhase", "");
        if (phase.isEmpty()) {
            helper.succeed();
            return;
        }
        ServerLevel level = helper.getLevel();
        List<Fixture> fixtures = fixtures();
        List<BlockEntity> loaded = new ArrayList<>();
        ChunkLifecycle.steps(helper)
            // full chunks keep the real entities loaded without advancing the saved breeding progress
            .run(() -> fixtures.forEach(fixture -> ChunkLifecycle.hold(level, fixture.chunk, 0)))
            .await(() -> fixtures.stream().allMatch(fixture -> ChunkLifecycle.isFull(level, fixture.chunk)), "Housing restart chunks did not load")
            .run(() -> {
                if (phase.equals("seed")) {
                    for (Fixture fixture : fixtures) {
                        if (fixture.type.equals("alveary")) {
                            MultiblockTestSupport.buildAlveary(helper, ChunkLifecycle.relative(helper, fixture.pos));
                        } else {
                            F2MachineTests.place(level, fixture.pos, fixture.type);
                        }
                    }
                }
            })
            .await(() -> fixtures.stream().filter(fixture -> fixture.type.equals("alveary"))
                .allMatch(fixture -> MultiblockTestSupport.isAssembled(level, fixture.pos)), "Restart alvearies did not assemble")
            .run(() -> {
                for (Fixture fixture : fixtures) {
                    helper.assertFalse(ChunkLifecycle.isTicking(level, fixture.chunk), "Restart fixture unexpectedly ticks: " + fixture.key());
                }
                if (phase.equals("seed")) seed(helper, fixtures);
                else verify(helper, fixtures);
                for (Fixture fixture : fixtures) {
                    loaded.add(level.getBlockEntity(fixture.pos));
                    ChunkLifecycle.release(level, fixture.chunk, 0);
                }
            })
            .await(() -> ChunkLifecycle.isUnloaded(loaded), "Housing restart fixtures did not unload and save")
            .run(() -> Forestry.LOGGER.info("FORESTRY_F2_HOUSING_RESTART phase={} housings=3 scenarios=3 actual_chunks=true", phase))
            .succeed();
    }

    private static List<Fixture> fixtures() {
        List<Fixture> fixtures = new ArrayList<>();
        for (int type = 0; type < TYPES.length; type++) {
            for (int scenario = 0; scenario < SCENARIOS.length; scenario++) {
                ChunkPos chunk = new ChunkPos(-1200 + type * 3 + scenario, 20);
                fixtures.add(new Fixture(TYPES[type], SCENARIOS[scenario], chunk,
                    new BlockPos(chunk.getMinBlockX() + 4, 90, chunk.getMinBlockZ() + 4)));
            }
        }
        return fixtures;
    }

    private static void seed(GameTestHelper helper, List<Fixture> fixtures) {
        ServerLevel level = helper.getLevel();
        CompoundTag expected = new CompoundTag();
        expected.putLong("writer_pid", ProcessHandle.current().pid());
        for (Fixture fixture : fixtures) {
            IBeeHousing housing = housing(level, fixture);
            Container inventory = (Container) housing.getBeeInventory();
            if (fixture.scenario.equals("breeding")) {
                var species = SpeciesUtil.getBeeSpecies(ForestryBeeSpecies.FOREST);
                housing.getBeeInventory().setQueen(species.createStack(BeeLifeStage.PRINCESS));
                housing.getBeeInventory().setDrone(species.createStack(BeeLifeStage.DRONE));
                level.getChunkAt(fixture.pos).tryMarkSaved();
                for (int tick = 0; tick < BREEDING_TICKS; tick++) tick(level, fixture, housing);
                helper.assertTrue(logic(level, housing).getIntOr("BreedingTime", -1) == BREEDING_TICKS, "Breeding fixture did not advance: " + fixture.key());
            } else {
                var queen = SpeciesUtil.getBeeSpecies(ForestryBeeSpecies.FOREST).createIndividual();
                queen.setMate(queen.getGenome());
                queen.setHealth(0);
                housing.getBeeInventory().setQueen(queen.createStack(BeeLifeStage.QUEEN));
                for (int slot = 2; slot < 9; slot++) inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
                level.getChunkAt(fixture.pos).tryMarkSaved();
                tick(level, fixture, housing);
                helper.assertTrue(housing.getBeeInventory().getQueen().isEmpty(), "Dead queen remained in " + fixture.key());
                helper.assertTrue(!pending(level, housing).isEmpty(), "Dead queen produced no pending offspring: " + fixture.key());
                helper.assertTrue(level.getChunkAt(fixture.pos).isUnsaved(), "Queen death did not dirty " + fixture.key());
                if (fixture.scenario.equals("idle")) {
                    for (int slot = 2; slot < 9; slot++) inventory.setItem(slot, ItemStack.EMPTY);
                    level.getChunkAt(fixture.pos).tryMarkSaved();
                    tick(level, fixture, housing);
                    helper.assertTrue(pending(level, housing).isEmpty() && products(inventory) >= 2, "Idle housing did not deliver offspring: " + fixture.key());
                }
            }
            helper.assertTrue(level.getChunkAt(fixture.pos).isUnsaved(), "Gameplay did not dirty " + fixture.key());
            expected.put(fixture.key(), snapshot(level, housing));
        }
        try {
            NbtIo.writeCompressed(expected, EXPECTED);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static void verify(GameTestHelper helper, List<Fixture> fixtures) {
        ServerLevel level = helper.getLevel();
        CompoundTag expected;
        try {
            expected = NbtIo.readCompressed(EXPECTED, NbtAccounter.unlimitedHeap());
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        helper.assertTrue(expected.getLongOr("writer_pid", -1) != ProcessHandle.current().pid(), "Housing verifier reused the writer JVM");
        for (Fixture fixture : fixtures) {
            IBeeHousing housing = housing(level, fixture);
            Container inventory = (Container) housing.getBeeInventory();
            helper.assertTrue(expected.getCompoundOrEmpty(fixture.key()).equals(snapshot(level, housing)), "Saved housing state changed across JVMs: " + fixture.key());
            if (fixture.scenario.equals("breeding")) {
                int remaining = forestry.core.platform.config.Constants.APIARY_BREEDING_TIME - BREEDING_TICKS;
                for (int tick = 0; tick < remaining - 1; tick++) tick(level, fixture, housing);
                helper.assertTrue(IIndividualHandlerItem.getLifeStage(housing.getBeeInventory().getQueen()) == BeeLifeStage.PRINCESS, "Saved breeding finished too early: " + fixture.key());
                tick(level, fixture, housing);
                helper.assertTrue(IIndividualHandlerItem.getLifeStage(housing.getBeeInventory().getQueen()) == BeeLifeStage.QUEEN
                    && housing.getBeeInventory().getDrone().isEmpty(), "Saved breeding did not finish at its remaining tick: " + fixture.key());
            } else if (fixture.scenario.equals("overflow")) {
                List<ItemStack> offspring = pending(level, housing);
                int expectedCount = offspring.stream().mapToInt(ItemStack::getCount).sum();
                for (int slot = 2; slot < 9; slot++) inventory.setItem(slot, ItemStack.EMPTY);
                level.getChunkAt(fixture.pos).tryMarkSaved();
                tick(level, fixture, housing);
                helper.assertTrue(pending(level, housing).isEmpty() && products(inventory) == expectedCount, "Saved pending offspring were lost: " + fixture.key());
                helper.assertTrue(level.getChunkAt(fixture.pos).isUnsaved(), "Pending delivery did not dirty " + fixture.key());
                for (int tick = 0; tick < 3; tick++) tick(level, fixture, housing);
                helper.assertTrue(products(inventory) == expectedCount, "Saved offspring were delivered twice: " + fixture.key());
            } else {
                CompoundTag before = snapshot(level, housing);
                for (int tick = 0; tick < 3; tick++) tick(level, fixture, housing);
                helper.assertTrue(before.equals(snapshot(level, housing)), "Idle housing duplicated or lost offspring: " + fixture.key());
            }
        }
    }

    private static IBeeHousing housing(ServerLevel level, Fixture fixture) {
        if (fixture.type.equals("alveary")) {
            return (AlvearyController) MultiblockTestSupport.controllerAt(level, fixture.pos);
        }
        return (AbstractBeeHousingBlockEntity) level.getBlockEntity(fixture.pos);
    }

    private static void tick(ServerLevel level, Fixture fixture, IBeeHousing housing) {
        if (housing instanceof AbstractBeeHousingBlockEntity tile) {
            tile.serverTick(level, fixture.pos, tile.getBlockState());
        } else {
            var controller = (AlvearyController) housing;
            BlockPos holder = controller.getHolderPos();
            BlockEntity tile = level.getBlockEntity(holder);
            MultiblockTicker.<BlockEntity>getTicker(level).tick(level, holder, tile.getBlockState(), tile);
        }
    }

    private static CompoundTag logic(ServerLevel level, IBeeHousing housing) {
        return housing.getBeekeepingLogic().write(new CompoundTag(), level.registryAccess());
    }

    private static CompoundTag snapshot(ServerLevel level, IBeeHousing housing) {
        CompoundTag snapshot = new CompoundTag();
        InventoryData.write((Container) housing.getBeeInventory(), "Items", snapshot, level.registryAccess());
        snapshot.put("Logic", logic(level, housing));
        return snapshot;
    }

    private static List<ItemStack> pending(ServerLevel level, IBeeHousing housing) {
        List<ItemStack> stacks = new ArrayList<>();
        InventoryData.replaceStacks(stacks, logic(level, housing).getListOrEmpty("Offspring"), level.registryAccess());
        return stacks;
    }

    private static int products(Container inventory) {
        int count = 0;
        for (int slot = 2; slot < 9; slot++) count += inventory.getItem(slot).getCount();
        return count;
    }
}
