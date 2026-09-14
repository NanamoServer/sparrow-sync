package net.momirealms.sparrow.sync.snapshot.data;

import net.momirealms.sparrow.sync.snapshot.model.SnapshotData;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Internal
public final class DecodedSnapshotData {
    final Object[] values; // 本次请求独占的解码值, 应用后逐槽释放
    private final DataRegistry registry;
    private final SnapshotData passthrough; // 未注册类型的临时视图, 交给应用流程前复制为独立数据帧
    private Throwable[] failures; // 首次失败时创建, 成功槽位为 null
    private int criticalFailure = -1; // 首个关键失败的槽位, -1 表示没有

    DecodedSnapshotData(@NotNull DataRegistry registry, @NotNull SnapshotData passthrough) {
        this.registry = registry;
        this.values = new Object[registry.size()];
        this.passthrough = passthrough;
    }

    @Nullable
    public Object value(@NotNull DataKey key) {
        int slot = this.registry.slot(key);
        return slot < 0 ? null : this.values[slot];
    }

    @Nullable
    public Throwable failure(@NotNull DataKey key) {
        int slot = this.registry.slot(key);
        return slot < 0 || this.failures == null ? null : this.failures[slot];
    }

    // 返回首个解码失败的关键类型
    @Nullable
    public DataKey criticalFailure() {
        return this.criticalFailure < 0 ? null : this.registry.keyAt(this.criticalFailure);
    }

    void failed(int slot, @NotNull Throwable failure, boolean critical) {
        if (this.failures == null) {
            this.failures = new Throwable[this.values.length];
        }
        this.failures[slot] = failure;
        if (critical) {
            this.criticalFailure = slot;
        }
    }

    // 返回未注册类型的视图, 供后续复制原始块
    @NotNull
    SnapshotData passthrough() {
        return this.passthrough;
    }

    // 将解码值和非关键失败交给应用流程, 随后由 Context 管理
    @NotNull
    SnapshotApplyContext intoApplyContext(@NotNull SnapshotData passthrough) {
        SnapshotApplyContext context = new SnapshotApplyContext(this.registry, passthrough, this.values);
        for (int i = 0; i < this.values.length; i++) {
            if (this.failures != null && this.failures[i] != null) {
                context.decodeSkipped(i, this.failures[i]);
            }
        }
        return context;
    }
}
