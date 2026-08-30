package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.session.SnapshotService.PreparedOutcome;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PlayerSession {
    private final UUID uuid;
    private final String playerName;
    // 一次性的 “会话释放完成” 信号, 可以用 thenRun 注册 Channel 关闭时的回调.
    private final CompletableFuture<Void> released = new CompletableFuture<>();

    private SessionState state = SessionState.PREPARING;
    private PreparedOutcome.Ready prepared;

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
    public CompletableFuture<Void> released() {
        return this.released;
    }

    @NotNull
    public synchronized SessionState state() {
        return this.state;
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
    }

    /**
     * 仅当前状态为 expected 时转移, 供与并发转移竞争的路径使用: 输掉竞争的一方拿 false 静默退出.
     */
    public synchronized boolean tryTransition(@NotNull SessionState expected, @NotNull SessionState to) {
        if (this.state != expected || !this.state.canTransitionTo(to)) return false;
        this.state = to;
        return true;
    }

    /**
     * 暂存配置阶段的预解码结果 (含数据准备段耗时), 等待应用段消费.
     * @param prepared 预解码结果
     */
    public synchronized void prepared(@NotNull PreparedOutcome.Ready prepared) {
        this.prepared = prepared;
    }

    /**
     * 消费掉预解码结果.
     */
    @Nullable
    public synchronized PreparedOutcome.Ready consumePrepared() {
        PreparedOutcome.Ready taken = this.prepared;
        this.prepared = null;
        return taken;
    }
}
