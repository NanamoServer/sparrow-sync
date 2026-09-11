package net.momirealms.sparrow.sync.snapshot.data;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
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

    // 按注册表布局解码数据, 将场景差异限定在类型选择与失败处置.
    @NotNull
    private DecodedSnapshotData decode(@NotNull Snapshot snapshot, @NotNull Predicate<PlayerDataType<?>> selected, boolean applying) {
        Tag[] tags = new Tag[this.registry.size()];
        Map<DataKey, Tag> passthrough = null;
        for (Map.Entry<DataKey, Tag> entry : snapshot.allData().entrySet()) {
            int slot = this.registry.slot(entry.getKey());
            if (slot < 0) {
                if (passthrough == null) {
                    passthrough = new LinkedHashMap<>();
                }
                passthrough.put(entry.getKey(), entry.getValue());
            } else {
                tags[slot] = entry.getValue();
            }
        }
        DecodedSnapshotData result = new DecodedSnapshotData(this.registry, passthrough == null ? Map.of() : passthrough);
        for (int i = 0; i < tags.length; i++) {
            if (tags[i] == null) {
                continue;
            }
            PlayerDataType<?> type = this.registry.typeAt(i);
            if (!selected.test(type)) {
                continue;
            }
            try {
                result.values[i] = type.decode(tags[i], snapshot.meta().mcDataVersion());
            } catch (Throwable failure) {
                // 预览沿用内容异常的处理范围, 虚拟机等 Error 继续交给调用边界.
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
