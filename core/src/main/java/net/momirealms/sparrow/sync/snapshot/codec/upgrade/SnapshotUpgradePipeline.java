package net.momirealms.sparrow.sync.snapshot.codec.upgrade;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotCodec;
import org.bson.Document;
import org.jetbrains.annotations.NotNull;

public final class SnapshotUpgradePipeline {
    // 按目标版本索引, 0 和 1 不使用
    private static final SnapshotUpgrade[] BY_TARGET = indexByTarget();

    private SnapshotUpgradePipeline() {
    }

    /**
     * 将 NBT 快照升级到当前格式.
     * @param fromVersion 原格式版本, 必须不小于 1
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
     * 将文档快照升级到当前格式.
     * @param fromVersion 原格式版本, 必须不小于 1
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

    // 按目标版本登记升级步骤, 同一版本只能注册一次
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
