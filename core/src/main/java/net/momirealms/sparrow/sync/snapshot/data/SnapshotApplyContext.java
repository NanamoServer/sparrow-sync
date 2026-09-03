package net.momirealms.sparrow.sync.snapshot.data;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.*;

@ApiStatus.Internal
public final class SnapshotApplyContext {
    private final DataRegistry dataRegistry;       // DataKey 与冻结槽位的稳定映射
    private final Object[] values;                 // 解码值按槽位存放, 应用完成后释放引用
    private final ApplyState[] states;             // 每个槽位在本轮解码和应用中的进度
    private final Map<DataKey, Tag> passthrough;   // 未注册类型原样保留到玩家后续保存
    private List<Failure> failures;                // 首次失败时创建, 按发生顺序记录

    // 在 worker 完成解码和 Native 写入后随 Session 发布,
    SnapshotApplyContext(@NotNull DataRegistry dataRegistry, @NotNull Map<DataKey, Tag> passthrough) {
        this.dataRegistry = dataRegistry;
        this.values = new Object[dataRegistry.size()];
        this.states = new ApplyState[dataRegistry.size()];
        Arrays.fill(this.states, ApplyState.ABSENT);
        this.passthrough = Collections.unmodifiableMap(new LinkedHashMap<>(passthrough));
    }

    /** 返回仍需通过 Player 路径应用的数据副本. */
    @NotNull
    public Map<DataKey, Object> pendingValues() {
        Map<DataKey, Object> pending = new LinkedHashMap<>(this.values.length);
        for (int i = 0; i < this.values.length; i++) {
            if (this.states[i] == ApplyState.PENDING) pending.put(this.dataRegistry.keyAt(i), this.values[i]);
        }
        return Collections.unmodifiableMap(pending);
    }

    /** 收回 PreApplyEvent 修改后的 pending 数据. */
    public void acceptEventValues(@NotNull Map<DataKey, Object> decoded) {
        // 事件可以替换待应用值, 也能补回非关键解码失败的槽位
        boolean[] present = new boolean[this.values.length];
        for (Map.Entry<DataKey, Object> entry : decoded.entrySet()) {
            int slot = this.dataRegistry.slot(entry.getKey());
            if (slot < 0 || entry.getValue() == null) continue;
            ApplyState state = this.states[slot];
            if (state != ApplyState.PENDING && state != ApplyState.DECODE_SKIPPED) continue;
            this.values[slot] = entry.getValue();
            present[slot] = true;
        }
        // 事件删除的值转为跳过, 补回的值重新进入 Player 应用路径
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
    public Map<DataKey, Tag> passthrough() {
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

    void decoded(int slot, @NotNull Object value) {
        this.values[slot] = value;
        this.states[slot] = ApplyState.PENDING;
    }

    void decodeSkipped(int slot, @NotNull Throwable throwable) {
        this.states[slot] = ApplyState.DECODE_SKIPPED;
        this.recordFailure(slot, FailureStage.DECODE, throwable);
    }

    void appliedNative(int slot) {
        this.values[slot] = null;
        this.states[slot] = ApplyState.APPLIED_NATIVE;
    }

    // 槽位保持 PENDING, Join 阶段执行完整的 Player 回退
    void nativeFailed(int slot, @NotNull Throwable throwable) {
        this.recordFailure(slot, FailureStage.NATIVE, throwable);
    }

    void appliedPlayer(int slot) {
        this.values[slot] = null;
        this.states[slot] = ApplyState.APPLIED_PLAYER;
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
        PENDING,         // 已解码, 等待 Native 或 Player 应用
        DECODE_SKIPPED,  // 解码失败, 允许事件补入合法值
        APPLIED_NATIVE,  // 已写入登录使用的原生玩家数据
        APPLIED_PLAYER,  // 已通过 Bukkit Player 路径应用
        SKIPPED,         // 事件移除或非关键 Player 应用失败
        FAILED           // 关键 Player 应用失败
    }

    public enum FailureStage {
        DECODE,
        NATIVE,
        PLAYER
    }

    public record Failure(@NotNull DataKey key, @NotNull FailureStage stage, @NotNull String detail) {
    }
}
