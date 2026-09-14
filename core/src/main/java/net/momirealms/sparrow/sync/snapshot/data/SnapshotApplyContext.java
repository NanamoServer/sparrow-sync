package net.momirealms.sparrow.sync.snapshot.data;

import net.momirealms.sparrow.sync.snapshot.model.SnapshotData;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;

/**
 * 记录一次快照应用的进度, 解码值由本请求独占并在使用后释放.
 * 未注册类型的数据保留到后续保存.
 */
@ApiStatus.Internal
public final class SnapshotApplyContext {
    private final DataRegistry dataRegistry;       // 类型标识对应的固定槽位
    private final Object[] values;                 // PENDING 保存解码值, APPLIED_NATIVE 保存可选 Join 回调, 使用后释放
    private final ApplyState[] states;             // 本次解码和应用进度
    private final SnapshotData passthrough;        // 未注册类型的数据, 后续保存时原样写回
    private List<Failure> failures;                // 首次失败时创建, 按发生顺序记录

    /**
     * 接管解码值并初始化各槽位的应用进度.
     * @param values <strong>交付后由本 Context 独占</strong>
     * @param passthrough 已复制为独立数据帧的未注册类型
     */
    SnapshotApplyContext(@NotNull DataRegistry dataRegistry, @NotNull SnapshotData passthrough, Object @NotNull [] values) {
        this.dataRegistry = dataRegistry;
        this.values = values;
        this.states = new ApplyState[values.length];
        for (int i = 0; i < values.length; i++) {
            this.states[i] = values[i] == null ? ApplyState.ABSENT : ApplyState.PENDING;
        }
        this.passthrough = passthrough;
    }

    /** 返回待应用数据的 Map 副本. */
    @NotNull
    public Map<DataKey, Object> pendingValues() {
        Map<DataKey, Object> pending = new LinkedHashMap<>(this.values.length);
        for (int i = 0; i < this.values.length; i++) {
            if (this.states[i] == ApplyState.PENDING) pending.put(this.dataRegistry.keyAt(i), this.values[i]);
        }
        return Collections.unmodifiableMap(pending);
    }

    /** 接收预应用事件修改后的数据. */
    public void acceptEventValues(@NotNull Map<DataKey, Object> decoded) {
        // 事件可替换值, 也可补回非关键解码失败的类型
        boolean[] present = new boolean[this.values.length];
        for (Map.Entry<DataKey, Object> entry : decoded.entrySet()) {
            int slot = this.dataRegistry.slot(entry.getKey());
            if (slot < 0 || entry.getValue() == null) continue;
            ApplyState state = this.states[slot];
            if (state != ApplyState.PENDING && state != ApplyState.DECODE_SKIPPED) continue;
            this.values[slot] = entry.getValue();
            present[slot] = true;
        }
        // 删除的值标为跳过, 补回的值重新等待应用
        for (int i = 0; i < this.states.length; i++) {
            if (this.states[i] == ApplyState.PENDING && !present[i]) {
                this.values[i] = null;
                this.states[i] = ApplyState.SKIPPED;
            } else if (this.states[i] == ApplyState.DECODE_SKIPPED && present[i]) {
                this.states[i] = ApplyState.PENDING;
            }
        }
    }

    @NotNull
    public SnapshotData passthrough() {
        return this.passthrough;
    }

    @NotNull
    public List<DataKey> applied() {
        List<DataKey> applied = new ArrayList<>();
        for (int i = 0; i < this.states.length; i++) {
            ApplyState state = this.states[i];
            if (state == ApplyState.APPLIED_NATIVE || state == ApplyState.APPLIED_PLAYER) applied.add(this.dataRegistry.keyAt(i));
        }
        return List.copyOf(applied);
    }

    @NotNull
    public List<DataKey> skipped() {
        List<DataKey> skipped = new ArrayList<>();
        for (int i = 0; i < this.states.length; i++) {
            ApplyState state = this.states[i];
            if (state == ApplyState.DECODE_SKIPPED || state == ApplyState.SKIPPED) skipped.add(this.dataRegistry.keyAt(i));
        }
        return List.copyOf(skipped);
    }

    @NotNull
    public List<Failure> failures() {
        return this.failures == null ? List.of() : List.copyOf(this.failures);
    }

    /** 取出需单独应用的值, 完成前暂计为跳过. */
    @Nullable
    public Object takePending(@NotNull DataKey key) {
        int slot = this.dataRegistry.slot(key);
        if (slot < 0 || this.states[slot] != ApplyState.PENDING) return null;
        Object value = this.values[slot];
        this.values[slot] = null;
        this.states[slot] = ApplyState.SKIPPED;
        return value;
    }

    /** 记录单独应用已完成的类型, key 须来自本次取出的待应用值. */
    public void appliedPlayer(@NotNull DataKey key) {
        this.appliedPlayer(this.dataRegistry.slot(key));
    }

    int size() {
        return this.values.length;
    }

    @NotNull
    ApplyState stateAt(int slot) {
        return this.states[slot];
    }

    @NotNull
    Object valueAt(int slot) {
        return this.values[slot];
    }

    void decodeSkipped(int slot, @NotNull Throwable throwable) {
        this.states[slot] = ApplyState.DECODE_SKIPPED;
        this.recordFailure(slot, FailureStage.DECODE, throwable);
    }

    void appliedPlayer(int slot) {
        this.values[slot] = null;
        this.states[slot] = ApplyState.APPLIED_PLAYER;
    }

    void appliedNative(int slot, @Nullable Consumer<Player> joinHandoff) {
        this.values[slot] = joinHandoff;
        this.states[slot] = ApplyState.APPLIED_NATIVE;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    Consumer<Player> nativeHandoffAt(int slot) {
        return this.states[slot] == ApplyState.APPLIED_NATIVE ? (Consumer<Player>) this.values[slot] : null;
    }

    // 保留 PENDING, 在 Join 阶段重新应用
    void nativeFailed(int slot, @NotNull Throwable throwable) {
        this.recordFailure(slot, FailureStage.NATIVE, throwable);
    }

    void playerSkipped(int slot, @NotNull Throwable throwable) {
        this.values[slot] = null;
        this.states[slot] = ApplyState.SKIPPED;
        this.recordFailure(slot, FailureStage.PLAYER, throwable);
    }

    void playerFailed(int slot, @NotNull Throwable throwable) {
        this.values[slot] = null;
        this.states[slot] = ApplyState.FAILED;
        this.recordFailure(slot, FailureStage.PLAYER, throwable);
    }

    private void recordFailure(int slot, @NotNull FailureStage stage, @NotNull Throwable throwable) {
        if (this.failures == null) this.failures = new ArrayList<>();
        this.failures.add(new Failure(this.dataRegistry.keyAt(slot), stage, String.valueOf(throwable)));
    }

    public enum ApplyState {
        ABSENT,          // 快照没有对应数据
        PENDING,         // 已解码, 等待登录前或玩家线程应用
        DECODE_SKIPPED,  // 解码失败, 事件仍可补入有效值
        APPLIED_NATIVE,  // 已写入登录使用的原版数据源
        APPLIED_PLAYER,  // 已在玩家线程应用
        SKIPPED,         // 事件移除或非关键应用失败
        FAILED           // 关键应用失败
    }

    public enum FailureStage {
        DECODE,
        NATIVE,
        PLAYER
    }

    public record Failure(@NotNull DataKey key, @NotNull FailureStage stage, @NotNull String detail) {
    }
}
