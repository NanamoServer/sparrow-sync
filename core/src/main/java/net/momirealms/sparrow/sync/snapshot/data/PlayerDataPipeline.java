package net.momirealms.sparrow.sync.snapshot.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.map.MapSyncService;
import net.momirealms.sparrow.sync.map.handler.MapType;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotDataCodec;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotData;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@ApiStatus.Internal
public final class PlayerDataPipeline {
    private final SparrowSync plugin;
    private SyncLogger logger;
    private DataRegistry dataRegistry;
    private MapSyncService mapSync;
    private SnapshotDecoder decoder;
    private SnapshotDataCodec dataCodec; // 将未注册类型复制成独立小帧, 会话只保留这些块

    public PlayerDataPipeline(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    public void onLoad() {
        this.dataRegistry = this.plugin.dataRegistry();
        this.decoder = new SnapshotDecoder(this.dataRegistry);
        this.dataCodec = this.plugin.dataCodec();
        this.logger = this.plugin.logger();
        this.mapSync = this.plugin.mapSyncService();
    }

    public void onDelayedEnable() {
        this.dataRegistry.freeze();
        StringJoiner activeTypes = new StringJoiner(", ");
        List<DataKey> order = this.applyOrder();
        for (int i = 0; i < order.size(); i++) {
            activeTypes.add(order.get(i).asString());
        }
        this.logger.info(TranslationManager.console(LogConstants.PLUGIN_REGISTRY_FROZEN, String.valueOf(order.size()), activeTypes.toString()));
    }

    @Nullable
    public MapType mapMode() {
        return this.mapSync.mode();
    }

    @NotNull
    public List<DataKey> applyOrder() {
        return this.dataRegistry.applyOrder();
    }

    /**
     * 开始一次采集, 创建本次请求独占的槽位缓冲.
     * SYNC 在玩家线程读取全部类型;
     * ASYNC 表示分阶段采集保存的第一阶段, 本方法仍在玩家线程执行, 只读取不支持在线异步的类型.
     * OFFLINE 在 Quit 后下一 Region tick 发起的串行任务中读取全部类型.
     *
     * @param player 本次保存绑定的玩家对象, 第二阶段继续使用同一个对象
     * @param mode 保存场景; ASYNC 不代表本方法已经处于异步线程
     * @return Ready 可直接编码; Pending 必须先交给串行线程调用 {@link #captureAsync}; Failed 中止整次采集
     */
    @NotNull
    public CaptureResult capture(@NotNull Player player, @NotNull CaptureMode mode) {
        CaptureBuffer buffer = new CaptureBuffer(player, this.dataRegistry.size());
        // null 槽位表代表全类型. 分阶段采集保存先读取注册表冻结时确定的玩家线程采集组, 不在热路径重新筛选类型.
        int[] slots = mode == CaptureMode.ASYNC ? this.dataRegistry.syncCaptureSlots() : null;
        // 玩家线程采集组实际运行在玩家线程, 类型收到 SYNC 后必须复制出可跨线程持有的值.
        CaptureResult.Failed failure = this.captureSlots(player, mode == CaptureMode.ASYNC ? CaptureMode.SYNC : mode, slots, buffer);
        if (failure != null) return failure;
        if (mode == CaptureMode.ASYNC) return new CaptureResult.Pending(buffer);
        return new CaptureResult.Ready(buffer.player, buffer.playerName, this.dataRegistry, buffer.values, buffer.skipped, buffer.captureNanos);
    }

    /**
     * 在玩家串行线程执行采集, 调用方须等第一阶段返回后再投递此方法.
     * 两个阶段顺序写同一数组, 不需要逐类型 Future、缓冲锁或合并另一份采集结果.
     *
     * @param player 第一阶段使用的玩家对象
     * @param pending 第一阶段转交的未完成采集, 只消费一次
     * @return 补齐后的 Ready, 或关键类型采集失败时的 Failed
     */
    @NotNull
    public CaptureResult captureAsync(@NotNull Player player, @NotNull CaptureResult.Pending pending) {
        CaptureBuffer buffer = pending.buffer;
        // 这些槽位和玩家线程采集组互斥. 即使期间发生新的同步保存, 它使用的也是另一份 CaptureBuffer.
        CaptureResult.Failed failure = this.captureSlots(player, CaptureMode.ASYNC, this.dataRegistry.asyncCaptureSlots(), buffer);
        if (failure != null) return failure;
        return new CaptureResult.Ready(buffer.player, buffer.playerName, this.dataRegistry, buffer.values, buffer.skipped, buffer.captureNanos);
    }

    /**
     * 按指定槽位读取玩家状态, 把结果写到注册表对应的原始下标.
     * 非关键类型失败时该槽位留空并记录 skipped, 其余类型继续; 关键类型失败则立即停止本次采集.
     *
     * @param slots 固定的玩家线程采集组或串行线程采集组, null 表示读取全部槽位
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

    /** 把一次采集的全部值编码为快照 NBT. */
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

    /**
     * 在普通编码完成后处理地图物品, <strong>必须由玩家串行线程发起</strong>.
     * <p>保存请求先持有未处理地图物品的快照, 如果必须停服时则会暂存到本地, 当前 worker 可中断地等待地图结果后继续提交.
     *
     * @param snapshot 已完成类型编码的完整正文
     * @param mode 请求接受时固定的地图模式
     * @param playerName 请求接受时的玩家名
     * @return 整批地图发布和物品改写的结果, 逐项失败及关闭回退由地图管线处理
     */
    @NotNull
    public CompletableFuture<Snapshot> prepareForStorage(@NotNull Snapshot snapshot, @NotNull MapType mode, @NotNull String playerName) {
        return this.mapSync.compileAsync(snapshot, mode, playerName);
    }

    /** 先准备本服地图物品, 再在异步执行器解码所有已装配类型. */
    @NotNull
    public CompletableFuture<DecodeResult> decodeAsync(@NotNull Snapshot snapshot) {
        return this.mapSync.decodeAsync(snapshot).thenApplyAsync(this::decode, this.plugin.scheduler().async());
    }

    /**
     * 解码正式加载的类型数据, 将成功值交给玩家应用状态.
     *
     * @param snapshot 已完成地图准备的快照
     * @return 待应用 Context, 或首个关键类型的失败
     * @throws UncheckedIOException 当未知类型的数据块越界或纯 Tag 编码失败时
     */
    @NotNull
    public DecodeResult decode(@NotNull Snapshot snapshot) {
        DecodedSnapshotData decoded = this.decoder.decodeForApply(snapshot);
        DataKey critical = decoded.criticalFailure();
        // 注册表顺序也决定非关键失败日志的顺序, 关键失败后的类型尚未执行.
        for (int i = 0; i < this.dataRegistry.size(); i++) {
            DataKey key = this.dataRegistry.keyAt(i);
            Throwable failure = decoded.failure(key);
            if (failure == null) continue;
            if (key.equals(critical)) {
                return new DecodeResult.Failed(key, String.valueOf(failure.getMessage()));
            }
            this.logger.warn(LogCategory.DATA, snapshot.meta().player(), null, failure, LogConstants.DATA_DECODE_SKIPPED, key.asString(), snapshot.meta().id().toString());
        }
        // 丢弃决策查询本服注册表.
        UUID player = snapshot.meta().player();
        for (DataKey key : snapshot.keys()) {
            if (this.dataRegistry.shouldDropUnknown(key)) {
                this.logger.file(LogCategory.DATA, player, null, LogConstants.DATA_UNKNOWN_DROPPED, player.toString(), key.asString());
            }
        }
        SnapshotData passthrough = decoded.passthrough();
        if (!passthrough.keys().isEmpty()) {
            // 子集的 raw 仍指向来源, 编码器逐块复制后读回, Context 只持有未知类型的小帧.
            try {
                passthrough = this.dataCodec.decode(this.dataCodec.encode(passthrough));
            } catch (IOException exception) {
                throw new UncheckedIOException("cannot retain unknown snapshot blocks", exception);
            }
        }
        return new DecodeResult.Ready(decoded.intoApplyContext(passthrough));
    }

    /**
     * 在登录 Gate 阶段把支持写入登录数据源的待应用数据写入对应数据源.
     *
     * @param session 玩家会话
     * @param playerData 玩家数据, 若本地不存在则为空
     * @param context 应用数据上下文
     * @return 最终交给 NMS 加载的数据
     */
    @NotNull
    public Optional<CompoundTag> applyNative(@NotNull PlayerSession session, @NotNull Optional<CompoundTag> playerData, @NotNull SnapshotApplyContext context) {
        // 各类型共用 SparrowNBT 工作副本, 整份 .dat 只在流水线入口与出口转换.
        net.momirealms.sparrow.nbt.CompoundTag working = playerData
                .map(tag -> (net.momirealms.sparrow.nbt.CompoundTag) NbtOps.INSTANCE.convertTo(NBTOps.INSTANCE, tag))
                .orElseGet(NBT::createCompound);
        boolean synthetic = playerData.isEmpty();
        if (synthetic) {
            working.putInt("DataVersion", VersionHelper.WORLD_VERSION);
            net.momirealms.sparrow.nbt.CompoundTag bukkit = NBT.createCompound();
            bukkit.putLong("firstPlayed", System.currentTimeMillis());
            working.put("bukkit", bukkit);
        }
        boolean playerDataApplied = false;
        int size = context.size();
        // 数据类型槽位已经按依赖排序, join-only 与非 pending 类型自然跳过
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
        // 没有 .dat 字段成功写入时保留原输入, 本地文件缺失仍按新玩家处理.
        if (!playerDataApplied) return playerData;
        return Optional.of((CompoundTag) NBTOps.INSTANCE.convertTo(NbtOps.INSTANCE, working));
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
        // 最终列表由 Context 按数据类型槽位顺序生成
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

    // 登录数据源写入实现数组与 Context 共用数据类型槽位, 这里恢复 applyNative 所需的 T
    @SuppressWarnings("unchecked")
    private static <T> NativePlayerDataType.NativeApplyResult applyNativeValue(NativePlayerDataType<T> type, PlayerSession session, net.momirealms.sparrow.nbt.CompoundTag playerData, Object value) throws IOException {
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

    /** Pending 尚待串行线程采集组采集, Ready 才可交给编码器. */
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

            @Nullable
            public Object value(@NotNull DataKey key) {
                int slot = this.dataRegistry.slot(key);
                return slot < 0 ? null : this.values[slot];
            }

            @NotNull
            public Map<DataKey, Object> values() {
                // 按注册表顺序返回已采集的值, 此处尚未编码为 Tag.
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
                // 按注册表顺序收集成功编码的 Tag, 供保存时覆盖同名保留数据
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
