package net.momirealms.sparrow.sync.snapshot.data;

import net.momirealms.sparrow.nbt.Tag;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

@ApiStatus.Internal
public final class DecodedSnapshotData {
    final Object[] values; // 当前请求独占的值缓冲, 应用阶段接管后可以逐槽释放
    private final DataRegistry registry;
    private final Map<DataKey, Tag> passthrough; // 本服未注册类型的原始数据
    private Throwable[] failures; // 首次解码失败时分配, 成功槽位保持 null
    private int criticalFailure = -1; // 正式加载停止的位置, -1 表示没有关键失败

    DecodedSnapshotData(@NotNull DataRegistry registry, @NotNull Map<DataKey, Tag> passthrough) {
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

    // 返回使正式加载提前结束的类型.
    @Nullable
    public DataKey criticalFailure() {
        return this.criticalFailure < 0 ? null : this.registry.keyAt(this.criticalFailure);
    }

    // 记录单个槽位的失败.
    void failed(int slot, @NotNull Throwable failure, boolean critical) {
        if (this.failures == null) {
            this.failures = new Throwable[this.values.length];
        }
        this.failures[slot] = failure;
        if (critical) {
            this.criticalFailure = slot;
        }
    }

    // 将成功值和非关键失败移交本次玩家应用, 之后由 Context 管理值的生命周期.
    @NotNull
    SnapshotApplyContext intoApplyContext() {
        SnapshotApplyContext context = new SnapshotApplyContext(this.registry, this.passthrough, this.values);
        for (int i = 0; i < this.values.length; i++) {
            if (this.failures != null && this.failures[i] != null) {
                context.decodeSkipped(i, this.failures[i]);
            }
        }
        return context;
    }
}
