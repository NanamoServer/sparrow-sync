package net.momirealms.sparrow.sync.snapshot;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
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
 * 同步数据类型的注册表, 生命周期与 MC 注册表同款: 插件 onLoad 期开放注册 (第三方在自己的
 * onLoad 中注册, 依赖声明保证顺序), 装配时冻结, 之后只读, 应用顺序由依赖关系的拓扑排序给出.
 */
public final class DataRegistry {
    private final Map<DataKey, DataDeclaration> declarations = new ConcurrentHashMap<>();
    private volatile boolean frozen;

    /**
     * 注册一类同步数据.
     *
     * @throws IllegalStateException 当注册表已冻结, 或该数据标识已被注册时
     */
    public void register(@NotNull DataDeclaration declaration) {
        if (this.frozen) {
            throw new IllegalStateException("data registry is frozen, register during plugin onLoad");
        }
        DataDeclaration existing = this.declarations.putIfAbsent(declaration.key(), declaration);
        if (existing != null) {
            throw new IllegalStateException("data key already registered: " + declaration.key());
        }
    }

    /** 关闭注册窗口, 此后一切 register 调用抛出. */
    public void freeze() {
        this.frozen = true;
    }

    public boolean frozen() {
        return this.frozen;
    }

    @Nullable
    public DataDeclaration declaration(@NotNull DataKey key) {
        return this.declarations.get(key);
    }

    public boolean registered(@NotNull DataKey key) {
        return this.declarations.containsKey(key);
    }

    /** 全部已注册声明的只读视图, 装配期从这里收割带行为的类型. */
    @NotNull
    public Collection<DataDeclaration> declarations() {
        return Collections.unmodifiableCollection(this.declarations.values());
    }

    /**
     * 计算全部已注册类型的应用顺序, 依赖者排在其依赖之后, 同层按 key 字典序保证结果稳定.
     *
     * @return 按应用先后排列的数据标识
     * @throws IllegalStateException 当依赖关系存在环时, 异常信息列出环上的全部节点
     */
    @NotNull
    public List<DataKey> applyOrder() {
        // 建图. 入度为已注册依赖数, 未注册的依赖直接忽略
        Map<DataKey, Integer> inDegree = new HashMap<>();
        Map<DataKey, List<DataKey>> dependents = new HashMap<>();
        for (DataDeclaration declaration : this.declarations.values()) {
            int degree = 0;
            for (DataKey dependency : declaration.dependencies()) {
                if (!this.declarations.containsKey(dependency)) continue;
                degree++;
                dependents.computeIfAbsent(dependency, key -> new ArrayList<>()).add(declaration.key());
            }
            inDegree.put(declaration.key(), degree);
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
        TreeSet<DataKey> dependencies = new TreeSet<>(this.declarations.get(current).dependencies());
        for (DataKey dependency : dependencies) {
            if (!this.declarations.containsKey(dependency)) continue;
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
