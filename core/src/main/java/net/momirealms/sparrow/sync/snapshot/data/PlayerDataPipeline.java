package net.momirealms.sparrow.sync.snapshot.data;

import net.minecraft.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

    /** 采集玩家全部已装配类型的脱离值. */
    @NotNull
    public CaptureResult capture(@NotNull Player player) {
        // 采集值沿冻结槽位存放, 编码阶段可以直接复用同一布局
        int size = this.dataRegistry.size();
        Object[] values = new Object[size];
        List<DataKey> skipped = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            DataKey key = this.dataRegistry.keyAt(i);
            PlayerDataType<?> type = this.dataRegistry.typeAt(i);
            try {
                values[i] = type.capture(player);
            } catch (Throwable throwable) {
                // 关键类型采集失败则丢弃整份快照.
                if (type.critical()) {
                    this.logger.error(LogCategory.DATA, player.getUniqueId(), player.getName(), throwable, LogConstants.DATA_CAPTURE_FAILED, key.asString(), player.getName());
                    return new CaptureResult.Failed(key, String.valueOf(throwable.getMessage()));
                }
                // 非关键类型采集失败则跳过.
                skipped.add(key);
                this.logger.warn(LogCategory.DATA, player.getUniqueId(), player.getName(), throwable, LogConstants.DATA_CAPTURE_SKIPPED, key.asString(), player.getName());
            }
        }
        return new CaptureResult.Ready(player.getUniqueId(), player.getName(), this.dataRegistry, values, skipped);
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
    public PrepareResult prepare(@NotNull Snapshot snapshot) {
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
                    return new PrepareResult.Failed(key, String.valueOf(throwable.getMessage()));
                }
                // 槽位保留为可恢复状态, PreApplyEvent 仍可为它补入合法值
                context.decodeSkipped(i, throwable);
                this.logger.warn(LogCategory.DATA, snapshot.meta().player(), null, throwable, LogConstants.DATA_DECODE_SKIPPED, key.asString(), snapshot.meta().id().toString());
            }
        }
        return new PrepareResult.Ready(context);
    }

    /** 在 Gate 阶段把可原生表达的 pending 槽位写入尚未发布的玩家 tag. */
    @NotNull
    public Optional<CompoundTag> applyNative(@NotNull UUID player, @NotNull String playerName, @NotNull Optional<CompoundTag> playerData, @NotNull SnapshotApplyContext context) {
        // 本地 .dat 是写入基底, 本地为空时先建立可丢弃的候选根 tag
        CompoundTag working = playerData.orElseGet(CompoundTag::new);
        boolean synthetic = playerData.isEmpty();
        if (synthetic) {
            working.putInt("DataVersion", VersionHelper.WORLD_VERSION);
            CompoundTag bukkit = new CompoundTag();
            bukkit.putLong("firstPlayed", System.currentTimeMillis());
            working.put("bukkit", bukkit);
        }
        boolean appliedAny = false;
        int size = context.size();
        // 冻结槽位已经按依赖排序, join-only 与非 pending 类型自然跳过
        for (int i = 0; i < size; i++) {
            if (context.stateAt(i) != SnapshotApplyContext.ApplyState.PENDING) continue;
            NativePlayerDataType<?> nativeType = this.dataRegistry.nativeTypeAt(i);
            if (nativeType == null) continue;
            try {
                if (applyNativeValue(nativeType, working, context.valueAt(i))) {
                    context.appliedNative(i);
                    appliedAny = true;
                }
            } catch (Throwable throwable) {
                // 异常槽位保持 PENDING, Join 可以回退应用并且后续 Native 类型继续执行
                context.nativeFailed(i, throwable);
                this.logger.warn(LogCategory.DATA, player, playerName, throwable, LogConstants.DATA_NATIVE_APPLY_FALLBACK, this.dataRegistry.keyAt(i).asString(), playerName);
            }
        }
        // 没有字段成功写入时丢弃候选, 玩家仍保持原版的新玩家语义
        if (synthetic && !appliedAny) return Optional.empty();
        return synthetic ? Optional.of(working) : playerData;
    }

    /** 在玩家线程按拓扑序应用 Context 中仍为 pending 的槽位. */
    @NotNull
    public ApplyResult apply(@NotNull Player player, @NotNull SnapshotApplyContext context) {
        // 这里只消费仍为 PENDING 的槽位, Native 成功项不会再次写入 Player
        int size = context.size();
        for (int i = 0; i < size; i++) {
            if (context.stateAt(i) != SnapshotApplyContext.ApplyState.PENDING) continue;
            DataKey key = this.dataRegistry.keyAt(i);
            PlayerDataType<?> type = this.dataRegistry.typeAt(i);
            try {
                applyValue(type, player, context.valueAt(i));
                context.appliedPlayer(i);
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
    private static <T> boolean applyNativeValue(NativePlayerDataType<T> type, CompoundTag playerData, Object value) {
        return type.applyNative(playerData, (T) value);
    }

    /** 采集结果, Failed 表示关键类型采集失败, 这次不应产出快照. */
    public sealed interface CaptureResult {

        final class Ready implements CaptureResult {
            private final UUID player;
            private final String playerName;
            private final DataRegistry dataRegistry;
            private final Object[] values;
            private final List<DataKey> skipped;

            private Ready(UUID player, String playerName, DataRegistry dataRegistry, Object[] values, List<DataKey> skipped) {
                this.player = player;
                this.playerName = playerName;
                this.dataRegistry = dataRegistry;
                this.values = values;
                this.skipped = List.copyOf(skipped);
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
    public sealed interface PrepareResult {

        record Ready(@NotNull SnapshotApplyContext context) implements PrepareResult {
        }

        record Failed(@NotNull DataKey key, @NotNull String detail) implements PrepareResult {
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
