package net.momirealms.sparrow.sync.snapshot.data;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
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
import java.util.UUID;

public final class SnapshotApplier {
    private final SparrowSync plugin;
    private SyncLogger logger;
    private DataRegistry dataRegistry;

    public SnapshotApplier(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    public SnapshotApplier(@NotNull DataRegistry dataRegistry, @NotNull SyncLogger logger) {
        this.plugin = null;
        this.logger = logger;
        this.dataRegistry = dataRegistry;
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
     * 采集玩家全部已装配类型的数据, <strong>必须在玩家线程上调用</strong>.
     */
    @NotNull
    public CaptureResult capture(@NotNull Player player) {
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

    /**
     * 把一次采集的全部值编码为快照 NBT, 可在任意线程调用.
     */
    @NotNull
    public EncodeResult encode(@NotNull CaptureResult.Ready captured) {
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
                if (type.critical()) {
                    this.logger.error(LogCategory.DATA, captured.player(), captured.playerName(), throwable, LogConstants.DATA_ENCODE_FAILED, key.asString(), captured.playerName());
                    return new EncodeResult.Failed(key, String.valueOf(throwable.getMessage()));
                }
                skipped.add(key);
                this.logger.warn(LogCategory.DATA, captured.player(), captured.playerName(), throwable, LogConstants.DATA_ENCODE_SKIPPED, key.asString(), captured.playerName());
            }
        }

        return new EncodeResult.Ready(this.dataRegistry, tags, skipped);
    }

    /**
     * 解码快照中全部已装配类型的数据, 可在任意线程调用.
     */
    @NotNull
    public PreparedSnapshot prepare(@NotNull Snapshot snapshot) {
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

        Object[] values = new Object[size];
        List<DataKey> skipped = new ArrayList<>();
        int mcDataVersion = snapshot.meta().mcDataVersion();
        for (int i = 0; i < size; i++) {
            Tag data = tags[i];
            if (data == null) continue;
            DataKey key = this.dataRegistry.keyAt(i);
            PlayerDataType<?> type = this.dataRegistry.typeAt(i);
            try {
                values[i] = type.decode(data, mcDataVersion);
            } catch (Throwable exception) {
                if (type.critical()) {
                    return new PreparedSnapshot.Failed(key, String.valueOf(exception.getMessage()));
                }
                skipped.add(key);
                this.logger.warn(LogCategory.DATA, snapshot.meta().player(), null, exception, LogConstants.DATA_DECODE_SKIPPED, key.asString(), snapshot.meta().id().toString());
            }
        }
        return new PreparedSnapshot.Ready(this.dataRegistry, values, skipped, passthrough == null ? Map.of() : passthrough);
    }

    /**
     * 将公开预应用事件修改后的 Map 收回冻结布局, 未注册键与 null 值会被丢弃.
     */
    @NotNull
    public PreparedSnapshot.Ready afterEvent(@NotNull Map<DataKey, Object> decoded, @NotNull PreparedSnapshot.Ready before) {
        int size = this.dataRegistry.size();
        boolean[] allowed = new boolean[size];
        for (int i = 0; i < size; i++) {
            allowed[i] = before.values[i] != null;
        }
        int skippedSize = before.skipped().size();
        for (int i = 0; i < skippedSize; i++) {
            int slot = this.dataRegistry.slot(before.skipped().get(i));
            if (slot >= 0) allowed[slot] = true;
        }

        Object[] values = new Object[size];
        for (Map.Entry<DataKey, Object> entry : decoded.entrySet()) {
            int slot = this.dataRegistry.slot(entry.getKey());
            if (slot >= 0 && allowed[slot] && entry.getValue() != null) {
                values[slot] = entry.getValue();
            }
        }

        List<DataKey> skipped = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (before.values[i] != null && values[i] == null) skipped.add(this.dataRegistry.keyAt(i));
        }
        for (int i = 0; i < skippedSize; i++) {
            DataKey key = before.skipped().get(i);
            if (values[this.dataRegistry.slot(key)] == null) skipped.add(key);
        }
        return new PreparedSnapshot.Ready(this.dataRegistry, values, skipped, before.passthrough());
    }

    /**
     * 把预解码结果按拓扑序应用到玩家. <strong>必须在玩家的拥有线程上调用</strong>.
     */
    @NotNull
    public ApplyResult apply(@NotNull Player player, @NotNull PreparedSnapshot.Ready prepared) {
        List<DataKey> applied = new ArrayList<>();
        List<DataKey> skipped = new ArrayList<>(prepared.skipped());
        int size = this.dataRegistry.size();
        for (int i = 0; i < size; i++) {
            Object value = prepared.values[i];
            if (value == null) continue;
            DataKey key = this.dataRegistry.keyAt(i);
            PlayerDataType<?> type = this.dataRegistry.typeAt(i);
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

    // capture 与 encode 使用同一个类型实例, 值的实际类型由 capture 保证
    @SuppressWarnings("unchecked")
    private static <T> Tag encodeValue(PlayerDataType<T> type, Object value) {
        return type.encode((T) value);
    }

    // prepare 与 apply 使用同一个类型实例, 值的实际类型由 decode 保证
    @SuppressWarnings("unchecked")
    private static <T> void applyValue(PlayerDataType<T> type, Player player, Object value) {
        type.apply(player, (T) value);
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

            /** 仅供诊断与扩展边界读取, 编码流程直接使用冻结槽位数组. */
            @NotNull
            public Map<DataKey, Object> values() {
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

            /** 在 Snapshot 构造边界将编码槽位物化为 Map. */
            @NotNull
            public Map<DataKey, Tag> data() {
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
    public sealed interface PreparedSnapshot {

        final class Ready implements PreparedSnapshot {
            private final DataRegistry dataRegistry;
            private final Object[] values;
            private final List<DataKey> skipped;
            private final Map<DataKey, Tag> passthrough;

            private Ready(DataRegistry dataRegistry, Object[] values, List<DataKey> skipped, Map<DataKey, Tag> passthrough) {
                this.dataRegistry = dataRegistry;
                this.values = values;
                this.skipped = List.copyOf(skipped);
                this.passthrough = Collections.unmodifiableMap(new LinkedHashMap<>(passthrough));
            }

            /** 仅在 PreApplyEvent 等公开边界将冻结槽位重新物化为 Map. */
            @NotNull
            public Map<DataKey, Object> values() {
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

            @NotNull
            public Map<DataKey, Tag> passthrough() {
                return this.passthrough;
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
