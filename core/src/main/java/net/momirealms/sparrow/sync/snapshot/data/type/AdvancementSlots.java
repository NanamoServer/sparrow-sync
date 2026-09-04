package net.momirealms.sparrow.sync.snapshot.data.type;

import net.minecraft.server.MinecraftServer;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.AdvancementHolderProxy;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 为 advancement ID 分配跨 reload 稳定的整数槽位, 供玩家对象用BitSet记录有过进度的数据.
 * <p>已删除的 ID 继续占用原槽位且 holder 为 null, 因此在线玩家已有的BitSet记录不需要重排.
 * 布局完成后通过 volatile 字段一次发布, 异步 capture 只读取已经完整构造的快照.
 */
final class AdvancementSlots {
    private final Supplier<Map<?, ?>> advancements; // 延迟读取当前服务端 advancement Map
    private volatile Layout layout = new Layout(null, new Object[0], Map.of()); // 当前已发布布局, source 为 null 表示布局尚未首次编译.

    AdvancementSlots() {
        this(() -> MinecraftServer.getServer().getAdvancements().advancements);
    }

    AdvancementSlots(Supplier<Map<?, ?>> advancements) {
        this.advancements = advancements;
    }

    /**
     * 返回与当前 advancement Map 对应的布局, 必要时先完成一次换代编译.
     *
     * @return 稳定布局, 构建期间 Map 连续变化时返回 null
     */
    @Nullable
    Layout current() {
        Map<?, ?> source = this.advancements.get();
        Layout current = this.layout;
        if (current.source() == source) return current;
        return this.rebuild();
    }

    /**
     * 从一个稳定的 advancement Map 编译新布局并发布.
     * 新布局继承旧的 ID -> slot 映射, 布局以服务端 advancement Map 的引用身份区分版本.
     *
     * @return 编译完成的布局, 两次尝试都撞上 Map 换代时返回 null
     */
    @Nullable
    private synchronized Layout rebuild() {
        // 一次尝试只接受从读取开始到发布前都保持同一引用的 Map
        for (int attempt = 0; attempt < 2; attempt++) {
            Map<?, ?> source = this.advancements.get();
            Layout current = this.layout;
            if (current.source() == source) return current;

            // 旧 ID 沿用原槽位, 新 ID 从末尾追加
            Map<Object, Integer> slots = new HashMap<>(current.slots());
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                slots.putIfAbsent(entry.getKey(), slots.size());
            }
            // 按完整历史槽位数建表, 当前 generation 已删除的 ID 自然保留为 null
            Object[] holders = new Object[slots.size()];
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                holders[slots.get(entry.getKey())] = entry.getValue();
            }
            // 扫描期间发生 reload 时丢弃候选布局, 防止新旧 holder 混入同一份快照
            if (this.advancements.get() != source) continue;

            Layout rebuilt = new Layout(source, holders, Map.copyOf(slots));
            this.layout = rebuilt;
            return rebuilt;
        }
        return null;
    }

    /**
     * 把 NMS 报告的 holder 转换为当前稳定槽位.
     * 无法匹配时由玩家 tracker 永久回退到 dense 路径, 保证候选集合不会漏项.
     *
     * @param holder NMS 加入 progressChanged 的 AdvancementHolder
     * @return holder 对应槽位, 布局不稳定或 ID 不存在时返回 -1
     */
    int observe(Object holder) {
        Layout current = this.current();
        return current == null ? -1 : current.slot(AdvancementHolderProxy.INSTANCE.id(holder));
    }

    /**
     * 一次完整的 advancement 布局快照.
     *
     * @param source 生成此布局的服务端 Map 引用, 初始未编译布局为 null
     * @param holders slot -> 当前 holder, 已删除 ID 的位置为 null
     * @param slots ID -> 生命周期稳定 slot, 包含已经删除的历史 ID
     */
    record Layout(@Nullable Map<?, ?> source, Object[] holders, Map<Object, Integer> slots) {

        // 根据槽位查询 advancement ID
        @Nullable
        Object holder(int slot) {
            if (slot < 0 || slot >= this.holders.length) return null;
            return this.holders[slot];
        }

        // 查询 advancement ID 的槽位, 从未出现过的 ID 返回 -1.
        int slot(Object id) {
            Integer slot = this.slots.get(id);
            return slot == null ? -1 : slot;
        }
    }
}
