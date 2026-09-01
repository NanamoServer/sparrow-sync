package net.momirealms.sparrow.sync.event;

import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 快照成功应用到玩家后派发, 并列出实际应用和跳过的数据类型.
 */
public final class SyncCompleteEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Snapshot snapshot;
    private final List<DataKey> applied;
    private final List<DataKey> skipped;

    @ApiStatus.Internal
    public SyncCompleteEvent(@NotNull Player player, @NotNull Snapshot snapshot, @NotNull List<DataKey> applied, @NotNull List<DataKey> skipped) {
        super(player);
        this.snapshot = snapshot;
        this.applied = List.copyOf(applied);
        this.skipped = List.copyOf(skipped);
    }

    /**
     * 返回已经完成应用的快照.
     *
     * @return 已经完成应用的快照
     */
    @NotNull
    public Snapshot snapshot() {
        return this.snapshot;
    }

    /**
     * 返回成功写入玩家的数据类型.
     *
     * @return 不可变的数据类型列表
     */
    @NotNull
    public List<DataKey> applied() {
        return this.applied;
    }

    /**
     * 返回解码或应用时跳过的数据类型.
     *
     * @return 不可变的数据类型列表
     */
    @NotNull
    public List<DataKey> skipped() {
        return this.skipped;
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
