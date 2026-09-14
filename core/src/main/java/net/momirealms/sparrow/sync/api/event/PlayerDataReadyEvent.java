package net.momirealms.sparrow.sync.api.event;

import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * onJoin 中登录数据应用流程结束、会话进入 ACTIVE 后, 在玩家所属线程同步派发一次.
 * 没有历史快照的新玩家也会触发, 登录失败或中止时不触发; 监听器可发起 API 操作.
 */
public final class PlayerDataReadyEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final @Nullable Snapshot snapshot;
    private final List<DataKey> skipped;

    @ApiStatus.Internal
    public PlayerDataReadyEvent(@NotNull Player player, @Nullable Snapshot snapshot, @NotNull List<DataKey> skipped) {
        super(player);
        this.snapshot = snapshot;
        this.skipped = List.copyOf(skipped);
    }

    /**
     * 返回本次登录的来源快照, <strong>调用方须只读使用其数据内容</strong>.
     *
     * @return 来源快照, 没有历史记录的新玩家为 null
     */
    @Nullable
    public Snapshot snapshot() {
        return this.snapshot;
    }

    /**
     * 返回本次登录应用流程记录的跳过类型.
     *
     * @return 不可变的跳过列表
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
