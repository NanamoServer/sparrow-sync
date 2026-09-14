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
    private SnapshotDataCodec dataCodec; // 复制未注册类型的块, 会话不再引用完整来源

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
     * 开始采集, 为本次请求分配独立的值数组.
     * SYNC 在玩家线程读取全部类型; ASYNC 先在玩家线程读取同步类型, 其余交给 captureAsync.
     * OFFLINE 在退出后下一区域 tick 提交的串行任务中读取全部类型.
     * @return Ready 可编码, Pending 须继续 captureAsync, Failed 表示采集失败
     */
    @NotNull
    public CaptureResult capture(@NotNull Player player, @NotNull CaptureMode mode) {
        CaptureBuffer buffer = new CaptureBuffer(player, this.dataRegistry.size());
        // null 槽位表表示全部类型, 分阶段采集使用注册表预先分好的槽位
        int[] slots = mode == CaptureMode.ASYNC ? this.dataRegistry.syncCaptureSlots() : null;
        // 此组始终在玩家线程采集, 返回值须可跨线程使用
        CaptureResult.Failed failure = this.captureSlots(player, mode == CaptureMode.ASYNC ? CaptureMode.SYNC : mode, slots, buffer);
        if (failure != null) return failure;
        if (mode == CaptureMode.ASYNC) return new CaptureResult.Pending(buffer);
        return new CaptureResult.Ready(buffer.player, buffer.playerName, this.dataRegistry, buffer.values, buffer.skipped, buffer.captureNanos);
    }

    /**
     * 在玩家串行线程补齐异步类型, <strong>须在第一阶段返回后调用, pending 只能使用一次</strong>.
     * 两阶段按序写入同一个数组.
     */
    @NotNull
    public CaptureResult captureAsync(@NotNull Player player, @NotNull CaptureResult.Pending pending) {
        CaptureBuffer buffer = pending.buffer;
        // 两组写入不同槽位, 其他保存请求使用独立数组
        CaptureResult.Failed failure = this.captureSlots(player, CaptureMode.ASYNC, this.dataRegistry.asyncCaptureSlots(), buffer);
        if (failure != null) return failure;
        return new CaptureResult.Ready(buffer.player, buffer.playerName, this.dataRegistry, buffer.values, buffer.skipped, buffer.captureNanos);
    }

    /**
     * 按槽位采集数据, 非关键失败留空并继续, 关键失败立即停止.
     * @param slots 要采集的槽位, null 表示全部
     * @return 关键失败信息, 无关键失败时返回 null
     */
    @Nullable
    private CaptureResult.Failed captureSlots(Player player, CaptureMode mode, @Nullable int[] slots, CaptureBuffer buffer) {
        long started = System.nanoTime();
        try {
            int size = slots == null ? this.dataRegistry.size() : slots.length;
            for (int i = 0; i < size; i++) {
                int slot = slots == null ? i : slots[i];
                // 结果写入注册表槽位, 不使用组内下标
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
            // 只计算实际采集耗时, 排队和编码不计入
            buffer.captureNanos += System.nanoTime() - started;
        }
    }

    /** 把一次采集的全部值编码为快照 NBT. */
    @NotNull
    public EncodeResult encode(@NotNull CaptureResult.Ready captured) {
        // 输入值与输出 Tag 使用相同槽位
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
                // 关键类型编码失败时终止整份快照
                if (type.critical()) {
                    this.logger.error(LogCategory.DATA, captured.player(), captured.playerName(), throwable, LogConstants.DATA_ENCODE_FAILED, key.asString(), captured.playerName());
                    return new EncodeResult.Failed(key, String.valueOf(throwable.getMessage()));
                }
                // 非关键失败不写入快照, 并记录到 skipped
                skipped.add(key);
                this.logger.warn(LogCategory.DATA, captured.player(), captured.playerName(), throwable, LogConstants.DATA_ENCODE_SKIPPED, key.asString(), captured.playerName());
            }
        }
        return new EncodeResult.Ready(this.dataRegistry, tags, skipped);
    }

    /**
     * 处理已编码快照中的地图物品, <strong>由玩家串行线程发起</strong>.
     * 等待期间请求保留原快照, 停服时可暂存; 单张地图失败由地图处理流程处理.
     */
    @NotNull
    public CompletableFuture<Snapshot> prepareForStorage(@NotNull Snapshot snapshot, @NotNull MapType mode, @NotNull String playerName) {
        return this.mapSync.compileAsync(snapshot, mode, playerName);
    }

    /** 先处理本服地图物品, 再异步解码已注册类型. */
    @NotNull
    public CompletableFuture<DecodeResult> decodeAsync(@NotNull Snapshot snapshot) {
        return this.mapSync.decodeAsync(snapshot).thenApplyAsync(this::decode, this.plugin.scheduler().async());
    }

    /**
     * 将解码值交给应用流程, 遇到首个关键失败时返回失败结果.
     * @throws UncheckedIOException 未知类型块越界或 Tag 编码失败时
     */
    @NotNull
    public DecodeResult decode(@NotNull Snapshot snapshot) {
        DecodedSnapshotData decoded = this.decoder.decodeForApply(snapshot);
        DataKey critical = decoded.criticalFailure();
        // 按注册顺序记录非关键失败, 关键失败后的类型尚未解码
        for (int i = 0; i < this.dataRegistry.size(); i++) {
            DataKey key = this.dataRegistry.keyAt(i);
            Throwable failure = decoded.failure(key);
            if (failure == null) continue;
            if (key.equals(critical)) {
                return new DecodeResult.Failed(key, String.valueOf(failure.getMessage()));
            }
            this.logger.warn(LogCategory.DATA, snapshot.meta().player(), null, failure, LogConstants.DATA_DECODE_SKIPPED, key.asString(), snapshot.meta().id().toString());
        }

        UUID player = snapshot.meta().player();
        for (DataKey key : snapshot.keys()) {
            if (this.dataRegistry.shouldDropUnknown(key)) {
                this.logger.file(LogCategory.DATA, player, null, LogConstants.DATA_UNKNOWN_DROPPED, player.toString(), key.asString());
            }
        }
        SnapshotData passthrough = decoded.passthrough();
        if (!passthrough.keys().isEmpty()) {
            // 复制未知类型的块后重新读取, Context 只保留这部分数据
            try {
                passthrough = this.dataCodec.decode(this.dataCodec.encode(passthrough));
            } catch (IOException exception) {
                throw new UncheckedIOException("cannot retain unknown snapshot blocks", exception);
            }
        }
        return new DecodeResult.Ready(decoded.intoApplyContext(passthrough));
    }

    /**
     * 在登录拦截阶段将快照写入原版要加载的数据源.
     * @return 最终交给 NMS 加载的玩家数据, 本地不存在时可以为空
     */
    @NotNull
    public Optional<CompoundTag> applyNative(@NotNull PlayerSession session, @NotNull Optional<CompoundTag> playerData, @NotNull SnapshotApplyContext context) {
        // 各类型共用一个 SparrowNBT 副本, 只在入口和出口转换原版 NBT
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
        // 按依赖顺序应用, 跳过仅支持 Join 或无需处理的类型
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
                // 失败项保留 PENDING, 留到 Join 重试, 其他类型继续处理
                context.nativeFailed(i, throwable);
                this.logger.warn(LogCategory.DATA, session.uuid(), session.playerName(), throwable, LogConstants.DATA_NATIVE_APPLY_FALLBACK, this.dataRegistry.keyAt(i).asString(), session.playerName());
            }
        }
        // 没有成功修改 .dat 时返回原输入, 本地数据缺失仍按新玩家处理
        if (!playerDataApplied) return playerData;
        return Optional.of((CompoundTag) NBTOps.INSTANCE.convertTo(NbtOps.INSTANCE, working));
    }

    /** 在玩家线程按依赖顺序应用剩余数据并执行登录交接回调. */
    @NotNull
    public ApplyResult apply(@NotNull Player player, @NotNull SnapshotApplyContext context) {
        // Join 回调与待应用数据使用相同的依赖顺序和失败处理
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
                // 关键失败保留 FAILED 状态, 会话不能进入 ACTIVE
                if (type.critical()) {
                    context.playerFailed(i, throwable);
                    this.logger.error(LogCategory.DATA, player.getUniqueId(), player.getName(), throwable, LogConstants.DATA_APPLY_FAILED, key.asString(), player.getName());
                    return new ApplyResult.Failure(key, String.valueOf(throwable.getMessage()), context.applied(), context.failures());
                }
                // 非关键失败释放对应值并计入 skipped
                context.playerSkipped(i, throwable);
                this.logger.warn(LogCategory.DATA, player.getUniqueId(), player.getName(), throwable, LogConstants.DATA_APPLY_SKIPPED, key.asString(), player.getName());
            }
        }
        // 最终结果按注册表槽位顺序排列
        return new ApplyResult.Success(context.applied(), context.skipped(), context.failures());
    }

    // type 和 value 来自同一槽位, 此处恢复对应泛型
    @SuppressWarnings("unchecked")
    private static <T> Tag encodeValue(PlayerDataType<T> type, Object value) {
        return type.encode((T) value);
    }

    // 按共享槽位确定 apply 所需的值类型
    @SuppressWarnings("unchecked")
    private static <T> void applyValue(PlayerDataType<T> type, Player player, Object value) {
        type.apply(player, (T) value);
    }

    // 按共享槽位确定 applyNative 所需的值类型
    @SuppressWarnings("unchecked")
    private static <T> NativePlayerDataType.NativeApplyResult applyNativeValue(NativePlayerDataType<T> type, PlayerSession session, net.momirealms.sparrow.nbt.CompoundTag playerData, Object value) throws IOException {
        return type.applyNative(session, playerData, (T) value);
    }

    private static final class CaptureBuffer {
        private final UUID player;
        private final String playerName;
        private final Object[] values;
        private final List<DataKey> skipped = new ArrayList<>();
        private long captureNanos; // 两阶段实际采集耗时, 不含排队

        private CaptureBuffer(Player player, int size) {
            this.player = player.getUniqueId();
            this.playerName = player.getName();
            this.values = new Object[size];
        }
    }

    /** Pending 还需异步采集, Ready 可交给编码器. */
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
                // 按注册顺序返回采集值, 尚未编码为 Tag
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

    /** 关键类型编码失败时返回 Failed, 本次不生成快照. */
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
                // 按注册顺序收集已编码的 Tag, 保存时覆盖同名旧数据
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

    /** 关键类型解码失败时返回 Failed, 不应用该快照. */
    public sealed interface DecodeResult {

        record Ready(@NotNull SnapshotApplyContext context) implements DecodeResult {
        }

        record Failed(@NotNull DataKey key, @NotNull String detail) implements DecodeResult {
        }
    }

    /** Failure 保留关键失败前已应用的类型. */
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
