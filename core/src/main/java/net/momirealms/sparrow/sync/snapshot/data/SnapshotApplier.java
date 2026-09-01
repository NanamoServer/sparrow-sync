package net.momirealms.sparrow.sync.snapshot.data;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 快照的采集与应用编排. 应用分两段: prepare 在任意线程完成全部解码, 关键类型解码失败则整体失败;
 * apply 在玩家拥有线程按拓扑序写入, 关键类型失败中止, 非关键类型跳过并告警. 快照携带的未注册数据不参与应用.
 */
public final class SnapshotApplier {
    private final Map<DataKey, PlayerDataType<?>> types;
    private final List<DataKey> applyOrder;
    private final SyncLogger logger;

    public SnapshotApplier(@NotNull DataRegistry registry, @NotNull SyncLogger logger) {
        this.logger = logger;
        if (registry.frozen()) throw new IllegalStateException("data registry is already frozen, snapshot applier is assembled once per registry");
        registry.freeze();
        Map<DataKey, PlayerDataType<?>> byKey = new LinkedHashMap<>();
        for (PlayerDataType<?> type : registry.types()) {
            byKey.put(type.key(), type);
        }
        this.types = byKey;
        this.applyOrder = List.copyOf(registry.applyOrder());
    }

    /**
     * 采集玩家全部已装配类型的数据, <strong>必须在玩家的拥有线程上调用</strong>.
     */
    @NotNull
    public CaptureResult capture(@NotNull Player player) {
        Map<DataKey, Tag> data = new LinkedHashMap<>();
        List<DataKey> skipped = new ArrayList<>();
        int size = this.applyOrder.size();
        for (int i = 0; i < size; i++) {
            DataKey key = this.applyOrder.get(i);
            PlayerDataType<?> type = this.types.get(key);
            try {
                data.put(key, type.capture(player));
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
        return new CaptureResult.Ready(data, skipped);
    }

    /**
     * 解码快照中全部已装配类型的数据, 可在任意线程调用.
     */
    @NotNull
    public PreparedSnapshot prepare(@NotNull Snapshot snapshot) {
        Map<DataKey, Object> values = new LinkedHashMap<>();
        List<DataKey> skipped = new ArrayList<>();
        int mcDataVersion = snapshot.meta().mcDataVersion();
        int size = this.applyOrder.size();
        for (int i = 0; i < size; i++) {
            DataKey key = this.applyOrder.get(i);
            Tag data = snapshot.data(key);
            if (data == null) continue;
            PlayerDataType<?> type = this.types.get(key);
            try {
                values.put(key, type.decode(data, mcDataVersion));
            } catch (Throwable exception) {
                if (type.critical()) {
                    return new PreparedSnapshot.Failed(key, String.valueOf(exception.getMessage()));
                }
                skipped.add(key);
                this.logger.warn(LogCategory.DATA, snapshot.meta().player(), null, exception, LogConstants.DATA_DECODE_SKIPPED, key.asString(), snapshot.meta().id().toString());
            }
        }
        return new PreparedSnapshot.Ready(values, skipped);
    }

    /**
     * 把预解码结果按拓扑序应用到玩家. <strong>必须在玩家的拥有线程上调用</strong>.
     */
    @NotNull
    public ApplyResult apply(@NotNull Player player, @NotNull PreparedSnapshot.Ready prepared) {
        List<DataKey> applied = new ArrayList<>();
        List<DataKey> skipped = new ArrayList<>(prepared.skipped());
        int size = this.applyOrder.size();
        for (int i = 0; i < size; i++) {
            DataKey key = this.applyOrder.get(i);
            Object value = prepared.values().get(key);
            if (value == null) continue;
            PlayerDataType<?> type = this.types.get(key);
            try {
                applyValue(type, player, value);
                applied.add(key);
            } catch (Throwable throwable) {
                // 类型实现是分级隔离的边界, 关键失败中止, 已应用部分不回滚由调用方保持锁定处理
                if (type.critical()) {
                    this.logger.error(LogCategory.DATA, player.getUniqueId(), player.getName(), throwable, LogConstants.DATA_APPLY_FAILED, key.asString(), player.getName());
                    return new ApplyResult.Failure(key, String.valueOf(throwable.getMessage()), applied);
                }
                skipped.add(key);
                this.logger.warn(LogCategory.DATA, player.getUniqueId(), player.getName(), throwable, LogConstants.DATA_APPLY_SKIPPED, key.asString(), player.getName());
            }
        }
        return new ApplyResult.Success(applied, skipped);
    }

    // prepare 与 apply 使用同一个类型实例, 值的实际类型由 decode 保证
    @SuppressWarnings("unchecked")
    private static <T> void applyValue(PlayerDataType<T> type, Player player, Object value) {
        type.apply(player, (T) value);
    }

    /** 采集结果, Failed 表示关键类型采集失败, 这次不应产出快照. */
    public sealed interface CaptureResult {

        record Ready(@NotNull Map<DataKey, Tag> data, @NotNull List<DataKey> skipped) implements CaptureResult {
            public Ready {
                data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
                skipped = List.copyOf(skipped);
            }
        }

        record Failed(@NotNull DataKey key, @NotNull String detail) implements CaptureResult {
        }
    }

    /** 预解码结果, Failed 表示关键类型解码失败, 整份快照不应被应用. */
    public sealed interface PreparedSnapshot {

        record Ready(@NotNull Map<DataKey, Object> values, @NotNull List<DataKey> skipped) implements PreparedSnapshot {
            public Ready {
                values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
                skipped = List.copyOf(skipped);
            }
        }

        record Failed(@NotNull DataKey key, @NotNull String detail) implements PreparedSnapshot {
        }
    }

    /** 应用结果, Failure 携带失败类型与失败前已应用的类型清单. */
    public sealed interface ApplyResult {

        record Success(@NotNull List<DataKey> applied, @NotNull List<DataKey> skipped) implements ApplyResult {
            public Success {
                applied = List.copyOf(applied);
                skipped = List.copyOf(skipped);
            }
        }

        record Failure(@NotNull DataKey failedKey, @NotNull String detail, @NotNull List<DataKey> appliedBefore) implements ApplyResult {
            public Failure {
                appliedBefore = List.copyOf(appliedBefore);
            }
        }
    }
}
