package net.momirealms.sparrow.sync.event;

import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.session.SnapshotService.SnapshotSaveOutcome;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletionStage;

/**
 * 快照完成采集、进入玩家串行保存队列前派发. 取消后, 这份快照不会提交落库.
 * <p><strong>处理本事件期间不得再次发起快照保存, 也不得阻塞等待 {@link #completion()}.</strong>
 * 重入保存会被拒绝并向控制台报告责任监听器.
 */
public final class SnapshotSaveEvent extends PlayerEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Snapshot snapshot;
    private final CompletionStage<SnapshotSaveOutcome> completion;
    private boolean cancelled;

    @ApiStatus.Internal
    public SnapshotSaveEvent(@NotNull Player player, @NotNull Snapshot snapshot, @NotNull CompletionStage<SnapshotSaveOutcome> completion) {
        super(player);
        this.snapshot = snapshot;
        this.completion = completion;
    }

    /**
     * 返回准备保存的快照.
     *
     * @return 准备保存的快照
     */
    @NotNull
    public Snapshot snapshot() {
        return this.snapshot;
    }

    /**
     * 返回本次保存的完成阶段.
     * 保存操作会在全部监听器返回后继续, 回调中读取玩家状态前需自行调度到玩家线程.
     *
     * @return 只读的保存完成阶段
     */
    @NotNull
    public CompletionStage<SnapshotSaveOutcome> completion() {
        return this.completion;
    }

    @Override
    public boolean isCancelled() {
        return this.cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    @NotNull
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
