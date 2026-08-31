package net.momirealms.sparrow.sync.snapshot.codec.upgrade;

import net.momirealms.sparrow.nbt.CompoundTag;
import org.bson.Document;
import org.jetbrains.annotations.NotNull;

public interface SnapshotUpgrade {

    /**
     * 本步升级到的格式版本, 取值从 2 起.
     */
    int targetVersion();

    /**
     * 就地改写二进制形态的快照树.
     * 入参来自刚解帧的新对象, 允许直接修改.
     */
    @NotNull
    CompoundTag upgrade(@NotNull CompoundTag root);

    /**
     * 改写文档形态的快照.
     * <strong>不得修改入参</strong>, 返回改写后的副本.
     */
    @NotNull
    Document upgrade(@NotNull Document document);
}
