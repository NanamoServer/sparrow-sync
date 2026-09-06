package net.momirealms.sparrow.sync.snapshot.data;

import net.minecraft.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

@ApiStatus.Internal
public final class PlayerDataPipeline {
    private final SparrowSync plugin;
    private SyncLogger logger;
    private DataRegistry dataRegistry;

    public PlayerDataPipeline(@NotNull SparrowSync plugin) {
        // 运行期组件由插件生命周期创建, onLoad 再接入同一份共享状态
        this.plugin = plugin;
    }

    /** 绑定数据类型注册表与日志出口. */
    public void onLoad() {
        this.dataRegistry = this.plugin.dataRegistry();
        this.logger = this.plugin.logger();
    }

    /** 返回启动期固定的数据应用顺序. */
    @NotNull
    public List<DataKey> applyOrder() {
        return this.dataRegistry.applyOrder();
    }

    /**
     * 开始一次采集, 创建本次请求独占的槽位缓冲.
     * SYNC 在玩家拥有线程读取全部类型; OFFLINE 在 Quit 后下一 Region tick 发起的串行任务中读取全部类型.
     * ASYNC 表示宽松保存的第一阶段, 本方法仍在玩家线程执行, 只读取不支持在线异步的类型.
     *
     * @param player 本次保存绑定的玩家对象, 第二阶段继续使用同一个对象
     * @param mode 保存场景; ASYNC 不代表本方法已经处于异步线程
     * @return Ready 可直接编码; Pending 必须先交给串行线程调用 {@link #captureAsync}; Failed 中止整次采集
     */
    @NotNull
    public CaptureResult capture(@NotNull Player player, @NotNull CaptureMode mode) {
        CaptureBuffer buffer = new CaptureBuffer(player, this.dataRegistry.size());
        // null 槽位表代表全类型. 宽松保存先按冻结时编译的同步组读取, 不在热路径重新筛选类型.
        int[] slots = mode == CaptureMode.ASYNC ? this.dataRegistry.syncCaptureSlots() : null;
        // 同步组实际运行在玩家拥有线程, 类型收到 SYNC 后必须复制出可跨线程持有的值.
        CaptureResult.Failed failure = this.captureSlots(player, mode == CaptureMode.ASYNC ? CaptureMode.SYNC : mode, slots, buffer);
        if (failure != null) return failure;
        if (mode == CaptureMode.ASYNC) return new CaptureResult.Pending(buffer);
        return new CaptureResult.Ready(buffer.player, buffer.playerName, this.dataRegistry, buffer.values, buffer.skipped, buffer.captureNanos);
    }

    /**
     * 在玩家串行线程执行, 补齐宽松保存的异步组.
     * 调用方须等第一阶段返回后再投递此方法; 投递后第一阶段不再访问缓冲.
     * 两个阶段顺序写同一数组, 不需要逐类型 Future、缓冲锁或合并另一份采集结果.
     *
     * @param player 第一阶段使用的玩家对象
     * @param pending 第一阶段转交的未完成采集, 只消费一次
     * @return 补齐后的 Ready, 或关键类型采集失败时的 Failed
     */
    @NotNull
    public CaptureResult captureAsync(@NotNull Player player, @NotNull CaptureResult.Pending pending) {
        CaptureBuffer buffer = pending.buffer;
        // 这些槽位和同步组互斥. 即使期间发生新的同步保存, 它使用的也是另一份 CaptureBuffer.
        CaptureResult.Failed failure = this.captureSlots(player, CaptureMode.ASYNC, this.dataRegistry.asyncCaptureSlots(), buffer);
        if (failure != null) return failure;
        return new CaptureResult.Ready(buffer.player, buffer.playerName, this.dataRegistry, buffer.values, buffer.skipped, buffer.captureNanos);
    }

