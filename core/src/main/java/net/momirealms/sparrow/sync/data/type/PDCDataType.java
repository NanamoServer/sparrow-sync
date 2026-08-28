package net.momirealms.sparrow.sync.data.type;

import net.minecraft.nbt.NbtOps;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.data.PlayerDataType;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * PDC 同步: 采集整块容器入快照; 应用时按策略写回, 白名单为空则全量替换,
 * 否则只替换白名单命名空间下的键, 其余命名空间保留本服现状.
 */
public final class PDCDataType implements PlayerDataType<CompoundTag> {
    public static final DataKey PERSISTENT_DATA = DataKey.sparrow("persistent_data");

    private final Set<String> mergeNamespaces;

    public PDCDataType(@NotNull Set<String> mergeNamespaces) {
        this.mergeNamespaces = Set.copyOf(mergeNamespaces);
    }

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
    public Tag capture(@NotNull Player player) {
        PlayerDataType.ensureOwningThread(player);
        CompoundTag snapshot = NBT.createCompound();
        for (Map.Entry<String, net.minecraft.nbt.Tag> entry : rawContainer(player).entrySet()) {
            snapshot.put(entry.getKey(), NbtOps.INSTANCE.convertTo(NBTOps.INSTANCE, entry.getValue()));
        }
        return snapshot;
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
        PlayerDataType.ensureOwningThread(player);
        Map<String, net.minecraft.nbt.Tag> raw = rawContainer(player);
        if (this.mergeNamespaces.isEmpty()) {
            raw.clear();
            for (Map.Entry<String, Tag> entry : value.entrySet()) {
                raw.put(entry.getKey(), NBTOps.INSTANCE.convertTo(NbtOps.INSTANCE, entry.getValue()));
            }
            return;
        }
        // 白名单合并: 先清掉本服白名单命名空间的键, 再写入快照中同命名空间的键
        raw.keySet().removeIf(this::inMergeNamespaces);
        for (Map.Entry<String, Tag> entry : value.entrySet()) {
            if (!this.inMergeNamespaces(entry.getKey())) continue;
            raw.put(entry.getKey(), NBTOps.INSTANCE.convertTo(NbtOps.INSTANCE, entry.getValue()));
        }
    }

    private boolean inMergeNamespaces(String key) {
        int separator = key.indexOf(':');
        if (separator < 0) return false;
        return this.mergeNamespaces.contains(key.substring(0, separator));
    }

    private static Map<String, net.minecraft.nbt.Tag> rawContainer(Player player) {
        return ((CraftPlayer) player).getPersistentDataContainer().getRaw();
    }
}
