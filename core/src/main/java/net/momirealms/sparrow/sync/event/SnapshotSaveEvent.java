package net.momirealms.sparrow.sync.event;

import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.session.SnapshotSaveResult;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletionStage;

/**
 * 快照完成编码、提交存储前在串行线程派发. 取消后, 这份快照不会提交落库.
 * <p><strong>处理本事件期间不得阻塞等待 {@link #completion()}.</strong>
 */
public final class SnapshotSaveEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final String playerName;
    private final Snapshot snapshot;
    private final CompletionStage<SnapshotSaveResult> completion;
    private boolean cancelled;

    @ApiStatus.Internal
    public SnapshotSaveEvent(@NotNull String playerName, @NotNull Snapshot snapshot, @NotNull CompletionStage<SnapshotSaveResult> completion) {
        super(true);
        this.playerName = playerName;
        this.snapshot = snapshot;
        this.completion = completion;
    }

    /**
     * 返回保存请求接受时记录的玩家名.
     *
     * @return 玩家名
     */
    @NotNull
    public String playerName() {
        return this.playerName;
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
     * 保存操作会在全部监听器返回后继续.
     *
     * @return 只读的保存完成阶段
     */
    @NotNull
    public CompletionStage<SnapshotSaveResult> completion() {
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