    /**
     * 按指定槽位读取玩家状态, 把结果写到注册表对应的原始下标.
     * 非关键类型失败时该槽位留空并记录 skipped, 其余类型继续; 关键类型失败则立即停止本次采集.
     *
     * @param slots 冻结的同步组或异步组, null 表示读取全部槽位
     * @return 关键类型的失败信息, 没有关键失败时返回 null
     */
    @Nullable
    private CaptureResult.Failed captureSlots(Player player, CaptureMode mode, @Nullable int[] slots, CaptureBuffer buffer) {
        long started = System.nanoTime();
        try {
            int size = slots == null ? this.dataRegistry.size() : slots.length;
            for (int i = 0; i < size; i++) {
                int slot = slots == null ? i : slots[i];
                // 始终使用注册表槽位, 不能把组内下标 i 当成最终下标.
                DataKey key = this.dataRegistry.keyAt(slot);
                PlayerDataType<?> type = this.dataRegistry.typeAt(slot);
                try {
                    buffer.values[slot] = type.capture(player, mode);
                } catch (Throwable throwable) {
                    if (type.critical()) {
                        this.logger.error(LogCategory.DATA, buffer.player, buffer.playerName, throwable, LogConstants.DATA_CAPTURE_FAILED, key.asString(), buffer.playerName);
                        return new CaptureResult.Failed(key, String.valueOf(throwable.getMessage()));
                    }
                    buffer.skipped.add(key);
                    this.logger.warn(LogCategory.DATA, buffer.player, buffer.playerName, throwable, LogConstants.DATA_CAPTURE_SKIPPED, key.asString(), buffer.playerName);
                }
            }
            return null;
        } finally {
            // 只累加实际执行采集的两个时间段. Pending 在队列里等待多久、后续 encode 多久都不计入.
            buffer.captureNanos += System.nanoTime() - started;
        }
    }

    /** 把一次采集的全部值编码为快照 NBT, 可在任意线程调用. */
    @NotNull
    public EncodeResult encode(@NotNull CaptureResult.Ready captured) {
        // 输入值与输出 tag 保持槽位对齐
        int size = this.dataRegistry.size();
        Tag[] tags = new Tag[size];
        List<DataKey> skipped = new ArrayList<>(captured.skipped());
        for (int i = 0; i < size; i++) {
            Object value = captured.values[i];
            if (value == null) continue;
            DataKey key = this.dataRegistry.keyAt(i);
            PlayerDataType<?> type = this.dataRegistry.typeAt(i);
            try {
                tags[i] = encodeValue(type, value);
            } catch (Throwable throwable) {
                // 关键类型无法编码时整份快照都不能进入存储
                if (type.critical()) {
                    this.logger.error(LogCategory.DATA, captured.player(), captured.playerName(), throwable, LogConstants.DATA_ENCODE_FAILED, key.asString(), captured.playerName());
                    return new EncodeResult.Failed(key, String.valueOf(throwable.getMessage()));
                }
                // 非关键类型从本次快照中缺席, skipped 继续传给保存结果
                skipped.add(key);
                this.logger.warn(LogCategory.DATA, captured.player(), captured.playerName(), throwable, LogConstants.DATA_ENCODE_SKIPPED, key.asString(), captured.playerName());
            }
        }
        return new EncodeResult.Ready(this.dataRegistry, tags, skipped);
    }

    /** 解码快照中全部已装配类型的数据, 可在任意线程调用. */
    @NotNull
    public DecodeResult decode(@NotNull Snapshot snapshot) {
        // 已注册数据进入固定槽位, 未安装的类型作为 passthrough 留给下次保存
        int size = this.dataRegistry.size();
        Tag[] tags = new Tag[size];
        Map<DataKey, Tag> passthrough = null;
        for (Map.Entry<DataKey, Tag> entry : snapshot.data().entrySet()) {
            int slot = this.dataRegistry.slot(entry.getKey());
            if (slot < 0) {
                if (passthrough == null) passthrough = new LinkedHashMap<>();
                passthrough.put(entry.getKey(), entry.getValue());
            } else {
                tags[slot] = entry.getValue();
            }
        }

        SnapshotApplyContext context = new SnapshotApplyContext(this.dataRegistry, passthrough == null ? Map.of() : passthrough);
        int mcDataVersion = snapshot.meta().mcDataVersion();
        // 解码值直接进入本次应用 Context, 后续 Native 与 Player 阶段共享这些槽位
        for (int i = 0; i < size; i++) {
            Tag data = tags[i];
            if (data == null) continue;
            DataKey key = this.dataRegistry.keyAt(i);
            PlayerDataType<?> type = this.dataRegistry.typeAt(i);
            try {
                context.decoded(i, type.decode(data, mcDataVersion));
            } catch (Throwable throwable) {
                // 关键类型解码失败时不允许应用这份快照的任何数据
                if (type.critical()) {
                    return new DecodeResult.Failed(key, String.valueOf(throwable.getMessage()));
                }
                // 槽位保留为可恢复状态, PreApplyEvent 仍可为它补入合法值
                context.decodeSkipped(i, throwable);
                this.logger.warn(LogCategory.DATA, snapshot.meta().player(), null, throwable, LogConstants.DATA_DECODE_SKIPPED, key.asString(), snapshot.meta().id().toString());
            }
        }
        return new DecodeResult.Ready(context);
    }

