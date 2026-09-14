package net.momirealms.sparrow.sync.snapshot.data.type;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundAwardStatsPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;
import net.minecraft.stats.Stats;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.IntArrayTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.proxy.minecraft.core.RegistryProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.network.ConnectionProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.resources.IdentifierProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.stats.ServerStatsCounterProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.stats.StatsCounterProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage.PlayerJsonFile;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage.PlayerJsonStorage;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import net.momirealms.sparrow.sync.util.GsonHelper;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spigotmc.SpigotConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

public final class StatisticsDataType implements NativePlayerDataType<StatisticsDataType.Statistics> {
    public static final DataKey STATISTICS = DataKey.sparrow("statistics");

    private static final boolean NATIVE_APPLY_SUPPORTED = VersionHelper.isPaper() && VersionHelper.isOrAbove1_21_7(); // Paper 在此版本起支持延后构造玩家
    private static final String TYPES_KEY = "types";
    private static final String VALUES_KEY = "values";
    private static final String AMOUNTS_KEY = "amounts";

    public StatisticsDataType() {
        // 启动时遍历 StatType, 初始化各类型的统计 Map
        for (StatType<?> type : BuiltInRegistries.STAT_TYPE) {
            for (Object value : type.getRegistry()) {
                statistic(type, value);
            }
        }
    }

    @Override
    @NotNull
    public DataKey key() {
        return STATISTICS;
    }

    @Override
    public boolean supportsAsyncCapture() {
        return true;
    }

    @Override
    @NotNull
    public Statistics capture(@NotNull Player player, @NotNull CaptureMode mode) {
        Object2IntMap<Stat<?>> values = stats(handle(player).getStats());
        Statistics captured;
        int size;
        synchronized (values) {
            size = values.size();
            Stat<?>[] statistics = new Stat<?>[size];
            int[] amounts = new int[size];
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
            if (index < size) {
                statistics = Arrays.copyOf(statistics, index);
                amounts = Arrays.copyOf(amounts, index);
            }
            captured = new Statistics(statistics, amounts);
        }
        return captured;
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
    public Statistics decode(@NotNull Tag data) throws IOException {
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
    public boolean shouldApply(@NotNull PlayerSession session) {
        return NATIVE_APPLY_SUPPORTED
                && PluginConfig.synchronization$nativeAsyncApply().statistics()
                && ConnectionProxy.INSTANCE.getSavedPlayerForLegacyEvents(session.connection()) == null;
    }

    @Override
    @NotNull
    public NativeApplyResult applyNative(@NotNull PlayerSession session, @NotNull CompoundTag playerData, @NotNull Statistics value) throws IOException {
        if (!PlayerJsonStorage.materialize(session.uuid(), PlayerJsonFile.STATISTICS, encodeNativeJson(value))) {
            throw new IOException("atomic statistics JSON replacement failed or is not supported");
        }
        return NativeApplyResult.APPLIED_EXTERNAL;
    }

    private static byte[] encodeNativeJson(@NotNull Statistics value) {
        JsonObject groups = new JsonObject();
        Stat<?>[] statistics = value.statistics();
        int[] amounts = value.amounts();
        for (int i = 0; i < statistics.length; i++) {
            int amount = amounts[i];
            if (amount == 0) continue;
            Stat<?> statistic = statistics[i];
            String typeName = registryKey(BuiltInRegistries.STAT_TYPE, statistic.getType()).toString();
            String valueName = registryKey(statistic.getType().getRegistry(), statistic.getValue()).toString();
            JsonObject entries;
            if (groups.get(typeName) instanceof JsonObject group) {
                entries = group;
            } else {
                entries = new JsonObject();
                groups.add(typeName, entries);
            }
            entries.addProperty(valueName, amount);
        }
        JsonObject root = new JsonObject();
        root.add("stats", groups);
        root.addProperty("DataVersion", VersionHelper.WORLD_VERSION);
        return GsonHelper.DEFAULT_GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void apply(@NotNull Player player, @NotNull Statistics value) {
        ServerPlayer handle = handle(player);
        ServerStatsCounter counter = handle.getStats();
        Object2IntMap<Stat<?>> current = stats(counter);
        Set<Stat<?>> dirty = (Set<Stat<?>>) ServerStatsCounterProxy.INSTANCE.getDirty(counter);
        Stat<?>[] statistics = value.statistics();
        int[] amounts = value.amounts();
        Object2IntMap<Stat<?>> packet;
        synchronized (current) {
            packet = new Object2IntOpenHashMap<>(Math.max(current.size(), statistics.length));
            // 客户端只更新收到的统计键, 已清除值和待更新键都要发送零值
            for (Stat<?> statistic : current.keySet()) packet.put(statistic, 0);
            for (Stat<?> statistic : dirty) packet.put(statistic, 0);
            current.clear();
            for (int i = 0; i < statistics.length; i++) {
                int amount = amounts[i];
                if (amount == 0) continue;
                Stat<?> statistic = statistics[i];
                current.put(statistic, amount);
                packet.put(statistic, amount);
            }
            // 按原版顺序用 forced stats 覆盖文件值, 玩家线程回退也使用此结果
            for (Map.Entry<Object, Integer> entry : forcedStats().entrySet()) {
                Stat<?> statistic = forcedStatistic(entry.getKey());
                if (statistic == null) continue;
                int amount = entry.getValue();
                current.put(statistic, amount);
                packet.put(statistic, amount);
            }
            dirty.clear();
        }
        handle.connection.send(new ClientboundAwardStatsPacket(packet));
    }

    @SuppressWarnings("unchecked")
    private static <T> Stat<T> statistic(StatType<?> type, Object value) {
        return ((StatType<T>) type).get((T) value);
    }

    private static Object registryKey(Registry<?> registry, Object value) {
        return RegistryProxy.INSTANCE.getKey(registry, value);
    }

    @Nullable
    private static Stat<?> forcedStatistic(Object key) {
        Object value = RegistryProxy.INSTANCE.getValue(BuiltInRegistries.CUSTOM_STAT, key);
        return value == null ? null : statistic(Stats.CUSTOM, value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<Object, Integer> forcedStats() {
        return (Map) SpigotConfig.forcedStats;
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
