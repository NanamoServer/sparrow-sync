package net.momirealms.sparrow.sync.snapshot.data;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

@ApiStatus.Internal
public final class SnapshotDecoder {
    private final DataRegistry registry;

    public SnapshotDecoder(@NotNull DataRegistry registry) {
        this.registry = registry;
    }

    /**
     * 解码所有已注册类型, 关键类型失败时停止读取后续槽位.
     *
     * @param snapshot 已完成本服地图准备的快照
     * @return 运行时值、未知类型原数据及失败信息
     */
    @NotNull
    public DecodedSnapshotData decodeForApply(@NotNull Snapshot snapshot) {
        return this.decode(snapshot, type -> true, true);
    }

    /**
     * 解码展示需要的类型, 单类内容异常记录后继续读取其余选择项.
     *
     * @param snapshot 保留原始内容的快照
     * @param selected 预览支持的类型, 未选择类型不会调用 decode
     * @return 当前请求独占的预览值及失败信息
     */
    @NotNull
    public DecodedSnapshotData decodeSelected(@NotNull Snapshot snapshot, @NotNull Predicate<PlayerDataType<?>> selected) {
        return this.decode(snapshot, selected, false);
    }

    // 按注册表确定的顺序读取选中的类型并转换为玩家数据对象; 数据块读取失败和类型转换失败都记在该类型的结果中.
    @NotNull
    private DecodedSnapshotData decode(@NotNull Snapshot snapshot, @NotNull Predicate<PlayerDataType<?>> selected, boolean applying) {
        Map<DataKey, Tag> passthrough = null;
        // 应用快照时先读取未注册类型的 Tag, 交给会话在后续保存时保留; 预览无需读取它们, 原快照仍持有这些数据.
        if (applying) {
            for (DataKey key : snapshot.keys()) {
                if (this.registry.slot(key) < 0) {
                    if (passthrough == null) {
                        passthrough = new LinkedHashMap<>();
                    }
                    passthrough.put(key, snapshot.data(key));
                }
            }
        }
        DecodedSnapshotData result = new DecodedSnapshotData(this.registry, passthrough == null ? Map.of() : passthrough);
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
                result.values[i] = type.decode(tag, snapshot.meta().mcDataVersion());
            } catch (Throwable failure) {
                // 预览只把 IOException 和 RuntimeException 记为该类型的读取失败, Error 继续向外抛出.
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
