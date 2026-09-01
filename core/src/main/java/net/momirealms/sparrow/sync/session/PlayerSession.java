package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.session.SnapshotService.PreparedOutcome;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PlayerSession {
    private final UUID uuid;
    private final String playerName;
    // 当会话从注册表移除后, 这个 Future 会在 Channel Close 的回调上完成, 一般用于在 thenRun 注册一些 Session 释放时的回调任务.
    private final CompletableFuture<Void> released = new CompletableFuture<>();

    private SessionState state = SessionState.PREPARING;
    private PreparedOutcome.Ready prepared;
    // 分布式锁的持有值, 释放时原样传回
    private String lockValue;
    boolean triggeredSnapshotInProgress;   // 当前触发器快照仍在采集、派发事件或入队
    @Nullable SaveCause pendingCloseCause; // 当前快照入队后紧接着提交的关闭原因

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
     * 当前状态与预期相同时尝试转移, 并发竞争失败时返回 false.
     *
     * @param expected 预期的当前状态
     * @param to 目标状态
     * @return 状态匹配且转移成功时返回 true
     */
    public synchronized boolean tryTransition(@NotNull SessionState expected, @NotNull SessionState to) {
        if (this.state != expected || !this.state.canTransitionTo(to)) return false;
        this.state = to;
        return true;
    }

    /**
     * 转移到指定的会话状态.
     *
     * @param to 目标状态
     * @throws IllegalStateException 当前状态不能转移到目标状态时
     */
    public synchronized void transition(@NotNull SessionState to) {
        if (!this.state.canTransitionTo(to)) {
            throw new IllegalStateException("illegal session transition " + this.state + " -> " + to + " for " + this.playerName);
        }
        this.state = to;
    }

    /**
     * 暂存登录配置阶段生成的预解码结果, 供玩家进入世界后应用.
     *
     * @param prepared 预解码结果
     */
    public synchronized void prepared(@NotNull PreparedOutcome.Ready prepared) {
        this.prepared = prepared;
    }

    /**
     * 取走预解码结果, 同一份结果只会返回一次.
     *
     * @return 待应用的结果, 没有结果时返回 null
     */
    @Nullable
    public synchronized PreparedOutcome.Ready consumePrepared() {
        PreparedOutcome.Ready taken = this.prepared;
        this.prepared = null;
        return taken;
    }

    public synchronized void lockValue(@NotNull String lockValue) {
        this.lockValue = lockValue;
    }

    @Nullable
    public synchronized String lockValue() {
        return this.lockValue;
    }
}
