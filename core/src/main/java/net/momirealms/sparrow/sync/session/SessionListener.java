package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.locale.MessageConstants;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.session.SnapshotService.LoadOutcome;
import net.momirealms.sparrow.sync.session.SnapshotService.PreparedOutcome;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class SessionListener implements Listener {
    private final SparrowSync plugin;
    private final SnapshotService snapshotService;
    private final SessionManager sessionManager;

    public SessionListener(@NotNull SparrowSync plugin, @NotNull SnapshotService snapshotService, @NotNull SessionManager sessionManager) {
        this.plugin = plugin;
        this.snapshotService = snapshotService;
        this.sessionManager = sessionManager;
    }

    // 应用配置阶段加载完成的数据
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerSession session = this.sessionManager.session(player.getUniqueId());
        // todo 测试代码, 等 Gate 完成后删除.
        // Gate 未覆盖本次进服 (Gate 落地前) 时回落到 join 后异步准备.
        // 会话存在就必然是 Gate 刚开的 PREPARING —— 上一次会话持锁到落库完成才放,
        // 而 Gate 拿不到锁就不放行, 因此这里不会撞上未结束的旧会话
        if (session == null) {
            this.joinFallback(player);
            return;
        }
        this.applyStored(session, player);
    }

    // 消费会话暂存并应用, 必须在玩家线程上调用.
    private void applyStored(PlayerSession session, Player player) {
        // 抢不到转移说明会话已被断线清理关掉, 本次应用作废
        if (!session.tryTransition(SessionState.PREPARING, SessionState.APPLYING)) return;
        var prepared = session.consumePrepared();
        // 无历史快照代表是新玩家, 只放行不暂存
        if (prepared == null) {
            session.tryTransition(SessionState.APPLYING, SessionState.ACTIVE);
            return;
        }
        // 给玩家应用快照数据
        LoadOutcome outcome;
        try {
            outcome = this.snapshotService.applyPrepared(player, prepared);
        } catch (Throwable throwable) {
            this.plugin.logger().error(TranslationManager.console(LogConstants.GATE_KICKED, player.getName(), String.valueOf(throwable)), throwable);
            this.kickOnOwningThread(session, player);
            return;
        }
        // 应用成功
        if (outcome instanceof LoadOutcome.Applied) {
            session.tryTransition(SessionState.APPLYING, SessionState.ACTIVE);
            return;
        }
        // 应用失败
        String detail = outcome instanceof LoadOutcome.Failed(String det) ? det : String.valueOf(outcome);
        this.plugin.logger().error(TranslationManager.console(LogConstants.GATE_KICKED, player.getName(), detail));
        this.kickOnOwningThread(session, player);
    }

    // 关闭会话并踢出玩家. kick 会同步触发 quit 事件重入 onQuit, 所以必须先关闭会话.
    private void kickOnOwningThread(PlayerSession session, Player player) {
        this.sessionManager.close(session, SaveCause.DISCONNECT);
        player.kick(MessageConstants.KICK_SYNC_NOT_READY.build());
    }

    // 关闭会话并踢出玩家.
    private void kickFromAsync(PlayerSession session, Player player, String reason) {
        this.plugin.logger().error(TranslationManager.console(LogConstants.GATE_KICKED, player.getName(), reason));
        player.getScheduler().run(
                this.plugin.javaPlugin(),
                task -> this.kickOnOwningThread(session, player),
                () -> this.sessionManager.close(session, SaveCause.DISCONNECT)
        );
    }

    // 玩家退出服务器时关闭会话并保存数据.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PlayerSession session = this.sessionManager.session(player.getUniqueId());
        if (session == null) return; // 说明会话已经被关闭, 无需处理.
        if (!this.sessionManager.close(session, SaveCause.DISCONNECT)) {
            // 会话从未就绪, 没有保存这一步; 半加载状态存出去会覆盖好数据 todo 信息意义不大, 存储到单独日志
            this.plugin.logger().warn(TranslationManager.console(LogConstants.SYNC_SAVE_SKIPPED_UNSYNCED, player.getName()));
        }
    }





    // todo 测试代码, 等 Gate 完成后删除.
    // Gate 未覆盖时的过渡路径: join 后异步读库预解码, 应用段再回玩家线程.
    // 登录读与上一条命的保存同走该玩家的串行队列, read-after-write 由队列次序保证
    private void joinFallback(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerSession session = this.sessionManager.open(uuid, player.getName());
        this.plugin.storageProvider().ensureUser(uuid, player.getName()).whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                this.plugin.logger().warn(TranslationManager.console(LogConstants.SYNC_USER_FAILED, player.getName()), throwable);
            }
        });
        this.snapshotService.loadAndPrepare(uuid, player.getName()).whenComplete((outcome, throwable) -> {
            if (throwable != null) {
                this.kickFromAsync(session, player, String.valueOf(throwable));
                return;
            }
            switch (outcome) {
                case PreparedOutcome.Empty ignored -> session.tryTransition(SessionState.PREPARING, SessionState.ACTIVE);
                case PreparedOutcome.Failed failed -> this.kickFromAsync(session, player, failed.detail());
                // 应用段回玩家线程运行, 调度前玩家已离开则会话作废, 无数据可回写
                case PreparedOutcome.Ready ready -> {
                    session.prepared(ready.prepared());
                    player.getScheduler().run(this.plugin.javaPlugin(),
                            task -> this.applyStored(session, player),
                            () -> this.sessionManager.close(session, SaveCause.DISCONNECT));
                }
            }
        });
    }


}
