package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.nbt.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一份玩家数据快照, 由元数据与各数据类型的 NBT 值组成, 数据体保持给定 Map 的迭代顺序.
 * 数据体包含本服未注册的类型时原样携带, 存档回写时原样带回.
 *
 * @param meta 快照元数据
 * @param data 各数据类型的值, <strong>快照持有的 Tag 视为不可变, 调用方不得修改</strong>
 */
public record Snapshot(@NotNull SnapshotMeta meta, @NotNull Map<DataKey, Tag> data) {

    // todo 复制是否过度防御? 我们目前代码是不暴露API的. API环节会专门复制, 如果我们内部没有发生修改, 我觉得这里不用兜底.
    public Snapshot {
        data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    @Nullable
    public Tag data(@NotNull DataKey key) {
        return this.data.get(key);
    }
}
