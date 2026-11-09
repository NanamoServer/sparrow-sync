package net.momirealms.sparrow.sync.snapshot.data.type;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig.PDCMergeBlacklist;
import net.momirealms.sparrow.sync.proxy.minecraft.nbt.CompoundTagProxy;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Map;

public final class PDCDataType implements NativePlayerDataType<CompoundTag> {
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
    public CompoundTag capture(@NotNull Player player, @NotNull CaptureMode mode) {
        Map<String, Tag> raw = ((CraftPlayer) player).getPersistentDataContainer().getRaw();
        CompoundTag captured = new CompoundTag();
        for (Map.Entry<String, Tag> entry : raw.entrySet()) {
            // 在线采集复制子 Tag, 后续玩家修改与本次快照隔离.
            captured.put(entry.getKey(), mode == CaptureMode.OFFLINE ? entry.getValue() : entry.getValue().copy());
        }
        return captured;
    }

    @Override
    @NotNull
    public net.momirealms.sparrow.nbt.Tag encode(@NotNull CompoundTag value) {
        return encodeCompound(value, PluginConfig.synchronization$pdcMergeNamespaces());
    }

    // 按当前层级的黑名单生成独立快照, 采集结果保持原样.
    @NotNull
    private static net.momirealms.sparrow.nbt.CompoundTag encodeCompound(@NotNull CompoundTag value, @NotNull PDCMergeBlacklist blacklist) {
        net.momirealms.sparrow.nbt.CompoundTag snapshot = NBT.createCompound();
        for (Map.Entry<String, Tag> entry : tags(value).entrySet()) {
            PDCMergeBlacklist child = blacklist.child(entry.getKey());
            if (child != null && child.terminal()) continue;

            snapshot.put(entry.getKey(), encodeTag(entry.getValue(), child));
        }
        return snapshot;
    }

    @NotNull
    private static net.momirealms.sparrow.nbt.Tag encodeTag(@NotNull Tag value, @Nullable PDCMergeBlacklist blacklist) {
        // 存在更深的黑名单路径时逐层过滤, 其余子树直接转换.
        if (blacklist != null && blacklist.hasChildren() && value instanceof CompoundTag compound) {
            return encodeCompound(compound, blacklist);
        }
        return NbtOps.INSTANCE.convertTo(NBTOps.INSTANCE, value);
    }

    @NotNull
    @Override
    public CompoundTag decode(@NotNull net.momirealms.sparrow.nbt.Tag data) throws IOException {
        if (!(data instanceof net.momirealms.sparrow.nbt.CompoundTag compound)) {
            throw new IOException("persistent data is not a compound");
        }
        return (CompoundTag) NBTOps.INSTANCE.convertTo(NbtOps.INSTANCE, compound);
    }

    @Override
    public void apply(@NotNull Player player, @NotNull CompoundTag value) {
        Map<String, Tag> target = ((CraftPlayer) player).getPersistentDataContainer().getRaw();
        applySnapshot(target, tags(value), PluginConfig.synchronization$pdcMergeNamespaces());
    }

    @Override
    public boolean shouldApply(@NotNull PlayerSession session) {
        return PluginConfig.synchronization$nativeAsyncApply().playerData();
    }

    @NotNull
    @Override
    public NativeApplyResult applyNative(@NotNull PlayerSession session, @NotNull net.momirealms.sparrow.nbt.CompoundTag playerData, @NotNull CompoundTag value) {
        net.momirealms.sparrow.nbt.Tag current = playerData.get("BukkitValues");
        CompoundTag target = current instanceof net.momirealms.sparrow.nbt.CompoundTag compound
                ? (CompoundTag) NBTOps.INSTANCE.convertTo(NbtOps.INSTANCE, compound)
                : new CompoundTag();
        applySnapshot(tags(target), tags(value), PluginConfig.synchronization$pdcMergeNamespaces());
        if (target.isEmpty()) {
            if (current == null) return NativeApplyResult.NOT_APPLIED;
            playerData.remove("BukkitValues");
            return NativeApplyResult.APPLIED_PLAYER_DATA;
        }
        // 完成整个 PDC 的同步后再写回玩家数据.
        playerData.put("BukkitValues", NbtOps.INSTANCE.convertTo(NBTOps.INSTANCE, target));
        return NativeApplyResult.APPLIED_PLAYER_DATA;
    }

    // 原地更新本地容器, source 始终作为只读快照使用.
    private static void applySnapshot(@NotNull Map<String, Tag> target, @NotNull Map<String, Tag> source, @Nullable PDCMergeBlacklist blacklist) {
        // 快照缺失的分支只保留本地黑名单数据及其父级结构.
        target.entrySet().removeIf(entry ->
                !source.containsKey(entry.getKey()) && !pruneToBlacklist(entry.getValue(), blacklist == null ? null : blacklist.child(entry.getKey()))
        );
        // 黑名单项沿用本地状态, 包括本地尚不存在该项的情况.
        for (Map.Entry<String, Tag> entry : source.entrySet()) {
            PDCMergeBlacklist child = blacklist == null ? null : blacklist.child(entry.getKey());
            if (child != null && child.terminal()) continue;

            target.put(entry.getKey(), applySnapshotTag(target.get(entry.getKey()), entry.getValue(), child));
        }
    }

    // 复用本地 Compound 承接递归更新, 返回可写入父级的值.
    @NotNull
    private static Tag applySnapshotTag(@Nullable Tag current, @NotNull Tag source, @Nullable PDCMergeBlacklist blacklist) {
        if (source instanceof CompoundTag sourceCompound) {
            CompoundTag targetCompound = current instanceof CompoundTag compound
                    ? compound
                    : new CompoundTag();
            applySnapshot(tags(targetCompound), tags(sourceCompound), blacklist);
            return targetCompound;
        }
        // 类型冲突时保留黑名单数据及其父级结构.
        if (current != null && pruneToBlacklist(current, blacklist)) return current;
        // 可变 Tag 与快照保持独立, 玩家后续修改仅作用于本地副本.
        return source.copy();
    }

    // 原地裁剪到黑名单覆盖的内容, 返回值表示父级是否应保留当前节点.
    private static boolean pruneToBlacklist(@NotNull Tag current, @Nullable PDCMergeBlacklist blacklist) {
        if (blacklist == null) return false;
        if (blacklist.terminal()) return true;
        if (!(current instanceof CompoundTag compound)) return false;

        // 未命中整项黑名单的 Compound 仅作为剩余黑名单后代的父级保留.
        tags(compound).entrySet().removeIf(entry -> !pruneToBlacklist(entry.getValue(), blacklist.child(entry.getKey())));
        return !compound.isEmpty();
    }

    // CompoundTag.entrySet() 在 1.21.4 上还是 protected, 直接读内部 map 才能跨版本用.
    @NotNull
    private static Map<String, Tag> tags(@NotNull CompoundTag tag) {
        return CompoundTagProxy.INSTANCE.getTags(tag);
    }
}
