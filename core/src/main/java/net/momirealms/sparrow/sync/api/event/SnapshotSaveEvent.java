package net.momirealms.sparrow.sync.api.event;

import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotSaveResult;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletionStage;

/**
 * 快照完成编码与地图准备后、提交存储前在玩家串行线程派发.
 * 监听器返回后确认的取消会结束保存请求, 停服超时已先取得收尾权时, 完整快照可能已决定暂存, 此时迟到的取消事件不会撤销保存.
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

    @NotNull
    public String playerName() {
        return this.playerName;
    }

    @NotNull
    public Snapshot snapshot() {
        return this.snapshot;
    }

    /**
     * 返回本次保存的最终完成结果, 表示“这次保存请求已经有最终结果”, 不表示“数据库已经完成保存”.
     * <p><strong>处理本事件期间不得阻塞等待 {@link #completion()}.</strong>
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
