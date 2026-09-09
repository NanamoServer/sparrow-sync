package net.momirealms.sparrow.sync.gui;

import net.momirealms.sparrow.sync.session.operation.SnapshotDetailResult;
import net.momirealms.sparrow.sync.snapshot.data.type.EnderChestDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.InventoryDataType;
import net.momirealms.sparrow.sync.util.ItemCodec;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

record SnapshotContents(ItemStack[] inventory, ItemStack[] enderChest, boolean inventoryAvailable, boolean enderAvailable, boolean complete) {

    static SnapshotContents prepare(SnapshotDetailResult.Ready ready) {
        SnapshotDetailResult.Preview inventoryPreview = ready.previews().get(InventoryDataType.INVENTORY);
        SnapshotDetailResult.Preview enderPreview = ready.previews().get(EnderChestDataType.ENDER_CHEST);
        InventoryDataType.Inventory inventory = inventoryPreview instanceof SnapshotDetailResult.Preview.Ready value && value.value() instanceof InventoryDataType.Inventory items ? items : null;
        ItemCodec.LoadedItems ender = enderPreview instanceof SnapshotDetailResult.Preview.Ready value && value.value() instanceof ItemCodec.LoadedItems items ? items : null;
        boolean complete = (inventoryPreview == null || inventory != null && inventory.dropped() == 0) && (enderPreview == null || ender != null && ender.dropped() == 0);
        // todo 这里的复制可能没有必要
        return new SnapshotContents(inventory == null ? new ItemStack[0] : copy(inventory.contents()), ender == null ? new ItemStack[0] : copy(ender.items()), inventory != null, ender != null, complete);
    }

    private static ItemStack[] copy(net.minecraft.world.item.ItemStack[] items) {
        ItemStack[] result = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            if (items[i] != null && !items[i].isEmpty()) {
                result[i] = CraftItemStack.asBukkitCopy(items[i]);
            }
        }
        return result;
    }

    List<ItemStack> allItems() {
        List<ItemStack> items = new ArrayList<>();
        append(items, this.inventory);
        append(items, this.enderChest);
        return items;
    }

    private static void append(List<ItemStack> target, ItemStack[] source) {
        for (int i = 0; i < source.length; i++) {
            if (source[i] != null && !source[i].isEmpty()) {
                target.add(source[i].clone());
            }
        }
    }

    static List<Integer> slots(int size, boolean enderChest, int page) {
        List<Integer> slots = new ArrayList<>();
        if (enderChest) {
            for (int i = page * 36; i < Math.min(size, (page + 1) * 36); i++) {
                slots.add(i);
            }
        } else {
            // 原版快捷栏是 0..8, 主背包是 9..35, 装备与副手在底行单独映射.
            for (int i = 9; i < 36; i++) {
                slots.add(i < size ? i : -1);
            }
            for (int i = 0; i < 9; i++) {
                slots.add(i < size ? i : -1);
            }
        }
        return slots;
    }
}
