package net.momirealms.sparrow.sync.snapshot.data.type;

import net.minecraft.nbt.NbtOps;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig.PDCMergeBlacklist;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataType;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Map;

/**
 * PDC 同步会把快照数据递归合入本服 {@code custom_data}, 黑名单路径留在各服务器本地.
 * 黑名单子树不会进入新快照, 也不会从已有快照写回.
 */
public final class PDCDataType implements PlayerDataType<CompoundTag> {
    public static final DataKey PERSISTENT_DATA = DataKey.sparrow("persistent_data");

    @Override
    @NotNull
    public DataKey key() {
        return PERSISTENT_DATA;
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
    public CompoundTag capture(@NotNull Player player) {
        return captureCompound(((CraftPlayer) player).getPersistentDataContainer().getRaw().entrySet(), PluginConfig.synchronization$pdcMergeNamespaces());
    }

    @Override
    @NotNull
    public Tag encode(@NotNull CompoundTag value) {
        return value;
    }

    @NotNull
    private static CompoundTag captureCompound(@NotNull Iterable<Map.Entry<String, net.minecraft.nbt.Tag>> entries, @NotNull PDCMergeBlacklist blacklist) {
        CompoundTag snapshot = NBT.createCompound();
        for (Map.Entry<String, net.minecraft.nbt.Tag> entry : entries) {
            PDCMergeBlacklist child = blacklist.child(entry.getKey());
            if (child != null && child.terminal()) {
                continue;
            }
            snapshot.put(entry.getKey(), captureTag(entry.getValue(), child));
        }
        return snapshot;
    }

    @NotNull
    private static Tag captureTag(@NotNull net.minecraft.nbt.Tag value, @Nullable PDCMergeBlacklist blacklist) {
        if (blacklist != null && blacklist.hasChildren() && value instanceof net.minecraft.nbt.CompoundTag compound) {
            return captureCompound(compound.entrySet(), blacklist);
        }
        return NbtOps.INSTANCE.convertTo(NBTOps.INSTANCE, value);
    }

    @Override
    @NotNull
    public CompoundTag decode(@NotNull Tag data, int mcDataVersion) throws IOException {
        if (!(data instanceof CompoundTag compound)) {
            throw new IOException("persistent data is not a compound");
        }
        return compound;
    }

    @Override
    public void apply(@NotNull Player player, @NotNull CompoundTag value) {
        Map<String, net.minecraft.nbt.Tag> target = ((CraftPlayer) player).getPersistentDataContainer().getRaw();
        PDCMergeBlacklist blacklist = PluginConfig.synchronization$pdcMergeNamespaces();

        for (Map.Entry<String, Tag> entry : value.entrySet()) {
            PDCMergeBlacklist child = blacklist.child(entry.getKey());
            if (child != null && child.terminal()) {
                continue;
            }
            target.put(entry.getKey(), mergeTag(target.get(entry.getKey()), entry.getValue(), child));
        }
    }

    @NotNull
    private static net.minecraft.nbt.Tag mergeTag(@Nullable net.minecraft.nbt.Tag current, @NotNull Tag source, @Nullable PDCMergeBlacklist blacklist) {
        if (source instanceof CompoundTag sourceCompound) {
            net.minecraft.nbt.CompoundTag targetCompound = current instanceof net.minecraft.nbt.CompoundTag compound
                    ? compound
                    : new net.minecraft.nbt.CompoundTag();
            mergeCompound(targetCompound, sourceCompound, blacklist);
            return targetCompound;
        }
        // 含黑名单后代的本服 Compound 在类型冲突时保持原值
        if (blacklist != null && blacklist.hasChildren() && current instanceof net.minecraft.nbt.CompoundTag) {
            return current;
        }
        return NBTOps.INSTANCE.convertTo(NbtOps.INSTANCE, source);
    }

    private static void mergeCompound(@NotNull net.minecraft.nbt.CompoundTag target, @NotNull CompoundTag source, @Nullable PDCMergeBlacklist blacklist) {
        for (Map.Entry<String, Tag> entry : source.entrySet()) {
            PDCMergeBlacklist child = blacklist == null ? null : blacklist.child(entry.getKey());
            if (child != null && child.terminal()) {
                continue;
            }
            target.put(entry.getKey(), mergeTag(target.get(entry.getKey()), entry.getValue(), child));
        }
    }
}
