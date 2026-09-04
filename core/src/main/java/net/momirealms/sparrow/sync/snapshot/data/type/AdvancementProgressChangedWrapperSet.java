package net.momirealms.sparrow.sync.snapshot.data.type;

import net.minecraft.advancements.AdvancementProgress;
import org.jspecify.annotations.NonNull;

import java.util.AbstractSet;
import java.util.BitSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

final class AdvancementProgressChangedWrapperSet extends AbstractSet<Object> {
    private final Set<Object> delegate;         // PlayerAdvancements 构造时创建的原始 progressChanged Set
    private final Map<Object, Object> progress; // 当前玩家的 holder -> AdvancementProgress Map, 用于排除只有可见性变化的空进度
    private final AdvancementSlots slots;       // 将 holder ID 转换为服务器生命周期稳定槽位的共享布局.
    private final BitSet candidates = new BitSet(); // 单调候选位图, 所有读写都持有当前对象监视器.

    private volatile boolean complete = true; // 初始 dirty 项和后续真实进度是否都成功映射到稳定槽位.

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
    }

    boolean complete() {
        return this.complete;
    }

    synchronized long[] candidates() {
        return this.candidates.toLongArray();
    }

    // 检查和 holder 上是否有真实的成就进度, 如果有就翻转 BitSet, 代表这些进度是序列化真正关心的进度.
    private void record(Object holder) {
        // visibility 更新也会进入 dirty Set, 空进度不持久化
        if (!(this.progress.get(holder) instanceof AdvancementProgress advancementProgress) || !advancementProgress.hasProgress()) return;
        int slot = this.slots.observe(holder);
        // 如果布局上找不到这成就, 代表有极端并发问题, 布局发生多次更新, 先记录并回退到全量模式
        if (slot < 0) {
            this.complete = false;
            return;
        }
        // 候选只增加不删除, 最后一个 criterion 被撤销后仍能参与远端缺失项的清理
        synchronized (this) {
            this.candidates.set(slot);
        }
    }
}