    /** 在 Gate 阶段把可原生表达的 pending 槽位写入原版登录数据源. */
    @NotNull
    public Optional<CompoundTag> applyNative(@NotNull PlayerSession session, @NotNull Optional<CompoundTag> playerData, @NotNull SnapshotApplyContext context) {
        // 本地 .dat 是写入基底, 本地为空时先建立可丢弃的候选根 tag
        CompoundTag working = playerData.orElseGet(CompoundTag::new);
        boolean synthetic = playerData.isEmpty();
        if (synthetic) {
            working.putInt("DataVersion", VersionHelper.WORLD_VERSION);
            CompoundTag bukkit = new CompoundTag();
            bukkit.putLong("firstPlayed", System.currentTimeMillis());
            working.put("bukkit", bukkit);
        }
        boolean playerDataApplied = false;
        int size = context.size();
        // 冻结槽位已经按依赖排序, join-only 与非 pending 类型自然跳过
        for (int i = 0; i < size; i++) {
            if (context.stateAt(i) != SnapshotApplyContext.ApplyState.PENDING) continue;
            NativePlayerDataType<?> nativeType = this.dataRegistry.nativeTypeAt(i);
            if (nativeType == null) continue;
            try {
                if (!nativeType.shouldApply(session)) continue;
                NativePlayerDataType.NativeApplyResult result = applyNativeValue(nativeType, session, working, context.valueAt(i));
                if (result.target() == NativePlayerDataType.NativeApplyResult.Target.NOT_APPLIED) continue;
                context.appliedNative(i, result.joinHandoff());
                if (result.target() == NativePlayerDataType.NativeApplyResult.Target.APPLIED) playerDataApplied = true;
            } catch (Throwable throwable) {
                // 异常槽位保持 PENDING, Join 可以回退应用并且后续 Native 类型继续执行
                context.nativeFailed(i, throwable);
                this.logger.warn(LogCategory.DATA, session.uuid(), session.playerName(), throwable, LogConstants.DATA_NATIVE_APPLY_FALLBACK, this.dataRegistry.keyAt(i).asString(), session.playerName());
            }
        }
        // 没有 .dat 字段成功写入时丢弃候选根 tag, 玩家仍保持原版的新玩家语义
        if (synthetic && !playerDataApplied) return Optional.empty();
        return synthetic ? Optional.of(working) : playerData;
    }

    /** 在玩家线程按拓扑序应用 pending 数据或消费 Native 交接回调. */
    @NotNull
    public ApplyResult apply(@NotNull Player player, @NotNull SnapshotApplyContext context) {
        // Join 回调与 PENDING 数据共用依赖顺序和失败处理
        int size = context.size();
        for (int i = 0; i < size; i++) {
            Consumer<Player> handoff = context.nativeHandoffAt(i);
            if (context.stateAt(i) != SnapshotApplyContext.ApplyState.PENDING && handoff == null) continue;
            DataKey key = this.dataRegistry.keyAt(i);
            PlayerDataType<?> type = this.dataRegistry.typeAt(i);
            try {
                if (handoff == null) {
                    applyValue(type, player, context.valueAt(i));
                    context.appliedPlayer(i);
                } else {
                    handoff.accept(player);
                    context.appliedNative(i, null);
                }
            } catch (Throwable throwable) {
                // 关键失败会留下 FAILED 状态, 调用方据此拒绝会话进入 ACTIVE
                if (type.critical()) {
                    context.playerFailed(i, throwable);
                    this.logger.error(LogCategory.DATA, player.getUniqueId(), player.getName(), throwable, LogConstants.DATA_APPLY_FAILED, key.asString(), player.getName());
                    return new ApplyResult.Failure(key, String.valueOf(throwable.getMessage()), context.applied(), context.failures());
                }
                // 非关键失败释放槽位值并计入最终 skipped
                context.playerSkipped(i, throwable);
                this.logger.warn(LogCategory.DATA, player.getUniqueId(), player.getName(), throwable, LogConstants.DATA_APPLY_SKIPPED, key.asString(), player.getName());
            }
        }
        // 最终列表由 Context 按冻结槽位顺序物化
        return new ApplyResult.Success(context.applied(), context.skipped(), context.failures());
    }

    // type 与 value 在采集时写入同一槽位, 泛型转换集中在这个边界
    @SuppressWarnings("unchecked")
    private static <T> Tag encodeValue(PlayerDataType<T> type, Object value) {
        return type.encode((T) value);
    }

    // Context 延续相同的槽位关系, 这里恢复 Player apply 所需的 T
    @SuppressWarnings("unchecked")
    private static <T> void applyValue(PlayerDataType<T> type, Player player, Object value) {
        type.apply(player, (T) value);
    }

