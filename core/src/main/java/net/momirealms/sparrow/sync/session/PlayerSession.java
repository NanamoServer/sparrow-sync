package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.data.SnapshotApplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public final class PlayerSession {
    private final UUID uuid;
    private final String playerName;

    // todo 也算是一种networkUser, 在join之后 绑定玩家(弱引用也可以) Player, quit 或者close/断联之后手动清理弱引用. 下次如果玩家重进服也检查清理旧的close的对象.
    private SessionState state = SessionState.PREPARING;
    private long lastTransitionAt = System.currentTimeMillis();
    private SnapshotApplier.PreparedSnapshot.Ready prepared;

    PlayerSession(@NotNull UUID player, @NotNull String playerName) {
        this.uuid = player;
        this.playerName = playerName;
    }

    @NotNull
    public UUID uuid() {
        return this.uuid;
    }

    @NotNull
    public String playerName() {
        return this.playerName;
    }

    @NotNull
    public synchronized SessionState state() {
        return this.state;
    }

    public synchronized long lastTransitionAt() {
        return this.lastTransitionAt;
    }

    /**
     * 转移到目标状态.
     *
     * @throws IllegalStateException 当转移非法时
     */
    public synchronized void transition(@NotNull SessionState to) {
        if (!this.state.canTransitionTo(to)) {
            throw new IllegalStateException("illegal session transition " + this.state + " -> " + to + " for " + this.playerName);
        }
        this.state = to;
        this.lastTransitionAt = System.currentTimeMillis();
    }

    /**
     * 仅当前状态为 expected 时转移, 供与并发转移竞争的路径使用: 输掉竞争的一方拿 false 静默退出.
     */
    public synchronized boolean tryTransition(@NotNull SessionState expected, @NotNull SessionState to) {
        if (this.state != expected || !this.state.canTransitionTo(to)) return false;
        this.state = to;
        this.lastTransitionAt = System.currentTimeMillis();
        return true;
    }

    /**
     * 暂存配置阶段的预解码结果, 等待应用段消费.
     * @param prepared 预解码结果
     */
    public synchronized void prepared(@NotNull SnapshotApplier.PreparedSnapshot.Ready prepared) {
        this.prepared = prepared;
    }

    /**
     * 消费掉预解码结果.
     */
    @Nullable
    public synchronized SnapshotApplier.PreparedSnapshot.Ready consumePrepared() {
        SnapshotApplier.PreparedSnapshot.Ready taken = this.prepared;
        this.prepared = null;
        return taken;
    }
}
