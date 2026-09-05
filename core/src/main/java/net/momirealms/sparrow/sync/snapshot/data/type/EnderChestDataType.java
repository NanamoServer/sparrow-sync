package net.momirealms.sparrow.sync.snapshot.data.type;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import net.momirealms.sparrow.sync.util.ItemCodec;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * 末影箱同步. 原版 27 槽, 但魔改服务端与扩容插件可放大到 54 槽,
 * 快照记录写入时的实际大小, 应用时适配到本服大小并重排放不下的物品.
 */
public final class EnderChestDataType implements NativePlayerDataType<ItemCodec.LoadedItems> {
    public static final DataKey ENDER_CHEST = DataKey.sparrow("ender_chest");
    private static final int FALLBACK_SIZE = 27;   // 缺失 size 字段的快照按原版 27 槽处理
    private static final String ITEMS_KEY = "items";
    private static final String SIZE_KEY = "size";

    private final SyncLogger logger;

    public EnderChestDataType() {
        this.logger = SparrowSync.instance().logger();
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
    public ItemCodec.LoadedItems capture(@NotNull Player player, @NotNull CaptureMode mode) {
        return new ItemCodec.LoadedItems(ItemCodec.captureItems(((CraftPlayer) player).getHandle().getEnderChestInventory(), mode), 0);
    }

    @Override
    @NotNull
    public Tag encode(@NotNull ItemCodec.LoadedItems value) {
        CompoundTag root = NBT.createCompound();
        root.putInt(SIZE_KEY, value.items().length);
        root.put(ITEMS_KEY, ItemCodec.saveItems(value.items()));
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

    @Override
    public void apply(@NotNull Player player, @NotNull ItemCodec.LoadedItems value) {
        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        PlayerEnderChestContainer enderChest = handle.getEnderChestInventory();
        ItemCodec.LoadedItems fitted = ItemCodec.fit(value.items(), enderChest.getContainerSize());
        ItemStack[] items = fitted.items();
        for (int slot = 0; slot < items.length; slot++) {
            enderChest.setItem(slot, items[slot] == null ? ItemStack.EMPTY : items[slot].copy());
        }
        int dropped = value.dropped() + fitted.dropped();
        if (dropped > 0) {
            this.logger.warn(LogCategory.DATA, player.getUniqueId(), player.getName(), LogConstants.DATA_ENDER_CHEST_DROPPED, String.valueOf(dropped), player.getName());
        }
    }

    @Override
    public boolean shouldApply(@NotNull PlayerSession session) {
        return PluginConfig.synchronization$nativeAsyncApply().playerData();
    }

    @Override
    @NotNull
    public NativeApplyResult applyNative(@NotNull PlayerSession session, @NotNull net.minecraft.nbt.CompoundTag playerData, @NotNull ItemCodec.LoadedItems value) {
        if (value.items().length != FALLBACK_SIZE || value.dropped() != 0) return NativeApplyResult.NOT_APPLIED;
        net.minecraft.nbt.ListTag items = ItemCodec.saveNativeItems(value.items());
        playerData.put("EnderItems", items);
        return NativeApplyResult.APPLIED_PLAYER_DATA;
    }
}
