package net.momirealms.sparrow.sync.snapshot.codec.upgrade;

import net.momirealms.sparrow.nbt.CompoundTag;
import org.bson.Document;
import org.jetbrains.annotations.NotNull;

public interface SnapshotUpgrade {

    /**
     * 本步升级到的格式版本, 取值从 2 起.
     */
    int targetVersion();

    /** 直接修改刚解码的 NBT 快照. */
    @NotNull
    CompoundTag upgrade(@NotNull CompoundTag root);

    /** 返回升级后的文档副本, <strong>不得修改输入文档</strong>. */
    @NotNull
    Document upgrade(@NotNull Document document);
}
