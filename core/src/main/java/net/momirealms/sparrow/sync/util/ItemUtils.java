package net.momirealms.sparrow.sync.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class ItemUtils {
    private ItemUtils() {
    }

    /** 将全部物品装入潜影盒, 保留物品组件与数量. */
    @NotNull
    public static List<ItemStack> pack(@NotNull List<ItemStack> items, @NotNull Component name) {
        List<ItemStack> result = new ArrayList<>();
        List<net.minecraft.world.item.ItemStack> box = new ArrayList<>(27);
        for (int i = 0; i < items.size(); i++) {
            var item = CraftItemStack.asNMSCopy(items.get(i));
            int remaining = item.getCount();
            while (remaining > 0) {
                int amount = Math.min(remaining, item.getMaxStackSize());
                box.add(item.copyWithCount(amount));
                remaining -= amount;
                if (box.size() == 27) {
                    result.add(box(box, name));
                    box.clear();
                }
            }
        }
        if (!box.isEmpty()) {
            result.add(box(box, name));
        }
        return result;
    }

    private static ItemStack box(List<net.minecraft.world.item.ItemStack> items, Component name) {
        var item = new net.minecraft.world.item.ItemStack(Items.SHULKER_BOX);
        item.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
        item.set(DataComponents.CUSTOM_NAME, name);
        return CraftItemStack.asCraftMirror(item);
    }

    /** 计算整批物品放入后的背包副本, 空间不足时返回 null. */
    @Nullable
    public static ItemStack[] fit(@Nullable ItemStack @NotNull [] storage, @NotNull List<ItemStack> incoming, int inventoryLimit) {
        ItemStack[] result = new ItemStack[storage.length];
        for (int i = 0; i < storage.length; i++) {
            result[i] = storage[i] == null ? null : storage[i].clone();
        }
        for (int i = 0; i < incoming.size(); i++) {
            ItemStack item = incoming.get(i);
            int remaining = item.getAmount();
            int limit = Math.min(inventoryLimit, item.getMaxStackSize());
            for (int slot = 0; slot < result.length && remaining > 0; slot++) {
                ItemStack present = result[slot];
                if (present != null && present.isSimilar(item)) {
                    int added = Math.min(remaining, Math.max(0, limit - present.getAmount()));
                    present.setAmount(present.getAmount() + added);
                    remaining -= added;
                }
            }
            for (int slot = 0; slot < result.length && remaining > 0; slot++) {
                if (result[slot] == null || result[slot].isEmpty()) {
                    ItemStack added = item.clone();
                    added.setAmount(Math.min(remaining, limit));
                    result[slot] = added;
                    remaining -= added.getAmount();
                }
            }
            if (remaining > 0) {
                return null;
            }
        }
        return result;
    }
}
