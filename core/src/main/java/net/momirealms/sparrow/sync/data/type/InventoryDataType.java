package net.momirealms.sparrow.sync.data.type;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.data.PlayerDataType;
import net.momirealms.sparrow.sync.data.item.ItemCodec;
import net.momirealms.sparrow.sync.locale.MessageConstants;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * 背包同步: 全部槽位 (含盔甲, 副手, 26.x 起的 body/saddle), 光标物品与手持槽位.
 * 槽位数随版本变化, 快照记录写入时的容器大小, 应用时适配到本服大小并重排放不下的物品.
 */
public final class InventoryDataType implements PlayerDataType<InventoryDataType.Inventory> {
    public static final DataKey INVENTORY = DataKey.sparrow("inventory");

    private static final int FALLBACK_SIZE = 41;   // 缺失 size 字段的旧快照按 1.21.x 的 41 槽处理
    private static final String ITEMS_KEY = "items";
    private static final String SIZE_KEY = "size";
    private static final String HELD_SLOT_KEY = "heldSlot";
    private static final String CURSOR_KEY = "cursor";

    private final PluginLogger logger;

    public InventoryDataType(@NotNull PluginLogger logger) {
        this.logger = logger;
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
    public Tag capture(@NotNull Player player) {
        PlayerDataType.ensureOwningThread(player);
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        CompoundTag root = NBT.createCompound();
        root.putInt(SIZE_KEY, contents.length);
        root.putInt(HELD_SLOT_KEY, inventory.getHeldItemSlot());
        ItemStack cursor = player.getItemOnCursor();
        if (!cursor.isEmpty()) {
            root.put(CURSOR_KEY, ItemCodec.saveItem(cursor));
        }
        root.put(ITEMS_KEY, ItemCodec.saveItems(contents));
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
        CompoundTag cursorTag = root.getCompound(CURSOR_KEY, null);
        ItemStack cursor = cursorTag == null ? null : ItemCodec.loadItem(cursorTag, mcDataVersion);
        int heldSlot = Math.clamp(root.getInt(HELD_SLOT_KEY), 0, 8);
        return new Inventory(loaded.items(), cursor, heldSlot, loaded.dropped());
    }

    @Override
    public void apply(@NotNull Player player, @NotNull Inventory value) {
        PlayerDataType.ensureOwningThread(player);
        PlayerInventory inventory = player.getInventory();
        // 快照容器大小与本服不同时 (跨版本) 适配并重排, 放不下的连同解码期的丢弃一起告警
        ItemCodec.LoadedItems fitted = ItemCodec.fit(value.contents(), inventory.getSize());
        int dropped = value.dropped() + fitted.dropped();
        if (dropped > 0) {
            this.logger.warn(TranslationManager.console(MessageConstants.LOG_DATA_INVENTORY_DROPPED, String.valueOf(dropped), player.getName()));
        }
        inventory.setContents(fitted.items());
        inventory.setHeldItemSlot(value.heldSlot());
        player.setItemOnCursor(value.cursor());
    }

    /**
     * 解码后的背包.
     *
     * @param dropped 溢出重排后仍被丢弃的物品数
     */
    public record Inventory(@Nullable ItemStack @NotNull [] contents, @Nullable ItemStack cursor, int heldSlot, int dropped) {
    }
}