    // Native 类型数组与 Context 共用冻结槽位, 这里恢复 applyNative 所需的 T
    @SuppressWarnings("unchecked")
    private static <T> NativePlayerDataType.NativeApplyResult applyNativeValue(NativePlayerDataType<T> type, PlayerSession session, CompoundTag playerData, Object value) throws IOException {
        return type.applyNative(session, playerData, (T) value);
    }

    private static final class CaptureBuffer {
        private final UUID player;
        private final String playerName;
        private final Object[] values;
        private final List<DataKey> skipped = new ArrayList<>();
        private long captureNanos; // 两段采集执行时间之和, 不含线程间排队

        private CaptureBuffer(Player player, int size) {
            this.player = player.getUniqueId();
            this.playerName = player.getName();
            this.values = new Object[size];
        }
    }

    /** Pending 尚待异步组采集, Ready 才可交给编码器. */
    public sealed interface CaptureResult {

        final class Pending implements CaptureResult {
            private final CaptureBuffer buffer;

            private Pending(CaptureBuffer buffer) {
                this.buffer = buffer;
            }
        }

        final class Ready implements CaptureResult {
            private final UUID player;
            private final String playerName;
            private final DataRegistry dataRegistry;
            private final Object[] values;
            private final List<DataKey> skipped;
            private final long captureNanos;

            private Ready(UUID player, String playerName, DataRegistry dataRegistry, Object[] values, List<DataKey> skipped, long captureNanos) {
                this.player = player;
                this.playerName = playerName;
                this.dataRegistry = dataRegistry;
                this.values = values;
                this.skipped = List.copyOf(skipped);
                this.captureNanos = captureNanos;
            }

            public long captureNanos() {
                return this.captureNanos;
            }

            @NotNull
            public UUID player() {
                return this.player;
            }

            @NotNull
            public String playerName() {
                return this.playerName;
            }

            @NotNull
            public Map<DataKey, Object> values() {
                // Map 只在事件和调试边界创建, 内部继续使用槽位数组
                Map<DataKey, Object> values = new LinkedHashMap<>(this.values.length);
                for (int i = 0; i < this.values.length; i++) {
                    Object value = this.values[i];
                    if (value != null) values.put(this.dataRegistry.keyAt(i), value);
                }
                return Collections.unmodifiableMap(values);
            }

            @NotNull
            public List<DataKey> skipped() {
                return this.skipped;
            }
        }

        record Failed(@NotNull DataKey key, @NotNull String detail) implements CaptureResult {
        }
    }

    /** 编码结果, Failed 表示关键类型无法编码, 这次不应产出快照. */
    public sealed interface EncodeResult {

        final class Ready implements EncodeResult {
            private final DataRegistry dataRegistry;
            private final Tag[] tags;
            private final List<DataKey> skipped;

            private Ready(DataRegistry dataRegistry, Tag[] tags, List<DataKey> skipped) {
                this.dataRegistry = dataRegistry;
                this.tags = tags;
                this.skipped = List.copyOf(skipped);
            }

            @NotNull
            public Map<DataKey, Tag> data() {
                // Map 只在事件和调试边界创建, 内部继续使用槽位数组
                Map<DataKey, Tag> data = new LinkedHashMap<>(this.tags.length);
                for (int i = 0; i < this.tags.length; i++) {
                    Tag tag = this.tags[i];
                    if (tag != null) data.put(this.dataRegistry.keyAt(i), tag);
                }
                return Collections.unmodifiableMap(data);
            }

            @NotNull
            public List<DataKey> skipped() {
                return this.skipped;
            }
        }

        record Failed(@NotNull DataKey key, @NotNull String detail) implements EncodeResult {
        }
    }

    /** 预解码结果, Failed 表示关键类型解码失败, 整份快照不应被应用. */
    public sealed interface DecodeResult {

        record Ready(@NotNull SnapshotApplyContext context) implements DecodeResult {
        }

        record Failed(@NotNull DataKey key, @NotNull String detail) implements DecodeResult {
        }
    }

    /** 应用结果, Failure 携带关键失败前已经完成的数据类型. */
    public sealed interface ApplyResult {

        record Success(@NotNull List<DataKey> applied, @NotNull List<DataKey> skipped, @NotNull List<SnapshotApplyContext.Failure> failures) implements ApplyResult {
            public Success {
                applied = List.copyOf(applied);
                skipped = List.copyOf(skipped);
                failures = List.copyOf(failures);
            }
        }

        record Failure(@NotNull DataKey failedKey, @NotNull String detail, @NotNull List<DataKey> appliedBefore, @NotNull List<SnapshotApplyContext.Failure> failures) implements ApplyResult {
            public Failure {
                appliedBefore = List.copyOf(appliedBefore);
                failures = List.copyOf(failures);
            }
        }
    }
}
