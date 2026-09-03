package net.momirealms.sparrow.sync.snapshot.data.type;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.proxy.minecraft.nbt.CompoundTagProxy;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import net.momirealms.sparrow.sync.util.ItemCodec;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Map;

/**
 * 背包同步: 全部槽位 (含盔甲、副手及 1.21.5 起的 body/saddle) 与手持槽位.
 * 槽位数随版本变化, 快照记录写入时的容器大小, 应用时适配到本服大小并重排放不下的物品.
 */
public final class InventoryDataType implements NativePlayerDataType<InventoryDataType.Inventory> {
    public static final DataKey INVENTORY = DataKey.sparrow("inventory");

    private static final int FALLBACK_SIZE = 41;   // 缺失 size 字段的旧快照按 1.21.x 的 41 槽处理
    private static final int STORAGE_SIZE = 36;
    private static final int LEGACY_SIZE = 41;
    private static final int EQUIPMENT_SIZE = 43;
    private static final String[] EQUIPMENT_KEYS = {"feet", "legs", "chest", "head", "offhand", "body", "saddle"};
    private static final String ITEMS_KEY = "items";
    private static final String SIZE_KEY = "size";
    private static final String HELD_SLOT_KEY = "heldSlot";

    private final SyncLogger logger;

    public InventoryDataType() {
        this.logger = SparrowSync.instance().logger();
    }

    @Override
    @NotNull
    public DataKey key() {
        return INVENTORY;
    }

    @Override
    @NotNull
    public StorageFormat storage() {
        return StorageFormat.BINARY;
    }

    @Override
    public boolean critical() {
        return true;
    }

    @Override
    @NotNull
    public Inventory capture(@NotNull Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        return new Inventory(contents, inventory.getHeldItemSlot(), 0);
    }

    @Override
    @NotNull
    public Tag encode(@NotNull Inventory value) {
        CompoundTag root = NBT.createCompound();
        root.putInt(SIZE_KEY, value.contents().length);
        root.putInt(HELD_SLOT_KEY, value.heldSlot());
        root.put(ITEMS_KEY, ItemCodec.saveItems(value.contents()));
        return root;
    }

    @Override
    @NotNull
    public Inventory decode(@NotNull Tag data, int mcDataVersion) throws IOException {
        if (!(data instanceof CompoundTag root)) {
            throw new IOException("inventory data is not a compound");
        }
        int size = Math.max(1, root.getInt(SIZE_KEY, FALLBACK_SIZE));
        ItemCodec.LoadedItems loaded = ItemCodec.loadItems(root.getList(ITEMS_KEY, NBT.createList()), size, mcDataVersion);
        int heldSlot = Math.clamp(root.getInt(HELD_SLOT_KEY), 0, 8);
        return new Inventory(loaded.items(), heldSlot, loaded.dropped());
    }

    @Override
    public void apply(@NotNull Player player, @NotNull Inventory value) {
        PlayerInventory inventory = player.getInventory();
        // 快照容器大小与本服不同时 (跨版本) 适配并重排, 放不下的连同解码期的丢弃一起告警
        ItemCodec.LoadedItems fitted = ItemCodec.fit(value.contents(), inventory.getSize());
        int dropped = value.dropped() + fitted.dropped();
        if (dropped > 0) {
            this.logger.warn(LogCategory.DATA, player.getUniqueId(), player.getName(), LogConstants.DATA_INVENTORY_DROPPED, String.valueOf(dropped), player.getName());
        }
        inventory.setContents(fitted.items());
        inventory.setHeldItemSlot(value.heldSlot());
    }

    @Override
    public boolean applyNative(@NotNull net.minecraft.nbt.CompoundTag playerData, @NotNull Inventory value) {
        boolean equipmentFormat = VersionHelper.isOrAbove1_21_5();
        int expectedSize = equipmentFormat ? EQUIPMENT_SIZE : LEGACY_SIZE;
        if (value.contents().length != expectedSize || value.dropped() != 0) return false;

        net.minecraft.nbt.ListTag inventory = new net.minecraft.nbt.ListTag();
        for (int i = 0; i < STORAGE_SIZE; i++) {
            addNativeItem(inventory, value.contents()[i], i);
        }
        // 1.21.5 把盔甲、副手、body 和 saddle 从 Inventory 的 100/150 槽迁到了 equipment map.
        if (equipmentFormat) {
            net.minecraft.nbt.Tag current = playerData.get("equipment");
            net.minecraft.nbt.CompoundTag equipment = current instanceof net.minecraft.nbt.CompoundTag compound
                    ? compound.copy()
                    : new net.minecraft.nbt.CompoundTag();
            Map<String, net.minecraft.nbt.Tag> equipmentTags = CompoundTagProxy.INSTANCE.getTags(equipment);
            equipmentTags.remove("mainhand");
            for (int i = 0; i < EQUIPMENT_KEYS.length; i++) equipmentTags.remove(EQUIPMENT_KEYS[i]);
            for (int i = 0; i < EQUIPMENT_KEYS.length; i++) {
                ItemStack item = value.contents()[STORAGE_SIZE + i];
                if (item != null && !item.isEmpty()) equipment.put(EQUIPMENT_KEYS[i], ItemCodec.saveNativeItem(item));
            }
            playerData.put("Inventory", inventory);
            playerData.put("equipment", equipment);
        } else {
            for (int i = 0; i < 4; i++) addNativeItem(inventory, value.contents()[STORAGE_SIZE + i], 100 + i);
            addNativeItem(inventory, value.contents()[40], 150);
            playerData.put("Inventory", inventory);
        }
        playerData.putInt("SelectedItemSlot", value.heldSlot());
        return true;
    }

    private static void addNativeItem(net.minecraft.nbt.ListTag target, @Nullable ItemStack item, int slot) {
        if (item == null || item.isEmpty()) return;
        net.minecraft.nbt.CompoundTag encoded = ItemCodec.saveNativeItem(item);
        encoded.putByte("Slot", (byte) slot);
        target.add(encoded);
    }

    /**
     * 解码后的背包.
     *
     * @param dropped 溢出重排后仍被丢弃的物品数
     */
    public record Inventory(@Nullable ItemStack @NotNull [] contents, int heldSlot, int dropped) {
    }
}
