package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

// 只覆盖不触碰玩家实体的状态编排; ACTIVE 分支与关服收尾依赖 Bukkit, 由真机验收
class SessionManagerTest {
    // 保存路径在本测试中不可达, plugin 缺席不影响状态编排
    private final SessionManager manager = new SessionManager(null, new SyncLogger(new QuietLogger()));

    @Test
    void openRegistersPreparingSession() {
        UUID player = UUID.randomUUID();

        PlayerSession session = this.manager.open(player, "Steve");

        assertSame(session, this.manager.session(player));
        assertEquals(SessionState.PREPARING, session.state());
        assertEquals(1, this.manager.sessionCount());
    }

    @Test
    void endSessionIsIdempotentAndRemovesFromRegistry() {
        UUID player = UUID.randomUUID();
        PlayerSession session = this.manager.open(player, "Steve");

        this.manager.close(session, SaveCause.DISCONNECT);
        this.manager.close(session, SaveCause.DISCONNECT);

        assertEquals(SessionState.CLOSED, session.state());
        assertNull(this.manager.session(player));
    }

    @Test
    void endSessionWithoutActiveSessionSavesNothing() {
        // 会话从未就绪时没有保存这一步, 非 ACTIVE 分支不触碰玩家实例
        UUID player = UUID.randomUUID();
        PlayerSession preparing = this.manager.open(player, "Steve");

        boolean saved = this.manager.close(preparing, SaveCause.DISCONNECT);

        assertFalse(saved);
        assertEquals(SessionState.CLOSED, preparing.state());
        assertNull(this.manager.session(player));
    }

    @Test
    void endSessionLeavesSavingSessionToItsOwnSettle() {
        // 保存已在进行中, 提前终结会抢在落库前放掉锁 (M12)
        PlayerSession session = this.manager.open(UUID.randomUUID(), "Steve");
        session.transition(SessionState.ACTIVE);
        session.transition(SessionState.SAVING);

        assertFalse(this.manager.close(session, SaveCause.SHUTDOWN));
        assertEquals(SessionState.SAVING, session.state());
    }

    @Test
    void endSessionIsQuietOnAlreadyClosedSession() {
        // 与并发终结竞争时输掉的一方拿到已关闭的会话, 不得抛错也不得复活它
        PlayerSession session = this.manager.open(UUID.randomUUID(), "Steve");
        this.manager.close(session, SaveCause.DISCONNECT);

        assertFalse(this.manager.close(session, SaveCause.DISCONNECT));
        assertEquals(SessionState.CLOSED, session.state());
    }

    @Test
    void reopenReplacesUnfinishedSession() {
        // 退服保存尚未 settle 时玩家重连: 新会话顶掉旧的, 旧会话由自己的 settle 收尾
        UUID player = UUID.randomUUID();
        PlayerSession first = this.manager.open(player, "Steve");

        PlayerSession second = this.manager.open(player, "Steve");

        assertSame(second, this.manager.session(player));
        // 被顶掉的会话自行收尾时不得误删新会话
        this.manager.close(first, SaveCause.DISCONNECT);
        assertSame(second, this.manager.session(player));
    }

    private static final class QuietLogger implements PluginLogger {

        @Override
        public void info(String s) {
        }

        @Override
        public void warn(String s) {
        }

        @Override
        public void warn(String s, Throwable t) {
        }

        @Override
        public void error(String s) {
        }

        @Override
        public void error(String s, Throwable t) {
        }
    }
}
