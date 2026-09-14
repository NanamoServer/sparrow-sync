package net.momirealms.sparrow.sync.snapshot.data;

import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

public final class DataRegistry {
    private final Map<DataKey, PlayerDataType<?>> types = new ConcurrentHashMap<>();
    private Set<DataKey> unknownDrops = ConcurrentHashMap.newKeySet();
    private DataKey[] orderedKeys = new DataKey[0];
    private PlayerDataType<?>[] orderedTypes = new PlayerDataType<?>[0];
    private NativePlayerDataType<?>[] orderedNativeTypes = new NativePlayerDataType<?>[0];
    private int[] syncCaptureSlots = new int[0];
    private int[] asyncCaptureSlots = new int[0];
    private Map<DataKey, Integer> slots = Map.of();
    private List<DataKey> applyOrder = List.of();
    private volatile boolean frozen;

    public DataRegistry() {
        for (DataKey key : PluginConfig.synchronization$discardUnknownData()) {
            this.registerUnknownDrop(key);
        }
    }

    /**
     * 注册一类同步数据.
     *
     * @throws IllegalStateException 当注册表已冻结, 或该数据标识已被注册时
     */
    public void register(@NotNull PlayerDataType<?> type) {
        if (this.frozen) {
            throw new IllegalStateException("data registry is frozen, register during onLoad or onEnable");
        }
        PlayerDataType<?> existing = this.types.putIfAbsent(type.key(), type);
        if (existing != null) {
            throw new IllegalStateException("data key already registered: " + type.key());
        }
    }

    /**
     * 登记未知数据丢弃名单, 本服已注册的类型仍正常应用.
     * @throws IllegalStateException 注册表已冻结时
     */
    public void registerUnknownDrop(@NotNull DataKey key) {
        if (this.frozen) {
            throw new IllegalStateException("data registry is frozen, register during onLoad or onEnable");
        }
        this.unknownDrops.add(key);
    }

    /** 类型未注册且在丢弃名单中时返回 true. */
    public boolean shouldDropUnknown(@NotNull DataKey key) {
        return !this.registered(key) && this.unknownDrops.contains(key);
    }

