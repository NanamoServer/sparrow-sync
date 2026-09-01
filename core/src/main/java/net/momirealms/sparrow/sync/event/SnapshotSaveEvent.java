package net.momirealms.sparrow.sync.event;

import net.momirealms.sparrow.sync.snapshot.Snapshot;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * 快照完成采集、进入玩家串行保存队列前派发. 取消后, 这份快照不会提交落库.
 */
public final class SnapshotSaveEvent extends PlayerEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Snapshot snapshot;
    private boolean cancelled;

    @ApiStatus.Internal
    public SnapshotSaveEvent(@NotNull Player player, @NotNull Snapshot snapshot) {
        super(player);
        this.snapshot = snapshot;
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
