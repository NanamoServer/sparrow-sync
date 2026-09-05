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

    private final Set<Object> delegate;         // PlayerAdvancements 构造时创建的原始 progressChanged Set
    private final Map<Object, Object> progress; // 当前玩家的 holder -> AdvancementProgress Map, 用于排除只有可见性变化的空进度
    private final AdvancementSlots slots;       // 将 holder ID 转换为服务器生命周期稳定槽位的共享布局.
    private final BitSet candidates = new BitSet(); // 单调候选位图, 所有读写都持有当前对象监视器.
    private final BitSet dirty = new BitSet(); // 等待重新采集的槽位, 最后一个 criterion 被撤销也会记录

    @Nullable
    private AdvancementSlots.Layout capturedLayout; // 上次成功更新缓存时的布局, 首次更新前为 null
    private boolean capturing; // 正在进行数据采集的标记, 在 this 锁内修改, 锁外读取 NMS 期间也保持 true
    private AdvancementValue[] cachedValues = EMPTY_VALUES; // 按槽位缓存复制出的进度, null 表示没有可保存的进度
    private Advancements cachedSnapshot = new Advancements(EMPTY_VALUES); // 上次采集的完整本服进度, 数组返回后保持只读

    private volatile boolean complete = true; // 初始 dirty 项和后续真实进度是否都成功映射到稳定槽位.
    private volatile AdvancementValue[] retainedUnknown = EMPTY_VALUES; // 未知进度, 玩家线程整组替换, 异步 capture 只读

    // 包装原 dirty Set, 并在 NMS 首次 flush 前为已经加载的进度播种候选位图.
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
        // 返回值完全沿用原 Set, 候选记录与本次是否首次变 dirty 无关
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
        // flush 只清客户端 dirty 状态, 历史候选继续服务后续保存和远端撤销
        this.delegate.clear();
        // reload 在此调用前清空 progress, 同布局下也要重新核对文件已删除的进度
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
     * 重新采集有变更的成就, 与未变化的缓存条目一起组成完整结果.
     *
     * @param layout 本次采集使用的成就布局
     * @param keepUnknown 是否附上保留的跨服未知进度
     * @return 完整进度, 缓存正由其他采集更新时返回 null, 调用方改走无缓存采集
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
            // 在锁内一起复制候选和本轮 dirty, 清空后的 dirty 继续记录采集期间的新变更
            pending = this.dirty.toLongArray();
            candidates = this.candidates.toLongArray();
            this.dirty.clear();
            this.capturing = true;
        }

        boolean captured = false;
        // NMS 读取在锁外进行, 玩家线程可以继续记录变更
        try {
            if (this.capturedLayout != layout) {
                // 换布局时重建缓存, 已删除的成就会留下空槽
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
                // 失败后把本轮 dirty 加回去, 连同采集期间的新变更一起重试
                if (!captured) {
                    this.dirty.or(BitSet.valueOf(pending));
                }
                this.capturing = false;
            }
        }
    }

    // 把缓存里的有效条目放入新数组, 旧快照继续使用原来的数组
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
                // reload 新增的槽位可能超出当前数组, 留到下次采集读取
                if (slot < this.cachedValues.length && this.cachedValues[slot] != null) {
                    values[count++] = this.cachedValues[slot];
                }
                word &= word - 1L;
            }
        }
        return new Advancements(count == values.length ? values : Arrays.copyOf(values, count));
    }

    // 标记需要重新采集的成就, 包括已撤销为空的进度
    private void record(Object holder) {
        boolean hasProgress = this.progress.get(holder) instanceof AdvancementProgress progress && progress.hasProgress();
        int slot = this.slots.observe(holder);
        // 如果布局上找不到这成就, 代表有极端并发问题, 布局发生多次更新, 先记录并回退到全量模式
        if (slot < 0) {
            this.complete = false;
            return;
        }
        // 候选只增加不删除, 最后一个 criterion 被撤销后仍能参与远端缺失项的清理
        synchronized (this) {
            if (hasProgress) {
                this.candidates.set(slot);
            }
            // 最后一个 criterion 撤销后 hasProgress 为 false, 旧缓存仍要清除
            if (this.candidates.get(slot)) {
                this.dirty.set(slot);
            }
        }
    }
}
