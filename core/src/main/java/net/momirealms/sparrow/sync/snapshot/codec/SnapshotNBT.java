package net.momirealms.sparrow.sync.snapshot.codec;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 快照元数据与完整 NBT 树的字段映射, 供 JSON 和二进制容器共用.
 * id 与 player 必填, 其余元数据按既有缺省值读取; 完整树额外包含以 DataKey 为键的 data.
 */
final class SnapshotNBT {
    static final String FIELD_ID = "id";           // 快照自身的 UUID
    static final String FIELD_PLAYER = "player";   // 玩家 UUID
    static final String FIELD_TIMESTAMP = "ts";    // 逻辑保存时间, 毫秒
    static final String FIELD_CAUSE = "cause";     // 保存原因的枚举名
    static final String FIELD_PINNED = "pinned";    // 是否排除在自动轮转之外
    static final String FIELD_SERVER = "server";   // 采集服务器标识
    static final String FIELD_MC_DATA = "mcData";  // Minecraft 数据版本, 用于内容升级
    static final String FIELD_DATA = "data";       // 完整树中的数据体, 元数据段省略此键

    private SnapshotNBT() {
    }

    /**
     * 将完整快照展开为树.
     *
     * @param snapshot 待展开的快照
     * @return 元数据与 data 共存的 compound
     */
    @NotNull
    static CompoundTag toTagTree(@NotNull Snapshot snapshot) {
        CompoundTag root = toMetaTree(snapshot.meta());
        CompoundTag data = NBT.createCompound();
        for (Map.Entry<DataKey, Tag> entry : snapshot.allData().entrySet()) {
            data.put(entry.getKey().asString(), entry.getValue());
        }
        root.put(FIELD_DATA, data);
        return root;
    }

    /**
     * 将七个元数据字段写入独立树, 供二进制容器序列化 meta 段.
     *
     * @param meta 快照元数据
     * @return 不含数据体的 compound
     */
    @NotNull
    static CompoundTag toMetaTree(@NotNull SnapshotMeta meta) {
        CompoundTag root = NBT.createCompound();
        root.putUUID(FIELD_PLAYER, meta.player());
        root.putUUID(FIELD_ID, meta.id());
        root.putLong(FIELD_TIMESTAMP, meta.timestamp());
        root.putString(FIELD_CAUSE, meta.cause().name());
        root.putBoolean(FIELD_PINNED, meta.pinned());
        root.putString(FIELD_SERVER, meta.server());
        root.putInt(FIELD_MC_DATA, meta.mcDataVersion());
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
    static Snapshot fromTagTree(@NotNull CompoundTag root) throws IOException {
        SnapshotMeta meta = fromMetaTree(root);
        Map<DataKey, Tag> data = new LinkedHashMap<>();
        CompoundTag values = root.getCompound(FIELD_DATA, null);
        if (values != null) {
            for (Map.Entry<String, Tag> entry : values.entrySet()) {
                data.put(DataKey.parse(entry.getKey()), entry.getValue());
            }
        }
        return new Snapshot(meta, data);
    }

    /**
     * 读取身份与保存信息.
     *
     * @param root 元数据树, 也可以是包含 data 的完整树
     * @return 可供列表展示及快照构造使用的元数据
     * @throws IOException 当 id 或 player 缺失, 或无法读取为 UUID 时
     */
    @NotNull
    static SnapshotMeta fromMetaTree(@NotNull CompoundTag root) throws IOException {
        // 身份用于确定快照归属, 必须同时存在;
        UUID player = root.getUUID(FIELD_PLAYER, null);
        if (player == null) throw new IOException("missing player uuid");
        UUID id = root.getUUID(FIELD_ID, null);
        if (id == null) throw new IOException("missing snapshot id");
        return new SnapshotMeta(
                id,
                player,
                root.getLong(FIELD_TIMESTAMP),
                SaveCause.byName(root.getString(FIELD_CAUSE, "")),
                root.getBoolean(FIELD_PINNED),
                root.getString(FIELD_SERVER, ""),
                root.getInt(FIELD_MC_DATA)
        );
    }
}
