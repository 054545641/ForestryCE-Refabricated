package forestry.fabric.test;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import forestry.api.apiculture.ForestryBeeSpecies;
import forestry.api.apiculture.genetics.*;
import forestry.api.core.genetics.capability.IIndividualHandlerItem;
import forestry.apiculture.bees.AbstractBeeHousingBlockEntity;
import forestry.core.platform.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
public final class F2HousingTests {
    @GameTest(maxTicks = 2_000_000)
    public void housingRestart(GameTestHelper helper) {
        F2RestartHousing.run(helper);
    }

    @GameTest
    public void swarmHiveUsesDirtGroundAndRetainsPrincess(GameTestHelper helper) {
        var level = helper.getLevel();
        var origin = helper.absolutePos(new BlockPos(2, 3, 2));
        var roof = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, origin).below();
        helper.assertTrue(level.getBlockState(roof).is(net.minecraft.world.level.block.Blocks.BARRIER), "Missing test enclosure roof");
        level.setBlockAndUpdate(roof, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        var princess = SpeciesUtil.getBeeSpecies(ForestryBeeSpecies.FOREST).createIndividual();
        princess.setPristine(false);
        var stack = princess.createStack(BeeLifeStage.PRINCESS);
        var definition = new forestry.apiculture.hives.HiveDefinitionSwarmer(stack);
        var hive = new forestry.apiculture.hives.Hive(definition,
            forestry.apiculture.hives.HiveDefinitionSwarmer.SWARMER_GEN_CHANCE, java.util.List.of());
        var grounds = java.util.List.of(net.minecraft.world.level.block.Blocks.DIRT, net.minecraft.world.level.block.Blocks.GRASS_BLOCK);
        for (int index = 0; index < grounds.size(); index++) {
            var ground = grounds.get(index);
            var pos = origin.above(index * 2);
            level.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(pos.below(), ground.defaultBlockState());
            helper.assertTrue(level.getBlockState(pos.below()).is(forestry.api.ForestryTags.Blocks.SWARM_BEE_GROUND), "Swarm ground tag omitted " + ground);
            var resolved = hive.getPosForHive(level, net.minecraft.util.RandomSource.create(1), pos.getX(), pos.getZ());
            helper.assertTrue(pos.equals(resolved), "Swarm height mismatch: expected " + pos + ", resolved " + resolved);
            helper.assertTrue(forestry.apiculture.hives.HiveDecorator.tryGenHive(level,
                net.minecraft.util.RandomSource.create(1), pos.getX(), pos.getZ(), hive), "Swarm hive did not generate on " + ground);
            helper.assertTrue(level.getBlockEntity(pos) instanceof forestry.apiculture.hives.HiveBlockEntity, "Swarm hive placed at the wrong height");
            var saved = level.getBlockEntity(pos).saveCustomOnly(level.registryAccess());
            var contained = new java.util.ArrayList<ItemStack>();
            InventoryData.replaceStacks(contained, saved.getListOrEmpty("Contained"), level.registryAccess());
            helper.assertTrue(contained.size() == 1 && ItemStack.matches(stack, contained.getFirst()), "Swarm hive lost or changed its princess");
            level.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        }
        var pos = origin.above(2);
        level.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(pos.below(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        helper.assertFalse(forestry.apiculture.hives.HiveDecorator.tryGenHive(level,
            net.minecraft.util.RandomSource.create(1), pos.getX(), pos.getZ(), hive), "Swarm hive accepted stone ground");
        helper.succeed();
    }

    @GameTest public void bees(GameTestHelper h) {
        var housing = (AbstractBeeHousingBlockEntity) F2MachineTests.place(h.getLevel(), h.absolutePos(new BlockPos(3, 2, 3)), "apiary");
        var species = SpeciesUtil.getBeeSpecies(ForestryBeeSpecies.FOREST);
        housing.setItem(0, species.createStack(BeeLifeStage.PRINCESS));
        housing.setItem(1, species.createStack(BeeLifeStage.DRONE));
        housing.setItem(9, new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(forestry.api.ForestryConstants.forestry("creative_frame"))));
        var logic = housing.getBeekeepingLogic();
        for (int i = 0; i < 2000 && IIndividualHandlerItem.getLifeStage(housing.getItem(0)) == BeeLifeStage.PRINCESS; i++) {
            h.assertTrue(logic.canWork(), "Princess with drone can breed");
            logic.doWork();
        }
        h.assertTrue(IIndividualHandlerItem.getLifeStage(housing.getItem(0)) == BeeLifeStage.QUEEN && housing.getItem(1).isEmpty(), "Breeding creates queen and consumes one drone");
        logic.setWorkThrottle(1);
        h.assertTrue(logic.canWork(), "Creative frame permits the newly bred queen to work");
        logic.doWork();
        h.assertTrue(countProducts(housing) > 0, "Actual queen work produces combs");
        logic.canWork();
        h.assertTrue(housing.getItem(0).isEmpty(), "Aged queen dies");
        boolean princess = false, drone = false;
        for (int i = 2; i < 9; i++) {
            var stage = IIndividualHandlerItem.getLifeStage(housing.getItem(i));
            princess |= stage == BeeLifeStage.PRINCESS;
            drone |= stage == BeeLifeStage.DRONE;
        }
        h.assertTrue(princess && drone, "Queen leaves both princess and drone offspring");
        h.succeed();
    }

    @GameTest public void beeOverflow(GameTestHelper h) {
        var housing = (AbstractBeeHousingBlockEntity) F2MachineTests.place(h.getLevel(), h.absolutePos(new BlockPos(3, 2, 3)), "apiary");
        IBee queen = SpeciesUtil.getBeeSpecies(ForestryBeeSpecies.FOREST).createIndividual();
        queen.setMate(queen.getGenome());
        queen.setHealth(0);
        housing.setItem(0, queen.createStack(BeeLifeStage.QUEEN));
        for (int i = 2; i < 9; i++) housing.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        housing.getBeekeepingLogic().canWork();
        var saved = housing.saveCustomOnly(h.getLevel().registryAccess());
        ValueIOUtil.readTag(saved, h.getLevel().registryAccess(), housing::loadCustomOnly);
        for (int i = 2; i < 9; i++) housing.setItem(i, ItemStack.EMPTY);
        housing.getBeekeepingLogic().canWork();
        int offspring = countProducts(housing);
        h.assertTrue(offspring >= 2, "Full housing persists pending offspring and delivers when space returns");
        housing.getBeekeepingLogic().canWork();
        h.assertTrue(countProducts(housing) == offspring, "Pending offspring delivered only once");
        h.succeed();
    }

    private static int countProducts(Container housing) {
        int count = 0;
        for (int i = 2; i < 9; i++) count += housing.getItem(i).getCount();
        return count;
    }

}
