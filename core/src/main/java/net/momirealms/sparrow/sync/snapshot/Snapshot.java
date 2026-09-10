package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.nbt.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;

/**
 * 一份玩家数据快照, 由元数据与各数据类型的 NBT 值组成, 数据体保持给定 Map 的迭代顺序.
 * 数据体包含本服未注册的类型时原样携带, 存档回写时原样带回.
 *
 * @param meta 快照元数据
 * @param data 各数据类型的值, <strong>快照直接持有传入的 Map, 交付后调用方不得再修改它或其中的 Tag</strong>
 */
public record Snapshot(@NotNull SnapshotMeta meta, @NotNull Map<DataKey, Tag> data) {

    public Snapshot {
        data = Collections.unmodifiableMap(data);
    }

    @Nullable
    public Tag data(@NotNull DataKey key) {
        return this.data.get(key);
    }
}
