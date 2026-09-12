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

/**
 * 同步数据类型的注册表, 插件 onLoad 与 onEnable 期开放注册.
 * ServerLoadEvent 时冻结, 之后只读, 应用顺序由依赖关系的拓扑排序给出.
 */
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
     * 将类型加入本服的未知数据丢弃名单, 供配置加载与第三方插件启动时注册.
     * 该类型在本服已注册时仍正常应用.
     *
     * @param key 本服未注册时需要丢弃的数据类型
     * @throws IllegalStateException 当注册表已经冻结时
     */
    public void registerUnknownDrop(@NotNull DataKey key) {
        if (this.frozen) {
            throw new IllegalStateException("data registry is frozen, register during onLoad or onEnable");
        }
        this.unknownDrops.add(key);
    }

    /**
     * 判断本服在读取到未知类型的数据时, 是否丢弃此类型数据.
     *
     * @param key 快照中的类型标识
     * @return 本服未注册该类型且丢弃名单包含它时为 true
     */
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

    /** 在线分阶段采集保存中的玩家线程槽位, <strong>返回数组只读</strong>. */
    public int @NotNull [] syncCaptureSlots() {
        return this.syncCaptureSlots;
    }

    /** 在线分阶段采集保存中的串行线程槽位, <strong>返回数组只读</strong>. */
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

    /** 固定的数据类型索引布局中的数据类型数量. */
    public int size() {
        return this.frozen ? this.orderedTypes.length : this.types.size();
    }

    /**
     * 返回固定的数据类型索引布局中指定槽位的数据标识.
     *
     * @param slot 拓扑顺序槽位
     */
    @NotNull
    public DataKey keyAt(int slot) {
        return this.orderedKeys[slot];
    }

    /**
     * 返回固定的数据类型索引布局中指定槽位的数据类型.
     *
     * @param slot 拓扑顺序槽位
     */
    @NotNull
    public PlayerDataType<?> typeAt(int slot) {
        return this.orderedTypes[slot];
    }

    /** 返回数据类型槽位的登录数据源写入实现, join-only 类型返回 null. */
    @Nullable
    public NativePlayerDataType<?> nativeTypeAt(int slot) {
        return this.orderedNativeTypes[slot];
    }

    /**
     * 查询数据标识在固定的数据类型索引布局中的槽位, 未注册时返回 {@code -1}.
     */
    public int slot(@NotNull DataKey key) {
        Integer slot = this.slots.get(key);
        return slot == null ? -1 : slot;
    }

    /**
     * 计算全部已注册类型的应用顺序, 依赖者排在其依赖之后, 同层按 key 字典序保证结果稳定.
     *
     * @return 按应用先后排列的数据标识
     * @throws IllegalStateException 当依赖关系存在环时, 异常信息列出环上的全部节点
     */
    @NotNull
    public List<DataKey> applyOrder() {
        if (this.frozen) return this.applyOrder;
        return this.buildApplyOrder();
    }

    @NotNull
    private List<DataKey> buildApplyOrder() {
        // 建图. 入度为已注册依赖数, 未注册的依赖直接忽略
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

        // Kahn 拓扑排序, 就绪集用字典序优先队列消除注册顺序的影响
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

        // 有节点没被输出, 说明存在依赖环
        if (order.size() < inDegree.size()) {
            throw new IllegalStateException("dependency cycle detected: " + this.describeCycles(inDegree.keySet(), order));
        }
        return order;
    }

    // 逐个检查未输出节点, 找出包含它的闭合路径
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
