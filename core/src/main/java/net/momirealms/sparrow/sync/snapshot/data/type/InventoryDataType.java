package net.momirealms.sparrow.sync.snapshot.data.type;

import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.proxy.minecraft.nbt.CompoundTagProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.entity.player.InventoryProxy;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import net.momirealms.sparrow.sync.util.ItemCodec;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
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
        return new Inventory(loaded.items(), root.getInt(HELD_SLOT_KEY), loaded.dropped());
    }

    @Override
    public void apply(@NotNull Player player, @NotNull Inventory value) {
        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        net.minecraft.world.entity.player.Inventory inventory = handle.getInventory();
        ItemCodec.LoadedItems fitted = ItemCodec.fit(value.contents(), inventory.getContainerSize());
        ItemStack[] items = fitted.items();
        for (int slot = 0; slot < items.length; slot++) {
            // 玩家持有独立物品, 后续游玩不能修改本次解码值. 空槽也必须写入, 清掉本服旧物品.
            ItemStack item = items[slot] == null ? ItemStack.EMPTY : items[slot].copy();
            inventory.setItem(slot, item);
            if (slot > 40) {
                // body/saddle 不属于 inventoryMenu, 沿用 Craft 的玩家背包包.
                handle.connection.send(new ClientboundSetPlayerInventoryPacket(slot, item.copy()));
            } else {
                // 容器槽位与背包槽位不同: 热键栏 0..8 -> 36..44, 盔甲倒序, 副手 -> 45.
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
    public NativeApplyResult applyNative(@NotNull PlayerSession session, @NotNull net.minecraft.nbt.CompoundTag playerData, @NotNull Inventory value) {
        boolean equipmentFormat = VersionHelper.isOrAbove1_21_5();
        int expectedSize = equipmentFormat ? EQUIPMENT_SIZE : LEGACY_SIZE;
        if (value.contents().length != expectedSize || value.dropped() != 0) return NativeApplyResult.NOT_APPLIED;

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
        return NativeApplyResult.APPLIED_PLAYER_DATA;
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
     * @param heldSlot 手持槽位, 越界时静默归零
     * @param dropped 解码时放不下而被丢弃的物品数
     */
    public record Inventory(@Nullable ItemStack @NotNull [] contents, int heldSlot, int dropped) {
        public Inventory {
            if (heldSlot < 0 || heldSlot > 8) {
                heldSlot = 0;
            }
        }
    }
}
