package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;
import net.minecraft.stats.Stats;
import net.momirealms.sparrow.sync.proxy.minecraft.core.RegistryProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.resources.IdentifierProxy;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ObjIntConsumer;
import java.util.function.ToIntFunction;

public final class StatisticsDataType extends CodecDataType<StatisticsDataType.Statistics> {
    public static final DataKey STATISTICS = DataKey.sparrow("statistics");

    public StatisticsDataType() {
        super(STATISTICS, StorageFormat.STRUCTURED, Statistics.CODEC);
    }

    @Override
    @NotNull
    protected Statistics captureValue(@NotNull Player player) {
        ServerStatsCounter counter = handle(player).getStats();
        Map<Object, Integer> untyped = captureUntyped(counter);
        Map<Object, Map<Object, Integer>> blocks = new LinkedHashMap<>();
        Map<Object, Map<Object, Integer>> items = new LinkedHashMap<>();
        Map<Object, Map<Object, Integer>> entities = new LinkedHashMap<>();
        for (StatType<?> type : BuiltInRegistries.STAT_TYPE) {
            if (type == Stats.CUSTOM) {
                continue;
            }
            captureType(counter, type, typedTarget(type, blocks, items, entities));
        }
        return new Statistics(untyped, blocks, items, entities);
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull Statistics value) {
        ServerPlayer handle = handle(player);
        ServerStatsCounter counter = handle.getStats();
        applyUntyped(handle, counter, value.untyped());
        for (StatType<?> type : BuiltInRegistries.STAT_TYPE) {
            if (type == Stats.CUSTOM) {
                continue;
            }
            applyType(handle, counter, type, typedTarget(type, value.blocks(), value.items(), value.entities()));
        }
        counter.sendStats(handle);
    }

    private static <T> void captureType(
            ServerStatsCounter counter,
            StatType<T> type,
            Map<Object, Map<Object, Integer>> target
    ) {
        Registry<T> registry = type.getRegistry();
        Map<Object, Integer> values = captureValues(registry, value -> registryKey(registry, value), value -> counter.getValue(type, value));
        if (!values.isEmpty()) {
            target.put(registryKey(BuiltInRegistries.STAT_TYPE, type), values);
        }
    }

    private static <T> void applyType(
            ServerPlayer player,
            ServerStatsCounter counter,
            StatType<T> type,
            Map<Object, Map<Object, Integer>> source
    ) {
        Registry<T> registry = type.getRegistry();
        Map<Object, Integer> values = source.getOrDefault(registryKey(BuiltInRegistries.STAT_TYPE, type), Map.of());
        applyComplete(
                registry,
                value -> registryKey(registry, value),
                values,
                value -> counter.getValue(type, value),
                (value, amount) -> counter.setValue(player, type.get(value), amount)
        );
    }

    private static Map<Object, Map<Object, Integer>> typedTarget(
            StatType<?> type,
            Map<Object, Map<Object, Integer>> blocks,
            Map<Object, Map<Object, Integer>> items,
            Map<Object, Map<Object, Integer>> entities
    ) {
        Registry<?> registry = type.getRegistry();
        if (registry == BuiltInRegistries.BLOCK) {
            return blocks;
        }
        if (registry == BuiltInRegistries.ITEM) {
            return items;
        }
        if (registry == BuiltInRegistries.ENTITY_TYPE) {
            return entities;
        }
        throw new IllegalStateException("unsupported statistic type " + registryKey(BuiltInRegistries.STAT_TYPE, type));
    }

    private static Map<Object, Integer> captureUntyped(ServerStatsCounter counter) {
        Map<Object, Integer> captured = new LinkedHashMap<>();
        StatType<Object> custom = customStatType();
        for (Object statistic : BuiltInRegistries.CUSTOM_STAT) {
            int current = counter.getValue(custom.get(statistic));
            if (current != 0) {
                captured.put(statistic, current);
            }
        }
        return captured;
    }

    private static void applyUntyped(ServerPlayer player, ServerStatsCounter counter, Map<Object, Integer> snapshot) {
        StatType<Object> custom = customStatType();
        for (Object statistic : BuiltInRegistries.CUSTOM_STAT) {
            Stat<?> stat = custom.get(statistic);
            int expected = snapshot.getOrDefault(statistic, 0);
            if (counter.getValue(stat) != expected) {
                counter.setValue(player, stat, expected);
            }
        }
    }

    // 零值由缺失项表达, 减少大注册表产生的快照体积
    static <T> Map<Object, Integer> captureValues(
            Iterable<T> values,
            Function<T, Object> key,
            ToIntFunction<T> amount
    ) {
        Map<Object, Integer> captured = new LinkedHashMap<>();
        for (T value : values) {
            int current = amount.applyAsInt(value);
            if (current != 0) {
                captured.put(key.apply(value), current);
            }
        }
        return captured;
    }

    // 当前注册表是完整状态域, 快照中没有的统计项统一写为零
    static <T> void applyComplete(
            Iterable<T> values,
            Function<T, Object> key,
            Map<Object, Integer> snapshot,
            ToIntFunction<T> current,
            ObjIntConsumer<T> apply
    ) {
        for (T value : values) {
            int expected = snapshot.getOrDefault(key.apply(value), 0);
            if (current.applyAsInt(value) != expected) {
                apply.accept(value, expected);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static StatType<Object> customStatType() {
        return (StatType<Object>) (StatType<?>) Stats.CUSTOM;
    }

    private static Object registryKey(Registry<?> registry, Object value) {
        return RegistryProxy.INSTANCE.getKey(registry, value);
    }

    private static ServerPlayer handle(Player player) {
        return ((CraftPlayer) player).getHandle();
    }

    public record Statistics(
            Map<Object, Integer> untyped,
            Map<Object, Map<Object, Integer>> blocks,
            Map<Object, Map<Object, Integer>> items,
            Map<Object, Map<Object, Integer>> entities
    ) {
        private static final Codec<Map<Object, Integer>> VALUE_MAP = Codec.unboundedMap(IdentifierProxy.INSTANCE.getCodec(), Codec.INT);
        private static final Codec<Map<Object, Map<Object, Integer>>> TYPED_MAP = Codec.unboundedMap(IdentifierProxy.INSTANCE.getCodec(), VALUE_MAP);
        public static final Codec<Statistics> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                VALUE_MAP.fieldOf("untyped").forGetter(Statistics::untyped),
                TYPED_MAP.fieldOf("blocks").forGetter(Statistics::blocks),
                TYPED_MAP.fieldOf("items").forGetter(Statistics::items),
                TYPED_MAP.fieldOf("entities").forGetter(Statistics::entities)
        ).apply(instance, Statistics::new));
    }
}
