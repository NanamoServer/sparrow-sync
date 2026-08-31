package net.momirealms.sparrow.sync.data.type;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.data.PlayerDataType;
import net.momirealms.sparrow.sync.util.ItemCodec;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * 末影箱同步. 原版 27 槽, 但魔改服务端与扩容插件可放大到 54 槽,
 * 快照记录写入时的实际大小, 应用时适配到本服大小并重排放不下的物品.
 */
public final class EnderChestDataType implements PlayerDataType<ItemCodec.LoadedItems> {
    public static final DataKey ENDER_CHEST = DataKey.sparrow("ender_chest");
    private static final int FALLBACK_SIZE = 27;   // 缺失 size 字段的快照按原版 27 槽处理
    private static final String ITEMS_KEY = "items";
    private static final String SIZE_KEY = "size";

    private final SyncLogger logger;

    public EnderChestDataType(@NotNull SyncLogger logger) {
        this.logger = logger;
    }

    @Override
    @NotNull
    public DataKey key() {
        return ENDER_CHEST;
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
        ItemStack[] contents = player.getEnderChest().getContents();
        CompoundTag root = NBT.createCompound();
        root.putInt(SIZE_KEY, contents.length);
        root.put(ITEMS_KEY, ItemCodec.saveItems(contents));
        return root;
    }

    @Override
    @NotNull
    public ItemCodec.LoadedItems decode(@NotNull Tag data, int mcDataVersion) throws IOException {
        if (!(data instanceof CompoundTag root)) {
            throw new IOException("ender chest data is not a compound");
        }
        int size = Math.max(1, root.getInt(SIZE_KEY, FALLBACK_SIZE));
        return ItemCodec.loadItems(root.getList(ITEMS_KEY, NBT.createList()), size, mcDataVersion);
    }

    // todo 这块需要改善一下处理, 不能直接drop. 扩容肯定没事, 缩小才要警告.
    @Override
    public void apply(@NotNull Player player, @NotNull ItemCodec.LoadedItems value) {
        PlayerDataType.ensureOwningThread(player);
        Inventory enderChest = player.getEnderChest();
        // 快照容器大小与本服不同时 (扩容插件, 魔改核心) 适配并重排, 放不下的连同解码期的丢弃一起告警
        ItemCodec.LoadedItems fitted = ItemCodec.fit(value.items(), enderChest.getSize());
        int dropped = value.dropped() + fitted.dropped();
        if (dropped > 0) {
            this.logger.warn(LogCategory.DATA, player.getUniqueId(), player.getName(), LogConstants.DATA_ENDER_CHEST_DROPPED, String.valueOf(dropped), player.getName());
        }
        enderChest.setContents(fitted.items());
    }
}
