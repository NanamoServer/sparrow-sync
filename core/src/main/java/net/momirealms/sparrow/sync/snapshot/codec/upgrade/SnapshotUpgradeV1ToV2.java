package net.momirealms.sparrow.sync.snapshot.codec.upgrade;

import net.momirealms.sparrow.nbt.CompoundTag;
import org.bson.Document;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * format 1 -> 2: 元数据里的 version(long) 被 id(UUID) 取代, 快照的新旧改由 timestamp 裁决.
 * <p>v1 的快照没有身份, 这里按 (player, version) 派生一个稳定 id, 遗留的 version 字段原样留着, 解码器只读它认识的键.
 */
public final class SnapshotUpgradeV1ToV2 implements SnapshotUpgrade {
    private static final String V1_PLAYER = "player";
    private static final String V1_VERSION = "version";
    private static final String V2_ID_TAG = "id";
    private static final String V2_ID_DOCUMENT = "_id";

    @Override
    public int targetVersion() {
        return 2;
    }

    @Override
    @NotNull
    public CompoundTag upgrade(@NotNull CompoundTag root) {
        UUID player = root.getUUID(V1_PLAYER, null);
        if (player == null) return root;
        root.putUUID(V2_ID_TAG, deriveId(player, root.getLong(V1_VERSION)));
        return root;
    }

    @Override
    @NotNull
    public Document upgrade(@NotNull Document document) {
        if (!(document.get(V1_PLAYER) instanceof UUID player)) return document;
        long version = document.get(V1_VERSION) instanceof Number number ? number.longValue() : 0L;
        Document upgraded = new Document(document);
        // v1 从没写过 _id, 里面是驱动自动生成的 ObjectId, 作为快照身份没有意义, 无条件盖掉
        upgraded.put(V2_ID_DOCUMENT, deriveId(player, version));
        return upgraded;
    }

    private static UUID deriveId(UUID player, long version) {
        return UUID.nameUUIDFromBytes(("sparrow-sync:v1:" + player + ":" + version).getBytes(StandardCharsets.UTF_8));
    }
}
