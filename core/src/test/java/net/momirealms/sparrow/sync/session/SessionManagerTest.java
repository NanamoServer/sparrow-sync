package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.session.SessionManager.CloseResult;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

// 只覆盖不触碰玩家实体的状态编排; 直接提交关闭快照的分支由真机验收
class SessionManagerTest {
    // 保存路径在本测试中不可达, plugin 缺席不影响状态编排
    private final SessionManager manager = new SessionManager(null);

    @Test
    void openRegistersPreparingSession() {
        UUID player = UUID.randomUUID();

        PlayerSession session = this.manager.tryOpen(player, "Steve");

        assertSame(session, this.manager.session(player));
        assertEquals(SessionState.PREPARING, session.state());
        assertEquals(1, this.manager.sessionCount());
    }

    @Test
    void endSessionIsIdempotentAndRemovesFromRegistry() {
        UUID player = UUID.randomUUID();
        PlayerSession session = this.manager.tryOpen(player, "Steve");

        CloseResult first = this.manager.close(session, SaveCause.DISCONNECT);
        CloseResult second = this.manager.close(session, SaveCause.DISCONNECT);

        assertEquals(CloseResult.RELEASED_UNSYNCED, first);
        assertEquals(CloseResult.STALE, second);
        assertEquals(SessionState.CLOSED, session.state());
        assertNull(this.manager.session(player));
    }

    @Test
    void endSessionWithoutActiveSessionSavesNothing() {
        // 会话从未就绪时没有保存这一步, 非 ACTIVE 分支不触碰玩家实例
        UUID player = UUID.randomUUID();
        PlayerSession preparing = this.manager.tryOpen(player, "Steve");

        CloseResult result = this.manager.close(preparing, SaveCause.DISCONNECT);

        assertEquals(CloseResult.RELEASED_UNSYNCED, result);
        assertEquals(SessionState.CLOSED, preparing.state());
        assertNull(this.manager.session(player));
    }

    @Test
    void endSessionLeavesSavingSessionToItsOwnSettle() {
        // 保存已在进行中, 提前终结会抢在落库前放掉锁 (M12)
        PlayerSession session = this.manager.tryOpen(UUID.randomUUID(), "Steve");
        session.transition(SessionState.ACTIVE);
        session.transition(SessionState.SAVING);

        assertEquals(CloseResult.ALREADY_CLOSING, this.manager.close(session, SaveCause.SHUTDOWN));
        assertEquals(SessionState.SAVING, session.state());
    }

    @Test
    void endSessionIsQuietOnAlreadyClosedSession() {
        // 与并发终结竞争时输掉的一方拿到已关闭的会话, 不得抛错也不得复活它
        PlayerSession session = this.manager.tryOpen(UUID.randomUUID(), "Steve");
        this.manager.close(session, SaveCause.DISCONNECT);

        assertEquals(CloseResult.STALE, this.manager.close(session, SaveCause.DISCONNECT));
        assertEquals(SessionState.CLOSED, session.state());
    }

    @Test
    void closeDefersSnapshotWhileTriggeredSnapshotIsBeingSubmitted() {
        PlayerSession session = this.manager.tryOpen(UUID.randomUUID(), "Steve");
        session.transition(SessionState.ACTIVE);
        synchronized (session) {
            session.triggeredSnapshotInProgress = true;
        }

        CloseResult result = this.manager.close(session, SaveCause.DISCONNECT);

        assertEquals(CloseResult.SNAPSHOT_DEFERRED, result);
        assertEquals(SaveCause.DISCONNECT, session.pendingCloseCause);
        assertEquals(SessionState.ACTIVE, session.state());
    }

    @Test
    void tryOpenRejectsExistingSession() {
        UUID player = UUID.randomUUID();
        PlayerSession first = this.manager.tryOpen(player, "Steve");

        PlayerSession second = this.manager.tryOpen(player, "Steve");

        assertNull(second);
        assertSame(first, this.manager.session(player));
        assertEquals(1, this.manager.sessionCount());
        this.manager.close(first, SaveCause.DISCONNECT);
        assertNull(this.manager.session(player));
    }

    @Test
    void releaseCallbackRunsAfterRegistryRemoval() {
        UUID player = UUID.randomUUID();
        PlayerSession session = this.manager.tryOpen(player, "Steve");
        AtomicReference<PlayerSession> observed = new AtomicReference<>(session);
        session.released().thenRun(() -> observed.set(this.manager.session(player)));
        this.manager.close(session, SaveCause.DISCONNECT);

        assertNull(observed.get());
    }
}
