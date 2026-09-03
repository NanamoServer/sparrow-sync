package net.momirealms.sparrow.sync.snapshot.data.type;

import net.minecraft.nbt.NbtOps;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig.PDCMergeBlacklist;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Map;

/**
 * PDC 同步会把快照数据递归合入本服 {@code custom_data}, 黑名单路径留在各服务器本地.
 * 玩家线程只深拷贝原版 CompoundTag, 黑名单过滤与 Sparrow NBT 转换由编码阶段完成.
 * 黑名单子树不会进入新快照, 也不会从已有快照写回.
 */
public final class PDCDataType implements NativePlayerDataType<net.minecraft.nbt.CompoundTag> {
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
    public net.minecraft.nbt.CompoundTag capture(@NotNull Player player) {
        Map<String, net.minecraft.nbt.Tag> raw = ((CraftPlayer) player).getPersistentDataContainer().getRaw();
        net.minecraft.nbt.CompoundTag captured = new net.minecraft.nbt.CompoundTag();
        for (Map.Entry<String, net.minecraft.nbt.Tag> entry : raw.entrySet()) {
            captured.put(entry.getKey(), entry.getValue().copy());
        }
        return captured;
    }

    @Override
    @NotNull
    public Tag encode(@NotNull net.minecraft.nbt.CompoundTag value) {
        return encodeCompound(value.entrySet(), PluginConfig.synchronization$pdcMergeNamespaces());
    }

    @NotNull
    private static CompoundTag encodeCompound(@NotNull Iterable<Map.Entry<String, net.minecraft.nbt.Tag>> entries, @NotNull PDCMergeBlacklist blacklist) {
        CompoundTag snapshot = NBT.createCompound();
        for (Map.Entry<String, net.minecraft.nbt.Tag> entry : entries) {
            PDCMergeBlacklist child = blacklist.child(entry.getKey());
            if (child != null && child.terminal()) {
                continue;
            }
            snapshot.put(entry.getKey(), encodeTag(entry.getValue(), child));
        }
        return snapshot;
    }

    @NotNull
    private static Tag encodeTag(@NotNull net.minecraft.nbt.Tag value, @Nullable PDCMergeBlacklist blacklist) {
        if (blacklist != null && blacklist.hasChildren() && value instanceof net.minecraft.nbt.CompoundTag compound) {
            return encodeCompound(compound.entrySet(), blacklist);
        }
        return NbtOps.INSTANCE.convertTo(NBTOps.INSTANCE, value);
    }

    @Override
    @NotNull
    public net.minecraft.nbt.CompoundTag decode(@NotNull Tag data, int mcDataVersion) throws IOException {
        if (!(data instanceof CompoundTag compound)) {
            throw new IOException("persistent data is not a compound");
        }
        return (net.minecraft.nbt.CompoundTag) NBTOps.INSTANCE.convertTo(NbtOps.INSTANCE, compound);
    }

    @Override
    public void apply(@NotNull Player player, @NotNull net.minecraft.nbt.CompoundTag value) {
        Map<String, net.minecraft.nbt.Tag> target = ((CraftPlayer) player).getPersistentDataContainer().getRaw();
        PDCMergeBlacklist blacklist = PluginConfig.synchronization$pdcMergeNamespaces();

        for (Map.Entry<String, net.minecraft.nbt.Tag> entry : value.entrySet()) {
            PDCMergeBlacklist child = blacklist.child(entry.getKey());
            if (child != null && child.terminal()) {
                continue;
            }
            target.put(entry.getKey(), mergeTag(target.get(entry.getKey()), entry.getValue(), child));
        }
    }

    @Override
    public boolean applyNative(@NotNull net.minecraft.nbt.CompoundTag playerData, @NotNull net.minecraft.nbt.CompoundTag value) {
        net.minecraft.nbt.Tag current = playerData.get("BukkitValues");
        net.minecraft.nbt.CompoundTag merged = current instanceof net.minecraft.nbt.CompoundTag compound
                ? compound.copy()
                : new net.minecraft.nbt.CompoundTag();
        mergeCompound(merged, value, PluginConfig.synchronization$pdcMergeNamespaces());
        // 子树在副本中完成合并, 到这里才替换根节点, 合并异常不会污染尚未发布的本地数据.
        playerData.put("BukkitValues", merged);
        return true;
    }

    @NotNull
    private static net.minecraft.nbt.Tag mergeTag(@Nullable net.minecraft.nbt.Tag current, @NotNull net.minecraft.nbt.Tag source, @Nullable PDCMergeBlacklist blacklist) {
        if (source instanceof net.minecraft.nbt.CompoundTag sourceCompound) {
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
        return source.copy();
    }

    private static void mergeCompound(@NotNull net.minecraft.nbt.CompoundTag target, @NotNull net.minecraft.nbt.CompoundTag source, @Nullable PDCMergeBlacklist blacklist) {
        for (Map.Entry<String, net.minecraft.nbt.Tag> entry : source.entrySet()) {
            PDCMergeBlacklist child = blacklist == null ? null : blacklist.child(entry.getKey());
            if (child != null && child.terminal()) {
                continue;
            }
            target.put(entry.getKey(), mergeTag(target.get(entry.getKey()), entry.getValue(), child));
        }
    }
}
