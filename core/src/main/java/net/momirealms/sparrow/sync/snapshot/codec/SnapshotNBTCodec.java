package net.momirealms.sparrow.sync.snapshot.codec;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.codec.block.BlockMetaCodec;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.model.EagerSnapshotData;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotBlock;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 快照与完整 NBT 树的字段映射, 供 JSON 的类型内容转换使用.
 * id 与 player 必填, 其余元数据按既有缺省值读取; 完整树额外包含以 DataKey 为键的 data.
 */
final class SnapshotNBTCodec {
    static final String FIELD_DATA = "data";       // 完整树中的数据体, 元数据段省略此键
    static final String FIELD_BLOCK_META = "meta"; // 类型块附加信息

    private SnapshotNBTCodec() {
    }

    /**
     * 将完整快照展开为树.
     *
     * @param snapshot 待展开的快照
     * @return 元数据与 data 共存的 compound
     */
    @NotNull
    static CompoundTag toCompoundTag(@NotNull Snapshot snapshot) {
        CompoundTag root = SnapshotMetaCodec.toCompoundTag(snapshot.meta());
        CompoundTag data = NBT.createCompound(new LinkedHashMap<>());
        for (DataKey key : snapshot.keys()) {
            SnapshotBlock block = snapshot.content().block(key);
            assert block != null;
            CompoundTag value = NBT.createCompound();
            value.put(FIELD_BLOCK_META, BlockMetaCodec.write(block.meta()));
            value.put(FIELD_DATA, block.data());
            data.put(key.asString(), value);
        }
        root.put(FIELD_DATA, data);
        return root;
    }

    /**
     * 将 JSON 等载体提供的完整树还原为已经展开的数据体.
     *
     * @param root 同时包含元数据与可选 data 的根
     * @return 使用 EagerSnapshotData 的快照
     * @throws IOException 当玩家或快照身份缺失时
     */
    @NotNull
    static Snapshot fromCompoundTag(@NotNull CompoundTag root) throws IOException {
        SnapshotMeta meta = SnapshotMetaCodec.fromCompoundTag(root);
        Map<DataKey, SnapshotBlock> data = new LinkedHashMap<>();
        CompoundTag values = root.getCompound(FIELD_DATA, null);
        if (values != null) {
            for (Map.Entry<String, Tag> entry : values.entrySet()) {
                if (!(entry.getValue() instanceof CompoundTag block)
                        || !(block.get(FIELD_BLOCK_META) instanceof CompoundTag blockMeta) || !block.containsKey(FIELD_DATA)) {
                    throw new IOException("data block '" + entry.getKey() + "' must contain meta and data");
                }
                data.put(DataKey.parse(entry.getKey()), new SnapshotBlock(BlockMetaCodec.read(blockMeta), block.get(FIELD_DATA)));
            }
        }
        return new Snapshot(meta, new EagerSnapshotData(data));
    }

}