    public void freeze() {
        if (this.frozen) return;

        List<DataKey> order = this.buildApplyOrder();
        int size = order.size();
        DataKey[] keys = new DataKey[size];
        PlayerDataType<?>[] types = new PlayerDataType<?>[size];
        NativePlayerDataType<?>[] nativeTypes = new NativePlayerDataType<?>[size];
        int[] syncSlots = new int[size];
        int[] asyncSlots = new int[size];
        int syncCount = 0;
        int asyncCount = 0;
        Map<DataKey, Integer> slots = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            DataKey key = order.get(i);
            keys[i] = key;
            types[i] = this.types.get(key);
            if (types[i].supportsAsyncCapture()) {
                asyncSlots[asyncCount++] = i;
            } else {
                syncSlots[syncCount++] = i;
            }
            if (types[i] instanceof NativePlayerDataType<?> nativeType) nativeTypes[i] = nativeType;
            slots.put(key, i);
        }
        this.orderedKeys = keys;
        this.orderedTypes = types;
        this.orderedNativeTypes = nativeTypes;
        this.syncCaptureSlots = Arrays.copyOf(syncSlots, syncCount);
        this.asyncCaptureSlots = Arrays.copyOf(asyncSlots, asyncCount);
        this.slots = Map.copyOf(slots);
        this.applyOrder = List.copyOf(order);
        this.unknownDrops = Set.copyOf(this.unknownDrops);
        this.frozen = true;
    }

    public boolean frozen() {
        return this.frozen;
    }

    /** 分阶段采集中需要在玩家线程读取的槽位, <strong>数组只读</strong>. */
    public int @NotNull [] syncCaptureSlots() {
        return this.syncCaptureSlots;
    }

    /** 分阶段采集中可在串行线程读取的槽位, <strong>数组只读</strong>. */
    public int @NotNull [] asyncCaptureSlots() {
        return this.asyncCaptureSlots;
    }

    @Nullable
    public PlayerDataType<?> type(@NotNull DataKey key) {
        return this.types.get(key);
    }

    public boolean registered(@NotNull DataKey key) {
        return this.types.containsKey(key);
    }

    /** 全部已注册数据类型的只读视图. */
    @NotNull
    public Collection<PlayerDataType<?>> types() {
        if (this.frozen) {
            return List.of(this.orderedTypes);
        }
        return Collections.unmodifiableCollection(this.types.values());
    }

    public int size() {
        return this.frozen ? this.orderedTypes.length : this.types.size();
    }

    /** 返回槽位对应的数据标识. */
    @NotNull
    public DataKey keyAt(int slot) {
        return this.orderedKeys[slot];
    }

    /** 返回槽位对应的数据类型. */
    @NotNull
    public PlayerDataType<?> typeAt(int slot) {
        return this.orderedTypes[slot];
    }

    /** 返回登录前应用实现, 只支持 Join 的类型返回 null. */
    @Nullable
    public NativePlayerDataType<?> nativeTypeAt(int slot) {
        return this.orderedNativeTypes[slot];
    }

    /** 返回类型对应的槽位, 未注册时返回 -1. */
    public int slot(@NotNull DataKey key) {
        Integer slot = this.slots.get(key);
        return slot == null ? -1 : slot;
    }

    /**
     * 按依赖排序类型, 同层按 key 字典序排列.
     * @throws IllegalStateException 依赖成环时, 错误中列出环上的节点
     */
    @NotNull
    public List<DataKey> applyOrder() {
        if (this.frozen) return this.applyOrder;
        return this.buildApplyOrder();
    }

    @NotNull
    private List<DataKey> buildApplyOrder() {
        // 只统计已注册的依赖
        Map<DataKey, Integer> inDegree = new HashMap<>();
        Map<DataKey, List<DataKey>> dependents = new HashMap<>();
        for (PlayerDataType<?> type : this.types.values()) {
            int degree = 0;
            for (DataKey dependency : type.dependencies()) {
                if (!this.types.containsKey(dependency)) continue;
                degree++;
                dependents.computeIfAbsent(dependency, key -> new ArrayList<>()).add(type.key());
            }
            inDegree.put(type.key(), degree);
        }

        // 按拓扑顺序输出, 同时就绪的类型按 key 字典序选择
        PriorityQueue<DataKey> ready = new PriorityQueue<>();
        for (Map.Entry<DataKey, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) ready.add(entry.getKey());
        }
        List<DataKey> order = new ArrayList<>(inDegree.size());
        while (!ready.isEmpty()) {
            DataKey key = ready.poll();
            order.add(key);
            List<DataKey> next = dependents.get(key);
            if (next == null) continue;
            for (int i = 0; i < next.size(); i++) {
                DataKey dependent = next.get(i);
                int remaining = inDegree.merge(dependent, -1, Integer::sum);
                if (remaining == 0) ready.add(dependent);
            }
        }

        // 尚有未输出节点时检查依赖环
        if (order.size() < inDegree.size()) {
            throw new IllegalStateException("dependency cycle detected: " + this.describeCycles(inDegree.keySet(), order));
        }
        return order;
    }

    // 从未输出节点中找出闭合的依赖路径
    private String describeCycles(Set<DataKey> nodes, List<DataKey> ordered) {
        TreeSet<DataKey> remaining = new TreeSet<>(nodes);
        remaining.removeAll(ordered);
        StringJoiner report = new StringJoiner("; ");
        while (!remaining.isEmpty()) {
            DataKey start = remaining.pollFirst();
            List<DataKey> path = new ArrayList<>();
            path.add(start);
            if (!this.findCycle(start, start, new HashSet<>(), path)) continue;

            remaining.removeAll(path);
            StringJoiner cycle = new StringJoiner(" -> ");
            for (int i = 0; i < path.size(); i++) cycle.add(path.get(i).asString());
            report.add(cycle.add(start.asString()).toString());
        }
        return report.toString();
    }

    private boolean findCycle(DataKey start, DataKey current, Set<DataKey> onPath, List<DataKey> path) {
        onPath.add(current);
        TreeSet<DataKey> dependencies = new TreeSet<>(this.types.get(current).dependencies());
        for (DataKey dependency : dependencies) {
            if (!this.types.containsKey(dependency)) continue;
            if (dependency.equals(start)) return true;
            if (onPath.contains(dependency)) continue;

            path.add(dependency);
            if (this.findCycle(start, dependency, onPath, path)) return true;
            path.removeLast();
        }
        onPath.remove(current);
        return false;
    }
}
