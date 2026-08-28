package net.momirealms.sparrow.sync.data;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.snapshot.DataDeclaration;
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
    private final PluginLogger logger;

    public SnapshotApplier(@NotNull DataRegistry registry, @NotNull PluginLogger logger) {
        this.logger = logger;
        if (registry.frozen()) throw new IllegalStateException("data registry is already frozen, snapshot applier is assembled once per registry");
        registry.freeze();
        // 收割注册表中一切带行为的声明, 纯声明 (无采集应用能力) 不参与装配
        Map<DataKey, PlayerDataType<?>> byKey = new LinkedHashMap<>();
        for (DataDeclaration declaration : registry.declarations()) {
            if (declaration instanceof PlayerDataType<?> type) { // todo 这段一定成功. 过度检查, DataDeclaration 接口是为了测试强拆的, 不是原本的意图
                byKey.put(type.key(), type);
            }
        }
        this.types = byKey;
        List<DataKey> order = new ArrayList<>(registry.applyOrder());
        order.retainAll(byKey.keySet());
        this.applyOrder = List.copyOf(order);
    }

    /**
     * 采集玩家全部已装配类型的数据. 任一类型采集失败即整体失败, 残缺快照不落盘.
     * <strong>必须在玩家的拥有线程上调用</strong>.
     */
    @NotNull
    public Map<DataKey, Tag> capture(@NotNull Player player) {
        Map<DataKey, Tag> data = new LinkedHashMap<>();
        int size = this.applyOrder.size();
        for (int i = 0; i < size; i++) {
            DataKey key = this.applyOrder.get(i);
            data.put(key, this.types.get(key).capture(player));
        }
        return data;
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
                this.logger.warn("Skipping non-critical data " + key + " of snapshot " + snapshot.meta().id(), exception);
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
                    this.logger.error("Critical data " + key + " failed to apply to " + player.getName(), throwable);
                    return new ApplyResult.Failure(key, String.valueOf(throwable.getMessage()), applied);
                }
                skipped.add(key);
                this.logger.warn("Skipping non-critical data " + key + " while applying to " + player.getName(), throwable);
            }
        }
        return new ApplyResult.Success(applied, skipped);
    }

    // prepare 与 apply 使用同一个类型实例, 值的实际类型由 decode 保证
    @SuppressWarnings("unchecked")
    private static <T> void applyValue(PlayerDataType<T> type, Player player, Object value) {
        type.apply(player, (T) value);
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
