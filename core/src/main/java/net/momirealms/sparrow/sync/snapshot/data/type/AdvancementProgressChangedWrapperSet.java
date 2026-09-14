package net.momirealms.sparrow.sync.snapshot.data.type;

import net.minecraft.advancements.AdvancementProgress;
import net.momirealms.sparrow.sync.snapshot.data.type.AdvancementsDataType.AdvancementValue;
import net.momirealms.sparrow.sync.snapshot.data.type.AdvancementsDataType.Advancements;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

import java.util.AbstractSet;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

final class AdvancementProgressChangedWrapperSet extends AbstractSet<Object> {
    private static final AdvancementValue[] EMPTY_VALUES = new AdvancementValue[0];

    private final Set<Object> delegate;         // 原版创建的 progressChanged 集合
    private final Map<Object, Object> progress; // 玩家当前进度, 用于排除只有可见性变化的空记录
    private final AdvancementSlots slots;       // 全服共享的成就 ID 与固定槽位映射
    private final BitSet candidates = new BitSet(); // 记录曾有进度的槽位, 读写时锁住当前对象
    private final BitSet dirty = new BitSet(); // 待重新采集的槽位, 最后一个条件被撤销时也记录

    @Nullable
    private AdvancementSlots.Layout capturedLayout; // 缓存对应的布局, 首次采集前为 null
    private boolean capturing; // 采集期间为 true, 在 this 锁内修改, 锁外读取 NMS 时仍保留标记
    private AdvancementValue[] cachedValues = EMPTY_VALUES; // 按槽位缓存进度副本, null 表示没有进度
    private Advancements cachedSnapshot = new Advancements(EMPTY_VALUES); // 上次完整采集结果, 数组只读

    private volatile boolean complete = true; // 是否已记录所有初始及后续变更的槽位
    private volatile AdvancementValue[] retainedUnknown = EMPTY_VALUES; // 本服不认识的进度, 玩家线程整组替换, 异步采集只读

    // 包装原版变更集合, 首次 flush 前记录已加载进度的槽位
    AdvancementProgressChangedWrapperSet(Set<Object> delegate, Map<Object, Object> progress, AdvancementSlots slots) {
        this.delegate = delegate;
        this.progress = progress;
        this.slots = slots;
        for (Object holder : delegate) {
            this.record(holder);
        }
    }

    @NonNull
    @Override
    public Iterator<Object> iterator() {
        return this.delegate.iterator();
    }

    @Override
    public int size() {
        return this.delegate.size();
    }

    @Override
    public boolean contains(Object value) {
        return this.delegate.contains(value);
    }

    @Override
    public boolean add(Object holder) {
        // 返回值沿用原 Set, 每次调用都记录对应槽位
        boolean changed = this.delegate.add(holder);
        this.record(holder);
        return changed;
    }

    @Override
    public boolean remove(Object value) {
        return this.delegate.remove(value);
    }

    @Override
    public void clear() {
        // flush 清除客户端更新标记, 保存和撤销仍需保留历史槽位
        this.delegate.clear();
        // reload 会先清空 progress, 布局未变时也要清除文件中已删除的进度
        if (this.progress.isEmpty()) {
            synchronized (this) {
                this.dirty.or(this.candidates);
            }
        }
    }

    boolean complete() {
        return this.complete;
    }

    synchronized long[] candidates() {
        return this.candidates.toLongArray();
    }

    AdvancementValue[] retainedUnknown() {
        return this.retainedUnknown;
    }

    void retainedUnknown(AdvancementValue[] values) {
        this.retainedUnknown = values;
    }

    /**
     * 重新采集有变更的成就, 与缓存合并为完整结果.
     * @return 完整进度, 其他任务正在更新缓存时返回 null, 由调用方直接采集
     */
    @Nullable
    Advancements capture(AdvancementSlots.Layout layout, boolean keepUnknown) {
        long[] pending;
        long[] candidates;
        synchronized (this) {
            if (this.capturing) return null;
            if (this.capturedLayout == layout && this.dirty.isEmpty() && (!keepUnknown || this.retainedUnknown.length == 0)) {
                return this.cachedSnapshot;
            }
            if (this.capturedLayout != layout) {
                this.dirty.or(this.candidates);
            }
            // 锁内复制候选和变更集合, 随后新增的变更留给下次采集
            pending = this.dirty.toLongArray();
            candidates = this.candidates.toLongArray();
            this.dirty.clear();
            this.capturing = true;
        }

        boolean captured = false;
        // 锁外读取 NMS, 玩家线程可以继续记录变更
        try {
            if (this.capturedLayout != layout) {
                // 布局变化时重建缓存, 已删除成就的槽位留空
                this.cachedValues = new AdvancementValue[layout.holders().length];
            }
            if (pending.length != 0 || this.capturedLayout != layout) {
                for (int i = 0; i < pending.length; i++) {
                    long word = pending[i];
                    while (word != 0L) {
                        int slot = (i << 6) + Long.numberOfTrailingZeros(word);
                        Object holder = layout.holder(slot);
                        if (holder != null) {
                            this.cachedValues[slot] = this.progress.get(holder) instanceof AdvancementProgress progress
                                    ? AdvancementsDataType.captureProgress(holder, progress)
                                    : null;
                        }
                        word &= word - 1L;
                    }
                }
                this.cachedSnapshot = this.snapshot(candidates);
                this.capturedLayout = layout;
            }
            captured = true;
            return keepUnknown
                    ? AdvancementsDataType.mergeRetained(this.cachedSnapshot, this.retainedUnknown, layout, candidates)
                    : this.cachedSnapshot;
        } finally {
            synchronized (this) {
                // 失败时将本轮变更加回, 与期间新增的变更一起重试
                if (!captured) {
                    this.dirty.or(BitSet.valueOf(pending));
                }
                this.capturing = false;
            }
        }
    }

    // 有效缓存项组成新数组, 旧快照保留原数组
    private Advancements snapshot(long[] candidates) {
        int capacity = 0;
        for (int i = 0; i < candidates.length; i++) {
            capacity += Long.bitCount(candidates[i]);
        }
        AdvancementValue[] values = new AdvancementValue[capacity];
        int count = 0;
        for (int i = 0; i < candidates.length; i++) {
            long word = candidates[i];
            while (word != 0L) {
                int slot = (i << 6) + Long.numberOfTrailingZeros(word);
                // reload 新增的槽位可能超出当前数组, 下次再采集
                if (slot < this.cachedValues.length && this.cachedValues[slot] != null) {
                    values[count++] = this.cachedValues[slot];
                }
                word &= word - 1L;
            }
        }
        return new Advancements(count == values.length ? values : Arrays.copyOf(values, count));
    }

    // 记录待重新采集的成就, 包括进度全部撤销的情况
    private void record(Object holder) {
        boolean hasProgress = this.progress.get(holder) instanceof AdvancementProgress progress && progress.hasProgress();
        int slot = this.slots.observe(holder);
        // 找不到槽位时记录不完整状态, 后续改为全量采集
        if (slot < 0) {
            this.complete = false;
            return;
        }
        // 进度全部撤销后仍保留候选槽位, 应用远端快照时也需处理这些成就
        synchronized (this) {
            if (hasProgress) {
                this.candidates.set(slot);
            }
            // 最后一个条件撤销后 hasProgress 为 false, 仍需清除旧缓存
            if (this.candidates.get(slot)) {
                this.dirty.set(slot);
            }
        }
    }
}
