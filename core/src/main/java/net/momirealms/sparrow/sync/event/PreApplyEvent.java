package net.momirealms.sparrow.sync.event;

import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 快照完成解码、写入玩家前派发. 监听器可以修改本次应用使用的解码数据.
 */
public final class PreApplyEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Snapshot snapshot;
    private final Map<DataKey, Object> decoded;

    @ApiStatus.Internal
    public PreApplyEvent(@NotNull Player player, @NotNull Snapshot snapshot, @NotNull Map<DataKey, Object> decoded) {
        super(player);
        this.snapshot = snapshot;
        this.decoded = new LinkedHashMap<>(decoded);
    }

    /**
     * 返回正在应用的原始快照.
     *
     * @return 正在应用的快照
     */
    @NotNull
    public Snapshot snapshot() {
        return this.snapshot;
    }

    /**
     * 返回本次应用使用的可变解码数据.
     * 监听器可以替换或删除已有值, 也可以为本次解码跳过的已注册类型补值.
     * <strong>写入值必须符合对应 PlayerDataType 的解码结果类型</strong>.
     * 事件返回后会丢弃未注册的键与 null 值.
     *
     * @return 事件独占的可变解码数据
     */
    @NotNull
    public Map<DataKey, Object> decoded() {
        return this.decoded;
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
