package net.momirealms.sparrow.sync.gui;

import net.momirealms.sparrow.sync.util.ItemUtils;
import net.momirealms.sparrow.sync.util.ItemCodec;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotFixtures;
import net.momirealms.sparrow.sync.snapshot.data.type.InventoryDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.EnderChestDataType;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotDetailResult;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotInventoryTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void unreadArchiveContainersAreNotTreatedAsEmptyForClaims() {
        var snapshot = SnapshotFixtures.snapshot();
        var unloaded = new SnapshotDetailResult.Preview.Unloaded(100);
        var inventory = new SnapshotDetailResult.Preview.Ready(new InventoryDataType.Inventory(new net.minecraft.world.item.ItemStack[0], 0, 0));
        var ender = new SnapshotDetailResult.Preview.Ready(new ItemCodec.LoadedItems(new net.minecraft.world.item.ItemStack[0], 0));
        var initial = new SnapshotDetailResult.Ready(snapshot, Map.of(InventoryDataType.INVENTORY, unloaded, EnderChestDataType.ENDER_CHEST, unloaded));
        var partial = new SnapshotDetailResult.Ready(snapshot, Map.of(InventoryDataType.INVENTORY, inventory, EnderChestDataType.ENDER_CHEST, unloaded));
        var complete = new SnapshotDetailResult.Ready(snapshot, Map.of(InventoryDataType.INVENTORY, inventory, EnderChestDataType.ENDER_CHEST, ender));
        assertFalse(SnapshotContents.prepare(initial).complete());
        assertFalse(SnapshotContents.prepare(partial).complete());
        assertTrue(SnapshotContents.prepare(complete).complete());
    }

    @Test
    void inventoryMappingKeepsFixedLayoutAndIgnoresExtraSlots() {
        List<Integer> slots = SnapshotContents.slots(43, false, 0);
        assertEquals(36, slots.size());
        assertEquals(9, slots.getFirst());
        assertEquals(35, slots.get(26));
        assertEquals(0, slots.get(27));
        assertEquals(8, slots.get(35));
        for (int slot = 36; slot < 43; slot++) {
            assertFalse(slots.contains(slot));
        }
        assertEquals(36, SnapshotContents.slots(54, true, 0).size());
        assertEquals(35, SnapshotContents.slots(54, true, 0).getLast());
    }

    @Test
    void enderChestPagesCoverExpandedAndPartialContainers() {
        assertEquals(IntStream.range(0, 36).boxed().toList(), SnapshotContents.slots(54, true, 0));
        assertEquals(IntStream.range(36, 54).boxed().toList(), SnapshotContents.slots(54, true, 1));
        assertEquals(List.of(36, 37), SnapshotContents.slots(38, true, 1));
        assertTrue(SnapshotContents.slots(0, true, 0).isEmpty());
    }

    @Test
    void smallInventoryLeavesMissingSlotsEmpty() {
        List<Integer> slots = SnapshotContents.slots(5, false, 0);
        assertEquals(36, slots.size());
        assertEquals(31, slots.stream().filter(slot -> slot == -1).count());
        assertEquals(List.of(0, 1, 2, 3, 4), slots.subList(27, 32));
    }

    @Test
    void receivingPackagesIsAllOrNothingWhenStorageIsFull() {
        ItemStack existing = stack(Items.DIAMOND, 60);
        ItemStack incoming = stack(Items.DIAMOND, 10);
        assertNull(ItemUtils.fit(new ItemStack[]{existing}, List.of(incoming), 64));
        assertEquals(60, existing.getAmount());
        assertEquals(10, incoming.getAmount());
    }

    @Test
    void fillingStorageMergesStacksThenUsesEmptySlotsWithoutMutatingInputs() {
        ItemStack existing = stack(Items.DIAMOND, 60);
        ItemStack incoming = stack(Items.DIAMOND, 10);
        ItemStack[] result = ItemUtils.fit(new ItemStack[]{existing, null}, List.of(incoming), 64);
        assertNotNull(result);
        assertEquals(64, result[0].getAmount());
        assertEquals(6, result[1].getAmount());
        assertEquals(60, existing.getAmount());
        assertEquals(10, incoming.getAmount());
    }

    @Test
    void equipmentUsesItsOwnStackLimit() {
        ItemStack sword = stack(Items.DIAMOND_SWORD, 2);
        ItemStack[] result = ItemUtils.fit(new ItemStack[2], List.of(sword), 64);
        assertNotNull(result);
        assertEquals(1, result[0].getAmount());
        assertEquals(1, result[1].getAmount());
        assertNull(ItemUtils.fit(new ItemStack[1], List.of(sword), 64));
    }

    @Test
    void packingSourceKeepsNestedComponentsAndRemainsIndependentOfClaims() {
        var nativeBox = new net.minecraft.world.item.ItemStack(Items.SHULKER_BOX);
        nativeBox.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new net.minecraft.world.item.ItemStack(Items.DIAMOND, 12))));
        ItemStack box = CraftItemStack.asCraftMirror(nativeBox);
        SnapshotContents contents = new SnapshotContents(new ItemStack[]{box}, new ItemStack[]{stack(Items.EMERALD, 8)}, true, true, true);
        List<ItemStack> first = contents.allItems();
        assertEquals(2, first.size());
        assertEquals(Material.SHULKER_BOX, first.getFirst().getType());
        assertTrue(net.minecraft.world.item.ItemStack.matches(nativeBox, CraftItemStack.asNMSCopy(first.getFirst())));
        first.get(1).setAmount(1);
        assertEquals(8, contents.allItems().get(1).getAmount());
        assertNotSame(box, first.getFirst());
    }

    @Test
    void nativePackingSplitsBoxesAndPreservesStackComponents() {
        var sword = new net.minecraft.world.item.ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.DAMAGE, 12);
        sword.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Saved sword"));
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < 28; i++) {
            items.add(CraftItemStack.asCraftMirror(sword.copy()));
        }
        var name = net.minecraft.network.chat.Component.literal("Snapshot package");
        List<ItemStack> packed = ItemUtils.pack(items, name);
        assertEquals(2, packed.size());
        int total = 0;
        for (ItemStack box : packed) {
            var nativeBox = CraftItemStack.asNMSCopy(box);
            assertEquals(name, nativeBox.get(DataComponents.CUSTOM_NAME));
            var contents = nativeBox.get(DataComponents.CONTAINER).stream().toList();
            for (var item : contents) {
                assertTrue(net.minecraft.world.item.ItemStack.matches(sword, item));
                total += item.getCount();
            }
        }
        assertEquals(28, total);
        assertEquals(28, items.size());
    }

    @Test
    void nativePackingNestsExistingBoxesAndSplitsOversizedStacks() {
        var original = new net.minecraft.world.item.ItemStack(Items.BLUE_SHULKER_BOX);
        original.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new net.minecraft.world.item.ItemStack(Items.EMERALD, 8))));
        List<ItemStack> packed = ItemUtils.pack(List.of(CraftItemStack.asCraftMirror(original), stack(Items.DIAMOND, 70)), net.minecraft.network.chat.Component.literal("Package"));
        assertEquals(1, packed.size());
        var contents = CraftItemStack.asNMSCopy(packed.getLast()).get(DataComponents.CONTAINER).stream().toList();
        assertTrue(net.minecraft.world.item.ItemStack.matches(original, contents.getFirst()));
        assertEquals(List.of(1, 64, 6), contents.stream().map(net.minecraft.world.item.ItemStack::getCount).toList());
        assertTrue(ItemUtils.pack(List.of(), net.minecraft.network.chat.Component.empty()).isEmpty());
    }

    private static ItemStack stack(net.minecraft.world.item.Item item, int amount) {
        return CraftItemStack.asCraftMirror(new net.minecraft.world.item.ItemStack(item, amount));
    }
}
