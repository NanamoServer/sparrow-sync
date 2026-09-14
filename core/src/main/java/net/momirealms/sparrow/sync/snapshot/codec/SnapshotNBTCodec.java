package net.momirealms.sparrow.sync.snapshot.codec;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.model.EagerSnapshotData;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 快照与完整 NBT 树互转, 供 JSON 编解码使用.
 */
final class SnapshotNBTCodec {
    static final String FIELD_DATA = "data";       // 完整快照的数据字段, 独立元数据不包含此字段

    private SnapshotNBTCodec() {
    }

    /** 将快照转为包含元数据和 data 的 CompoundTag. */
    @NotNull
    static CompoundTag toCompoundTag(@NotNull Snapshot snapshot) {
        CompoundTag root = SnapshotMetaCodec.toCompoundTag(snapshot.meta());
        CompoundTag data = NBT.createCompound(new LinkedHashMap<>());
        for (DataKey key : snapshot.keys()) {
            data.put(key.asString(), snapshot.data(key));
        }
        root.put(FIELD_DATA, data);
        return root;
    }

    /**
     * 从完整 NBT 树读取快照, 类型数据直接保存为 Tag.
     * @throws IOException 缺少玩家 UUID 或快照 ID 时
     */
    @NotNull
    static Snapshot fromCompoundTag(@NotNull CompoundTag root) throws IOException {
        SnapshotMeta meta = SnapshotMetaCodec.fromCompoundTag(root);
        Map<DataKey, Tag> data = new LinkedHashMap<>();
        CompoundTag values = root.getCompound(FIELD_DATA, null);
        if (values != null) {
            for (Map.Entry<String, Tag> entry : values.entrySet()) {
                data.put(DataKey.parse(entry.getKey()), entry.getValue());
            }
        }
        return new Snapshot(meta, new EagerSnapshotData(data));
    }

}
