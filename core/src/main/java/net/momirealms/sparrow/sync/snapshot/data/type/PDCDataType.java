package net.momirealms.sparrow.sync.snapshot.data.type;

import net.minecraft.nbt.NbtOps;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig.PDCMergeBlacklist;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.proxy.minecraft.nbt.CompoundTagProxy;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

public final class PDCDataType implements NativePlayerDataType<net.minecraft.nbt.CompoundTag> {
    public static final DataKey PERSISTENT_DATA = DataKey.sparrow("persistent_data");

    @Override
    @NotNull
    public DataKey key() {
        return PERSISTENT_DATA;
    }

    @Override
    public boolean critical() {
        return true;
    }

    @Override
    @NotNull
    public net.minecraft.nbt.CompoundTag capture(@NotNull Player player, @NotNull CaptureMode mode) {
        Map<String, net.minecraft.nbt.Tag> raw = ((CraftPlayer) player).getPersistentDataContainer().getRaw();
        net.minecraft.nbt.CompoundTag captured = new net.minecraft.nbt.CompoundTag();
        for (Map.Entry<String, net.minecraft.nbt.Tag> entry : raw.entrySet()) {
            // 退出后只复制根结构, 子 Tag 在最终保存编码结束前不再变化
            captured.put(entry.getKey(), mode == CaptureMode.OFFLINE ? entry.getValue() : entry.getValue().copy());
        }
        return captured;
    }

    @Override
    @NotNull
    public Tag encode(@NotNull net.minecraft.nbt.CompoundTag value) {
        return encodeCompound(entries(value), PluginConfig.synchronization$pdcMergeNamespaces());
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
            return encodeCompound(entries(compound), blacklist);
        }
        return NbtOps.INSTANCE.convertTo(NBTOps.INSTANCE, value);
    }

    @Override
    @NotNull
    public net.minecraft.nbt.CompoundTag decode(@NotNull Tag data) throws IOException {
        if (!(data instanceof CompoundTag compound)) {
            throw new IOException("persistent data is not a compound");
        }
        return (net.minecraft.nbt.CompoundTag) NBTOps.INSTANCE.convertTo(NbtOps.INSTANCE, compound);
    }

    @Override
    public void apply(@NotNull Player player, @NotNull net.minecraft.nbt.CompoundTag value) {
        Map<String, net.minecraft.nbt.Tag> target = ((CraftPlayer) player).getPersistentDataContainer().getRaw();
        PDCMergeBlacklist blacklist = PluginConfig.synchronization$pdcMergeNamespaces();

        for (Map.Entry<String, net.minecraft.nbt.Tag> entry : entries(value)) {
            PDCMergeBlacklist child = blacklist.child(entry.getKey());
            if (child != null && child.terminal()) {
                continue;
            }
            target.put(entry.getKey(), mergeTag(target.get(entry.getKey()), entry.getValue(), child));
        }
    }

    @Override
    public boolean shouldApply(@NotNull PlayerSession session) {
        return PluginConfig.synchronization$nativeAsyncApply().playerData();
    }

    @Override
    @NotNull
    public NativeApplyResult applyNative(@NotNull PlayerSession session, @NotNull CompoundTag playerData, @NotNull net.minecraft.nbt.CompoundTag value) {
        Tag current = playerData.get("BukkitValues");
        net.minecraft.nbt.CompoundTag merged = current instanceof CompoundTag compound
                ? (net.minecraft.nbt.CompoundTag) NBTOps.INSTANCE.convertTo(NbtOps.INSTANCE, compound)
                : new net.minecraft.nbt.CompoundTag();
        mergeCompound(merged, value, PluginConfig.synchronization$pdcMergeNamespaces());
        // 子树合并成功后再替换根节点, 合并失败时保留原本地数据
        playerData.put("BukkitValues", NbtOps.INSTANCE.convertTo(NBTOps.INSTANCE, merged));
        return NativeApplyResult.APPLIED_PLAYER_DATA;
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
        // 含黑名单后代的本地 Compound 遇到类型冲突时保留原值
        if (blacklist != null && blacklist.hasChildren() && current instanceof net.minecraft.nbt.CompoundTag) {
            return current;
        }
        return source.copy();
    }

    private static void mergeCompound(@NotNull net.minecraft.nbt.CompoundTag target, @NotNull net.minecraft.nbt.CompoundTag source, @Nullable PDCMergeBlacklist blacklist) {
        for (Map.Entry<String, net.minecraft.nbt.Tag> entry : entries(source)) {
            PDCMergeBlacklist child = blacklist == null ? null : blacklist.child(entry.getKey());
            if (child != null && child.terminal()) {
                continue;
            }
            target.put(entry.getKey(), mergeTag(target.get(entry.getKey()), entry.getValue(), child));
        }
    }

    // CompoundTag.entrySet() 在 1.21.4 上还是 protected, 直接读内部 map 才能跨版本用
    @NotNull
    private static Set<Map.Entry<String, net.minecraft.nbt.Tag>> entries(@NotNull net.minecraft.nbt.CompoundTag tag) {
        return CompoundTagProxy.INSTANCE.getTags(tag).entrySet();
    }
}
