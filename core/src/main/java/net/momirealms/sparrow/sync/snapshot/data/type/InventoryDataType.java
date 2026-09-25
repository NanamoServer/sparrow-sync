package net.momirealms.sparrow.sync.snapshot.data.type;

import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.proxy.minecraft.world.entity.player.InventoryProxy;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import net.momirealms.sparrow.sync.util.ItemCodec;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * 同步背包全部槽位和选中的快捷栏位置, 包括盔甲、副手及 body/saddle.
 * 快照记录实际容量, 应用时按本服容量重新安置物品.
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
    public boolean critical() {
        return true;
    }

    @Override
    @NotNull
    public Inventory capture(@NotNull Player player, @NotNull CaptureMode mode) {
        net.minecraft.world.entity.player.Inventory inventory = ((CraftPlayer) player).getHandle().getInventory();
        return new Inventory(ItemCodec.captureItems(inventory, mode), InventoryProxy.INSTANCE.getSelected(inventory), 0);
    }

    @Override
    @NotNull
    public Tag encode(@NotNull Inventory value) {
        CompoundTag root = NBT.createCompound();
        root.putInt("DataVersion", VersionHelper.WORLD_VERSION);
        root.putInt(SIZE_KEY, value.contents().length);
        root.putInt(HELD_SLOT_KEY, value.heldSlot());
        root.put(ITEMS_KEY, ItemCodec.saveItems(value.contents()));
        return root;
    }

    @Override
    @NotNull
    public Inventory decode(@NotNull Tag data) throws IOException {
        if (!(data instanceof CompoundTag root)) {
            throw new IOException("inventory data is not a compound");
        }
        int size = Math.max(1, root.getInt(SIZE_KEY, FALLBACK_SIZE));
        ItemCodec.LoadedItems loaded = ItemCodec.loadItems(root.getList(ITEMS_KEY, NBT.createList()), size, root.getInt("DataVersion"));
        return new Inventory(loaded.items(), root.getInt(HELD_SLOT_KEY), loaded.dropped());
    }

    @Override
    public void apply(@NotNull Player player, @NotNull Inventory value) {
        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        net.minecraft.world.entity.player.Inventory inventory = handle.getInventory();
        ItemCodec.LoadedItems fitted = ItemCodec.fit(value.contents(), inventory.getContainerSize());
        ItemStack[] items = fitted.items();
        for (int slot = 0; slot < items.length; slot++) {
            // 写入物品副本, 空槽也要写入以清除本服旧物品
            ItemStack item = items[slot] == null ? ItemStack.EMPTY : items[slot].copy();
            inventory.setItem(slot, item);
            if (slot > 40) {
                // body/saddle 不在 inventoryMenu 中, 使用 Craft 的玩家背包更新包
                handle.connection.send(new ClientboundSetPlayerInventoryPacket(slot, item.copy()));
            } else {
                // 槽位映射为快捷栏 0..8 -> 36..44, 盔甲倒序, 副手 -> 45
                int menuSlot = slot;
                if (slot < 9) {
                    menuSlot += 36;
                } else if (slot > 39) {
                    menuSlot += 5;
                } else if (slot > 35) {
                    menuSlot = 44 - slot;
                }
                handle.connection.send(new ClientboundContainerSetSlotPacket(handle.inventoryMenu.containerId, handle.inventoryMenu.incrementStateId(), menuSlot, item));
            }
        }
        InventoryProxy.INSTANCE.setSelected(inventory, value.heldSlot());
        handle.connection.send(new ClientboundSetHeldSlotPacket(value.heldSlot()));
        int dropped = value.dropped() + fitted.dropped();
        if (dropped > 0) {
            this.logger.warn(LogCategory.DATA, player.getUniqueId(), player.getName(), LogConstants.DATA_INVENTORY_DROPPED, String.valueOf(dropped), player.getName());
        }
    }

    @Override
    public boolean shouldApply(@NotNull PlayerSession session) {
        return PluginConfig.synchronization$nativeAsyncApply().playerData();
    }

    @Override
    @NotNull
    public NativeApplyResult applyNative(@NotNull PlayerSession session, @NotNull CompoundTag playerData, @NotNull Inventory value) {
        boolean equipmentFormat = VersionHelper.isOrAbove1_21_5;
        int expectedSize = equipmentFormat ? EQUIPMENT_SIZE : LEGACY_SIZE;
        if (value.contents().length != expectedSize || value.dropped() != 0) return NativeApplyResult.NOT_APPLIED;

        ListTag inventory = NBT.createList();
        for (int i = 0; i < STORAGE_SIZE; i++) {
            addNativeItem(inventory, value.contents()[i], i);
        }
        // 1.21.5 起盔甲、副手、body 和 saddle 改存于 equipment map
        if (equipmentFormat) {
            Tag current = playerData.get("equipment");
            CompoundTag equipment = current instanceof CompoundTag compound
                    ? compound.deepClone()
                    : NBT.createCompound();
            equipment.remove("mainhand");
            for (int i = 0; i < EQUIPMENT_KEYS.length; i++) equipment.remove(EQUIPMENT_KEYS[i]);
            for (int i = 0; i < EQUIPMENT_KEYS.length; i++) {
                ItemStack item = value.contents()[STORAGE_SIZE + i];
                if (item != null && !item.isEmpty()) equipment.put(EQUIPMENT_KEYS[i], ItemCodec.saveItem(item));
            }
            playerData.put("Inventory", inventory);
            playerData.put("equipment", equipment);
        } else {
            for (int i = 0; i < 4; i++) addNativeItem(inventory, value.contents()[STORAGE_SIZE + i], 100 + i);
            addNativeItem(inventory, value.contents()[40], 150);
            playerData.put("Inventory", inventory);
        }
        playerData.putInt("SelectedItemSlot", value.heldSlot());
        return NativeApplyResult.APPLIED_PLAYER_DATA;
    }

    private static void addNativeItem(ListTag target, @Nullable ItemStack item, int slot) {
        if (item == null || item.isEmpty()) return;
        CompoundTag encoded = ItemCodec.saveItem(item);
        encoded.putByte("Slot", (byte) slot);
        target.add(encoded);
    }

    /**
     * 解码后的背包数据.
     * @param heldSlot 选中的快捷栏槽位, 越界时设为 0
     * @param dropped 容量不足而丢弃的物品数
     */
    public record Inventory(@Nullable ItemStack @NotNull [] contents, int heldSlot, int dropped) {
        public Inventory {
            if (heldSlot < 0 || heldSlot > 8) {
                heldSlot = 0;
            }
        }
    }
}
