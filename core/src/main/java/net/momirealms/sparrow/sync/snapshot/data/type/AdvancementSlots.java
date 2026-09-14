package net.momirealms.sparrow.sync.snapshot.data.type;

import net.minecraft.server.MinecraftServer;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.AdvancementHolderProxy;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 为成就 ID 分配固定槽位, reload 后沿用旧编号.
 * 删除的 ID 仍保留槽位, holder 为 null; 完整布局通过 volatile 一次发布.
 */
final class AdvancementSlots {
    private final Supplier<Map<?, ?>> advancements; // 延迟获取服务端当前成就 Map
    private volatile Layout layout = new Layout(null, new Object[0], Map.of()); // 当前布局, source 为 null 时尚未构建

    AdvancementSlots() {
        this(() -> MinecraftServer.getServer().getAdvancements().advancements);
    }

    AdvancementSlots(Supplier<Map<?, ?>> advancements) {
        this.advancements = advancements;
    }

    /** 返回当前成就 Map 对应的布局, 必要时重建; Map 连续变化时返回 null. */
    @Nullable
    Layout current() {
        Map<?, ?> source = this.advancements.get();
        Layout current = this.layout;
        if (current.source() == source) return current;
        return this.rebuild();
    }

    /**
     * 重建布局并沿用原槽位, 按服务端 Map 引用判断版本.
     * @return 新布局, 两次尝试都遇到 Map 变化时返回 null
     */
    @Nullable
    private synchronized Layout rebuild() {
        // 从读取到发布期间, 服务端 Map 引用必须保持不变
        for (int attempt = 0; attempt < 2; attempt++) {
            Map<?, ?> source = this.advancements.get();
            Layout current = this.layout;
            if (current.source() == source) return current;

            // 旧 ID 沿用原槽位, 新 ID 从末尾追加
            Map<Object, Integer> slots = new HashMap<>(current.slots());
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                slots.putIfAbsent(entry.getKey(), slots.size());
            }
            // 按历史槽位总数分配数组, 已删除 ID 的位置为 null
            Object[] holders = new Object[slots.size()];
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                holders[slots.get(entry.getKey())] = entry.getValue();
            }
            // 扫描期间发生 reload 时丢弃本次结果, 重新读取
            if (this.advancements.get() != source) continue;

            Layout rebuilt = new Layout(source, holders, Map.copyOf(slots));
            this.layout = rebuilt;
            return rebuilt;
        }
        return null;
    }

    /**
     * 查询 holder 的固定槽位, 无法确定时由跟踪器改为全量采集.
     * @return 槽位编号, 布局不稳定或 ID 不存在时为 -1
     */
    int observe(Object holder) {
        Layout current = this.current();
        return current == null ? -1 : current.slot(AdvancementHolderProxy.INSTANCE.id(holder));
    }

    /**
     * 成就布局快照.
     * @param source 对应的服务端 Map, 尚未构建时为 null
     * @param holders 各槽位的 holder, 已删除项为 null
     * @param slots 成就 ID 对应的固定槽位, 包含已删除 ID
     */
    record Layout(@Nullable Map<?, ?> source, Object[] holders, Map<Object, Integer> slots) {

        // 根据槽位查询 advancement ID
        @Nullable
        Object holder(int slot) {
            if (slot < 0 || slot >= this.holders.length) return null;
            return this.holders[slot];
        }

        // 查询成就槽位, 从未出现过的 ID 返回 -1
        int slot(Object id) {
            Integer slot = this.slots.get(id);
            return slot == null ? -1 : slot;
        }
    }
}
