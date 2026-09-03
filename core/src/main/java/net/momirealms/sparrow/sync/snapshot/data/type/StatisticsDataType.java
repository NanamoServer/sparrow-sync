package net.momirealms.sparrow.sync.snapshot.data.type;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.IntArrayTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.proxy.minecraft.core.RegistryProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.resources.IdentifierProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.stats.StatsCounterProxy;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataType;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class StatisticsDataType implements PlayerDataType<StatisticsDataType.Statistics> {
    public static final DataKey STATISTICS = DataKey.sparrow("statistics");

    private static final String TYPES_KEY = "types";
    private static final String VALUES_KEY = "values";
    private static final String AMOUNTS_KEY = "amounts";

    @Override
    @NotNull
    public DataKey key() {
        return STATISTICS;
    }

    @Override
    @NotNull
    public StorageFormat storage() {
        return StorageFormat.STRUCTURED;
    }

    @Override
    @NotNull
    public Statistics capture(@NotNull Player player) {
        Object2IntMap<Stat<?>> values = stats(handle(player).getStats());
        synchronized (values) {
            int count = 0;
            for (Object2IntMap.Entry<Stat<?>> entry : values.object2IntEntrySet()) {
                if (entry.getIntValue() != 0) {
                    count++;
                }
            }
            Stat<?>[] statistics = new Stat<?>[count];
            int[] amounts = new int[count];
            int index = 0;
            for (Object2IntMap.Entry<Stat<?>> entry : values.object2IntEntrySet()) {
                int amount = entry.getIntValue();
                if (amount == 0) {
                    continue;
                }
                statistics[index] = entry.getKey();
                amounts[index] = amount;
                index++;
            }
            return new Statistics(statistics, amounts);
        }
    }

    @Override
    @NotNull
    public Tag encode(@NotNull Statistics value) {
        Stat<?>[] statistics = value.statistics();
        int[] amounts = value.amounts();
        ListTag types = NBT.createList();
        ListTag values = NBT.createList();
        for (int i = 0; i < statistics.length; i++) {
            Stat<?> statistic = statistics[i];
            StatType<?> type = statistic.getType();
            types.add(NBT.createString(registryKey(BuiltInRegistries.STAT_TYPE, type).toString()));
            values.add(NBT.createString(registryKey(type.getRegistry(), statistic.getValue()).toString()));
        }
        CompoundTag root = NBT.createCompound();
        root.put(TYPES_KEY, types);
        root.put(VALUES_KEY, values);
        root.putIntArray(AMOUNTS_KEY, amounts);
        return root;
    }

    @Override
    @NotNull
    public Statistics decode(@NotNull Tag data, int mcDataVersion) throws IOException {
        if (!(data instanceof CompoundTag root)
                || !(root.get(TYPES_KEY) instanceof ListTag types)
                || !(root.get(VALUES_KEY) instanceof ListTag values)
                || !(root.get(AMOUNTS_KEY) instanceof IntArrayTag amountTag)) {
            throw new IOException("statistics data is not a parallel array compound");
        }
        int[] storedAmounts = amountTag.value();
        if (types.size() != values.size() || types.size() != storedAmounts.length) {
            throw new IOException("statistics arrays have different lengths");
        }

        Stat<?>[] statistics = new Stat<?>[storedAmounts.length];
        int[] amounts = new int[storedAmounts.length];
        int count = 0;
        for (int i = 0; i < storedAmounts.length; i++) {
            String typeName = types.getString(i, null);
            String valueName = values.getString(i, null);
            Object typeKey = typeName == null ? null : IdentifierProxy.INSTANCE.tryParse(typeName);
            Object valueKey = valueName == null ? null : IdentifierProxy.INSTANCE.tryParse(valueName);
            if (typeKey == null || valueKey == null) {
                throw new IOException("invalid statistic identifier at index " + i);
            }
            StatType<?> type = (StatType<?>) RegistryProxy.INSTANCE.getValue(BuiltInRegistries.STAT_TYPE, typeKey);
            if (type == null) {
                continue;
            }
            Object statValue = RegistryProxy.INSTANCE.getValue(type.getRegistry(), valueKey);
            int amount = storedAmounts[i];
            if (statValue == null || amount == 0) {
                continue;
            }
            statistics[count] = statistic(type, statValue);
            amounts[count] = amount;
            count++;
        }
        if (count < statistics.length) {
            statistics = Arrays.copyOf(statistics, count);
            amounts = Arrays.copyOf(amounts, count);
        }
        return new Statistics(statistics, amounts);
    }

    @Override
    public void apply(@NotNull Player player, @NotNull Statistics value) {
        ServerPlayer handle = handle(player);
        ServerStatsCounter counter = handle.getStats();
        Stat<?>[] statistics = value.statistics();
        int[] amounts = value.amounts();
        Map<Stat<?>, Integer> remaining = new HashMap<>(statistics.length);
        for (int i = 0; i < statistics.length; i++) {
            remaining.put(statistics[i], amounts[i]);
        }

        Object2IntMap<Stat<?>> current = stats(counter);
        synchronized (current) {
            for (Object2IntMap.Entry<Stat<?>> entry : current.object2IntEntrySet()) {
                Integer expected = remaining.remove(entry.getKey());
                int amount = expected == null ? 0 : expected;
                if (entry.getIntValue() != amount) {
                    counter.setValue(handle, entry.getKey(), amount);
                }
            }
            for (Map.Entry<Stat<?>, Integer> entry : remaining.entrySet()) {
                counter.setValue(handle, entry.getKey(), entry.getValue());
            }
        }
        counter.sendStats(handle);
    }

    @SuppressWarnings("unchecked")
    private static <T> Stat<T> statistic(StatType<?> type, Object value) {
        return ((StatType<T>) type).get((T) value);
    }

    private static Object registryKey(Registry<?> registry, Object value) {
        return RegistryProxy.INSTANCE.getKey(registry, value);
    }

    @SuppressWarnings("unchecked")
    private static Object2IntMap<Stat<?>> stats(ServerStatsCounter counter) {
        return (Object2IntMap<Stat<?>>) StatsCounterProxy.INSTANCE.getStats(counter);
    }

    private static ServerPlayer handle(Player player) {
        return ((CraftPlayer) player).getHandle();
    }

    public record Statistics(@NotNull Stat<?> @NotNull [] statistics, @NotNull int[] amounts) {
    }
}
