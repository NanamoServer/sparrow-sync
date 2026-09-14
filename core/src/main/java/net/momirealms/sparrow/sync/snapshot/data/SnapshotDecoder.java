package net.momirealms.sparrow.sync.snapshot.data;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.model.EagerSnapshotData;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotData;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.function.Predicate;

@ApiStatus.Internal
public final class SnapshotDecoder {
    private final DataRegistry registry;

    public SnapshotDecoder(@NotNull DataRegistry registry) {
        this.registry = registry;
    }

    /**
     * 按注册顺序解码类型, 关键类型失败时停止后续解码.
     * @param snapshot 已完成本服地图处理的快照
     */
    @NotNull
    public DecodedSnapshotData decodeForApply(@NotNull Snapshot snapshot) {
        return this.decode(snapshot, type -> true, true);
    }

    /** 解码所选预览类型, 单个类型失败不影响其余类型. */
    @NotNull
    public DecodedSnapshotData decodeSelected(@NotNull Snapshot snapshot, @NotNull Predicate<PlayerDataType<?>> selected) {
        return this.decode(snapshot, selected, false);
    }

    // 按注册顺序解码所选类型, 读取或转换失败都记入对应结果
    @NotNull
    private DecodedSnapshotData decode(@NotNull Snapshot snapshot, @NotNull Predicate<PlayerDataType<?>> selected, boolean applying) {
        // 保留未注册且不在丢弃名单中的类型, 后续复制为独立数据帧
        SnapshotData passthrough = applying
                ? snapshot.content().select(key -> this.registry.slot(key) < 0 && !this.registry.shouldDropUnknown(key))
                : EagerSnapshotData.EMPTY;
        DecodedSnapshotData result = new DecodedSnapshotData(this.registry, passthrough);
        for (int i = 0; i < this.registry.size(); i++) {
            DataKey key = this.registry.keyAt(i);
            if (!snapshot.keys().contains(key)) {
                continue;
            }
            PlayerDataType<?> type = this.registry.typeAt(i);
            if (!selected.test(type)) {
                continue;
            }
            try {
                Tag tag = snapshot.data(key);
                if (tag == null) continue;
                result.values[i] = type.decode(tag);
            } catch (Throwable failure) {
                // 预览记录 IOException 和 RuntimeException, Error 继续抛出
                if (!applying && !(failure instanceof IOException || failure instanceof RuntimeException)) {
                    throw (Error) failure;
                }
                boolean critical = applying && type.critical();
                result.failed(i, failure, critical);
                if (critical) return result;
            }
        }
        return result;
    }
}
