package forestry.fabric.test;

import forestry.core.features.*;
import forestry.api.core.fluids.FluidVolume;
import forestry.core.content.machines.gui.ContainerFermenter;
import forestry.core.content.machines.tiles.TileFermenter;
import forestry.core.platform.fluids.FluidHelper;
import forestry.core.platform.fluids.ForestryFluids;
import forestry.core.platform.network.packets.PacketPipetteClick;
import forestry.core.platform.inventory.InventoryPlain;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.*;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import static forestry.fabric.test.F1RegistrationTests.*;

public final class F2UtilityTests {
    @GameTest public void pipetteMenuConservesLockedTankContentsAndRejectsInvalidClicks(GameTestHelper test) {
        var pos = test.absolutePos(new BlockPos(3, 2, 3));
        var machine = (TileFermenter) F2MachineTests.place(test.getLevel(), pos, "fermenter");
        var tank = machine.getTankManager().getTank(0);
        long original = 2 * FluidConstants.BUCKET + 1;
        tank.setFluid(new FluidVolume(FluidVariant.of(Fluids.WATER), original));
        require(!tank.supportsExtraction(), "Fixture must exercise an automation-locked tank");
        var player = FakePlayer.get(test.getLevel(), new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "PipetteMenu"));
        player.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        var previousMenu = player.containerMenu;
        var previousCursor = player.inventoryMenu.getCarried().copy();
        var menu = new ContainerFermenter(73, player.getInventory(), machine);
        try {
            player.inventoryMenu.setCarried(new ItemStack(Items.DIAMOND));
            player.containerMenu = menu;
            menu.setCarried(CoreUtilityItems.PIPETTE.stack());
            for (int invalidSlot : new int[]{-1, Integer.MAX_VALUE, machine.getTankManager().size()}) {
                PacketPipetteClick.handle(new PacketPipetteClick(menu.containerId, invalidSlot), player);
            }
            PacketPipetteClick.handle(new PacketPipetteClick(menu.containerId + 1, 0), player);
            require(tank.getAmount() == original && FluidHelper.getFluidContained(menu.getCarried()).isEmpty(), "Invalid click changed fluid contents");

            long extracted = 0;
            for (int attempt = 0; attempt < 4; attempt++) {
                var pipette = CoreUtilityItems.PIPETTE.stack();
                pipette.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Menu pipette"));
                menu.setCarried(pipette);
                PacketPipetteClick.handle(new PacketPipetteClick(menu.containerId, 0), player);
                long amount = FluidHelper.getFluidContained(menu.getCarried()).amount();
                long expected = Math.min(FluidConstants.BUCKET, original - extracted);
                require(amount == expected, "Pipette did not extract the exact remaining amount");
                extracted += amount;
                require(tank.getAmount() + extracted == original, "Repeated GUI extraction duplicated or lost fluid");
                require(menu.getCarried().getHoverName().getString().equals("Menu pipette"), "GUI transfer lost pipette components");
            }
            require(extracted == original && tank.isEmpty(), "GUI failed to empty the locked tank");
            require(player.inventoryMenu.getCarried().is(Items.DIAMOND), "Transfer changed the inventory menu instead of the active cursor");

            var full = CoreUtilityItems.PIPETTE.stack();
            full.set(CoreDataComponents.FLUID_CONTENT.get(), new FluidVolume(FluidVariant.of(Fluids.WATER), FluidConstants.BUCKET));
            menu.setCarried(full);
            PacketPipetteClick.handle(new PacketPipetteClick(menu.containerId, 0), player);
            require(tank.getAmount() == FluidConstants.BUCKET && FluidHelper.getFluidContained(menu.getCarried()).isEmpty(), "Pipette did not return exactly one bucket");
            player.setPos(pos.getX() + 20, pos.getY(), pos.getZ());
            PacketPipetteClick.handle(new PacketPipetteClick(menu.containerId, 0), player);
            require(tank.getAmount() == FluidConstants.BUCKET && FluidHelper.getFluidContained(menu.getCarried()).isEmpty(), "Distant player extracted fluid");
        } finally {
            player.containerMenu = previousMenu;
            player.inventoryMenu.setCarried(previousCursor);
        }
        test.succeed();
    }

    @GameTest public void explosiveFluidsRequireNearbyIgnitionInEveryDirection(GameTestHelper test) {
        var level = test.getLevel();
        var center = test.absolutePos(new BlockPos(3, 3, 3));
        var ethanol = ForestryFluids.BIO_ETHANOL.getFeature().fluidBlock().block();
        for (var pos : BlockPos.betweenClosed(center.offset(-2, -2, -2), center.offset(2, 2, 2))) {
            level.setBlock(pos, Blocks.OBSIDIAN.defaultBlockState(), 2);
        }
        for (Direction direction : Direction.values()) {
            clearFluidChamber(test, center);
            level.setBlock(center, ethanol.defaultBlockState(), 2);
            ethanol.randomTick(level.getBlockState(center), level, center, RandomSource.create(42));
            require(level.getBlockState(center).is(ethanol), "Ethanol exploded without an ignition source");
            var distant = center.relative(direction, 2);
            level.setBlock(distant, Blocks.LAVA.defaultBlockState(), 2);
            ethanol.randomTick(level.getBlockState(center), level, center, RandomSource.create(42));
            require(level.getBlockState(center).is(ethanol), "Ethanol reacted to lava outside its immediate neighborhood");
            level.setBlock(distant, Blocks.OBSIDIAN.defaultBlockState(), 2);
            level.setBlock(center.relative(direction), Blocks.LAVA.defaultBlockState(), 2);
            ethanol.randomTick(level.getBlockState(center), level, center, RandomSource.create(42));
            require(!level.getBlockState(center).is(ethanol), "Ethanol ignored adjacent lava on " + direction);
        }
        for (var fire : new net.minecraft.world.level.block.Block[]{Blocks.FIRE, Blocks.SOUL_FIRE}) {
            clearFluidChamber(test, center);
            var ignition = center.east();
            level.setBlock(ignition.below(), Blocks.SOUL_SOIL.defaultBlockState(), 2);
            level.setBlock(center, ethanol.defaultBlockState(), 2);
            level.setBlock(ignition, fire.defaultBlockState(), 2);
            require(level.getBlockState(ignition).is(fire), "Fire fixture was not placed");
            ethanol.randomTick(level.getBlockState(center), level, center, RandomSource.create(42));
            require(!level.getBlockState(center).is(ethanol), "Ethanol ignored " + fire);
        }
        clearFluidChamber(test, center);
        var oil = ForestryFluids.SEED_OIL.getFeature().fluidBlock().block();
        level.setBlock(center, oil.defaultBlockState(), 2);
        level.setBlock(center.east(), Blocks.LAVA.defaultBlockState(), 2);
        oil.randomTick(level.getBlockState(center), level, center, RandomSource.create(42));
        require(level.getBlockState(center).is(oil), "Low-flammability fluid incorrectly exploded");
        clearFluidChamber(test, center);
        test.succeed();
    }

    private static void clearFluidChamber(GameTestHelper test, BlockPos center) {
        for (var pos : BlockPos.betweenClosed(center.offset(-1, -1, -1), center.offset(1, 1, 1))) {
            test.getLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        }
    }

    @GameTest public void pipetteUsesHeldContextAndRetainsEveryDroplet(GameTestHelper test) {
        var inventory = new InventoryPlain(1, "pipette", 64);
        var stack = CoreUtilityItems.PIPETTE.stack();
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("F2 pipette"));
        inventory.setItem(0, stack);
        var storage = ContainerItemContext.ofSingleSlot(inventory.getTransferHandler().getSlot(0)).find(FluidStorage.ITEM);
        require(storage != null, "Pipette lookup missing");
        try (var outer = Transaction.openOuter()) {
            try (var inner = outer.openNested()) {
                require(storage.insert(FluidVariant.of(Fluids.WATER), FluidConstants.BUCKET - 1, inner) == FluidConstants.BUCKET - 1, "Pipette insert rounded droplets");
                inner.commit();
            }
        }
        require(!inventory.getItem(0).has(CoreDataComponents.FLUID_CONTENT.get()), "Aborted pipette fill persisted");
        try (var tx = Transaction.openOuter()) { storage.insert(FluidVariant.of(Fluids.WATER), FluidConstants.BUCKET - 1, tx); tx.commit(); }
        require(inventory.getItem(0).get(CoreDataComponents.FLUID_CONTENT.get()).amount() == FluidConstants.BUCKET - 1, "Pipette component lost precision");
        require(inventory.getItem(0).getHoverName().getString().equals("F2 pipette"), "Pipette overwrote unrelated components");
        var tag = inventory.write(new net.minecraft.nbt.CompoundTag(), test.getLevel().registryAccess());
        var restored = new InventoryPlain(1, "pipette", 64); restored.read(tag, test.getLevel().registryAccess());
        require(ItemStack.matches(inventory.getItem(0), restored.getItem(0)), "Pipette did not save");
        try (var tx = Transaction.openOuter()) { require(storage.extract(FluidVariant.of(Fluids.WATER), FluidConstants.BUCKET, tx) == FluidConstants.BUCKET - 1, "Pipette extract changed quantity"); tx.commit(); }
        require(!inventory.getItem(0).has(CoreDataComponents.FLUID_CONTENT.get()), "Empty pipette retains a fluid component");
        test.succeed();
    }

    @GameTest public void milkBucketUsesNativeStorageAndOriginalIdentifier(GameTestHelper test) {
        var milk = net.minecraft.core.registries.BuiltInRegistries.FLUID.getValue(net.minecraft.resources.Identifier.parse("minecraft:milk"));
        require(milk != Fluids.EMPTY, "Legacy milk identity is missing");
        var inventory = new InventoryPlain(1, "milk", 64); inventory.setItem(0, new ItemStack(Items.MILK_BUCKET));
        var context = ContainerItemContext.ofSingleSlot(inventory.getTransferHandler().getSlot(0));
        var storage = context.find(FluidStorage.ITEM);
        require(storage != null, "Vanilla milk bucket storage is missing");
        try (var tx = Transaction.openOuter()) { require(storage.extract(FluidVariant.of(milk), FluidConstants.BUCKET, tx) == FluidConstants.BUCKET, "Milk cannot be drained"); }
        require(inventory.getItem(0).is(Items.MILK_BUCKET), "Aborted milk conversion consumed its bucket");
        try (var tx = Transaction.openOuter()) { storage.extract(FluidVariant.of(milk), FluidConstants.BUCKET, tx); tx.commit(); }
        require(inventory.getItem(0).is(Items.BUCKET), "Milk did not return an empty bucket");
        storage = context.find(FluidStorage.ITEM);
        try (var tx = Transaction.openOuter()) { require(storage.insert(FluidVariant.of(milk), FluidConstants.BUCKET, tx) == FluidConstants.BUCKET, "Milk cannot refill the original bucket"); tx.commit(); }
        require(inventory.getItem(0).is(Items.MILK_BUCKET), "Filled milk is not drinkable vanilla milk");
        test.succeed();
    }

    @GameTest public void nativeDamagePreservesRemnantsAndFrameStackLimit(GameTestHelper test) {
        var player = FakePlayer.get(test.getLevel(), new com.mojang.authlib.GameProfile(java.util.UUID.fromString("33b9fe7c-323d-4c60-bf42-336b28a408ed"), "F2Tools"));
        player.getInventory().clearContent();
        var tool = CoreUtilityItems.SURVIVALISTS_PICKAXE.stack();
        tool.setDamageValue(tool.getMaxDamage() - 1);
        tool.hurtAndBreak(1, test.getLevel(), player, ignored -> {});
        require(tool.isEmpty() && player.getInventory().countItem(CoreUtilityItems.BROKEN_SURVIVALISTS_PICKAXE.item()) == 1, "A broken native tool did not leave exactly one remnant");
        var frame = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id("untreated_frame")));
        require(frame.getMaxStackSize() == 64 && frame.getMaxDamage() == 80, "Native frame lost the original stack or durability policy");
        var ops = test.getLevel().registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
        frame.setCount(64);
        require(ItemStack.CODEC.parse(ops, ItemStack.CODEC.encodeStart(ops, frame).getOrThrow()).getOrThrow().getCount() == 64, "Stacked frames cannot round-trip");
        test.succeed();
    }
}
