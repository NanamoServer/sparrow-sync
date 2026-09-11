package net.momirealms.sparrow.sync.snapshot.codec.upgrade;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotCodec;
import org.bson.Document;
import org.jetbrains.annotations.NotNull;

public final class SnapshotUpgradePipeline {
    // 下标即 targetVersion, 下标 0 与 1 恒为 null
    private static final SnapshotUpgrade[] BY_TARGET = indexByTarget();

    private SnapshotUpgradePipeline() {
    }

    /**
     * 把二进制形态的快照树升到当前布局.
     *
     * @param root 已读取的快照树
     * @param fromVersion 快照自带的格式版本, 必须不小于 1.
     */
    @NotNull
    public static CompoundTag upgrade(@NotNull CompoundTag root, int fromVersion) {
        if (fromVersion >= SnapshotCodec.CURRENT_VERSION) return root;
        CompoundTag current = root;
        for (int target = fromVersion + 1; target <= SnapshotCodec.CURRENT_VERSION; target++) {
            current = BY_TARGET[target].upgrade(current);
        }
        return current;
    }

    /**
     * 把文档形态的快照升到当前布局.
     *
     * @param document 已读取的文档
     * @param fromVersion 快照自带的格式版本, 必须不小于 1.
     */
    @NotNull
    public static Document upgrade(@NotNull Document document, int fromVersion) {
        if (fromVersion >= SnapshotCodec.CURRENT_VERSION) return document;
        Document current = document;
        for (int target = fromVersion + 1; target <= SnapshotCodec.CURRENT_VERSION; target++) {
            current = BY_TARGET[target].upgrade(current);
        }
        return current;
    }

    // 按目标版本建立直接索引, 并检查每个升级步恰好注册一次.
    @NotNull
    private static SnapshotUpgrade[] indexByTarget(SnapshotUpgrade... registered) {
        SnapshotUpgrade[] byTarget = new SnapshotUpgrade[SnapshotCodec.CURRENT_VERSION + 1];
        for (int i = 0; i < registered.length; i++) {
            SnapshotUpgrade upgrade = registered[i];
            int target = upgrade.targetVersion();
            if (target < 2 || target > SnapshotCodec.CURRENT_VERSION) {
                throw new IllegalStateException(upgrade.getClass().getSimpleName() + " targets snapshot format " + target
                        + ", outside the 2.." + SnapshotCodec.CURRENT_VERSION + " range this plugin can read");
            }
            if (byTarget[target] != null) {
                throw new IllegalStateException("two snapshot upgrades target format " + target + ": "
                        + byTarget[target].getClass().getSimpleName() + " and " + upgrade.getClass().getSimpleName());
            }
            byTarget[target] = upgrade;
        }
        for (int target = 2; target <= SnapshotCodec.CURRENT_VERSION; target++) {
            if (byTarget[target] == null) {
                throw new IllegalStateException("no snapshot upgrade targets format " + target
                        + ", every step up to " + SnapshotCodec.CURRENT_VERSION + " needs one");
            }
        }
        return byTarget;
    }
}
