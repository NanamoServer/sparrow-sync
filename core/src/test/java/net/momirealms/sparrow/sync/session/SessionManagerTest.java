package net.momirealms.sparrow.sync.session;

import net.minecraft.network.Connection;
import net.momirealms.sparrow.sync.snapshot.operation.SessionPrepareResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotSaveResult;
import net.momirealms.sparrow.sync.test.ConnectionFixture;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 覆盖不触碰 Bukkit 与外部服务的会话注册和最终保存边界.
class SessionManagerTest {
    private final SessionManager manager = new SessionManager(null);
    private final Connection connection = ConnectionFixture.create();

    @Test
    void openRegistersPreparingSession() {
        UUID player = UUID.randomUUID();

        PlayerSession session = this.manager.tryOpen(player, "Steve", this.connection);

        assertSame(session, this.manager.find(player));
        assertSame(this.connection, session.connection());
        assertEquals(SessionState.PREPARING, session.state());
        assertTrue(this.manager.onlinePlayers().isEmpty());
        assertEquals(1, this.manager.size());
    }

    @Test
    void abortIsIdempotentAndRemovesSession() {
        UUID player = UUID.randomUUID();
        PlayerSession session = this.manager.tryOpen(player, "Steve", this.connection);

        assertTrue(this.manager.abort(session));
        assertFalse(this.manager.abort(session));
        assertEquals(SessionState.CLOSED, session.state());
        assertNull(this.manager.find(player));
    }

    @Test
    void abortNeverClosesAnActiveSessionWithoutSaving() {
        PlayerSession session = this.manager.tryOpen(UUID.randomUUID(), "Steve", this.connection);
        session.transition(SessionState.ACTIVE);

        assertFalse(this.manager.abort(session));
        assertEquals(SessionState.ACTIVE, session.state());
    }

    @Test
    void abortLeavesSavingSessionToItsCompletion() {
        PlayerSession session = this.manager.tryOpen(UUID.randomUUID(), "Steve", this.connection);
        session.transition(SessionState.ACTIVE);
        session.transition(SessionState.SAVING);

        assertFalse(this.manager.abort(session));
        assertEquals(SessionState.SAVING, session.state());
    }

    @Test
    void prepareRejectsAReleasedSessionBeforeStartingTheSnapshotPipeline() {
        PlayerSession session = this.manager.tryOpen(UUID.randomUUID(), "Steve", this.connection);
        this.manager.abort(session);

        SessionPrepareResult result = this.manager.prepare(session).join();

        assertSame(SessionPrepareResult.REJECTED, result);
    }

    @Test
    void tryOpenRejectsExistingSession() {
        UUID player = UUID.randomUUID();
        PlayerSession first = this.manager.tryOpen(player, "Steve", this.connection);

        PlayerSession second = this.manager.tryOpen(player, "Steve", this.connection);

        assertNull(second);
        assertSame(first, this.manager.find(player));
        assertEquals(1, this.manager.size());
        assertTrue(this.manager.abort(first));
        assertNull(this.manager.find(player));
    }

    @Test
    void releaseCallbackRunsAfterRegistryRemoval() {
        UUID player = UUID.randomUUID();
        PlayerSession session = this.manager.tryOpen(player, "Steve", this.connection);
        AtomicReference<PlayerSession> observed = new AtomicReference<>(session);
        session.released().thenRun(() -> observed.set(this.manager.find(player)));

        this.manager.abort(session);

        assertNull(observed.get());
    }

    @Test
    void reopeningBindsTheNewConnectionWithoutChangingTheReleasedSession() {
        UUID uuid = UUID.randomUUID();
        PlayerSession first = this.manager.tryOpen(uuid, "Steve", this.connection);
        assertTrue(this.manager.abort(first));
        Connection nextConnection = ConnectionFixture.create();

        PlayerSession second = this.manager.tryOpen(uuid, "Steve", nextConnection);

        assertSame(this.connection, first.connection());
        assertSame(nextConnection, second.connection());
        assertSame(second, this.manager.find(uuid));
    }

    @Test
    void finalSaveKeepsSessionUntilTheRemoteWriteCompletes() {
        UUID player = UUID.randomUUID();
        PlayerSession session = this.manager.tryOpen(player, "Steve", this.connection);
        session.transition(SessionState.ACTIVE);
        CompletableFuture<SnapshotSaveResult> remoteSave = new CompletableFuture<>();
        AtomicReference<PlayerSession> observed = new AtomicReference<>(session);
        session.released().thenRun(() -> observed.set(this.manager.find(player)));

        assertEquals(SessionManager.CloseAction.SAVE_ACCEPTED, this.manager.closeWithFinalSave(session, () -> remoteSave));
        assertEquals(SessionState.SAVING, session.state());
        assertSame(session, this.manager.find(player));

        remoteSave.complete(SnapshotSaveResult.CANCELLED);

        assertEquals(SessionState.CLOSED, session.state());
        assertNull(this.manager.find(player));
        assertTrue(session.released().isDone());
        assertNull(observed.get());
    }

    @Test
    void finalSaveIsNotStartedForAHalfLoadedSession() {
        UUID player = UUID.randomUUID();
        PlayerSession session = this.manager.tryOpen(player, "Steve", this.connection);

        assertEquals(SessionManager.CloseAction.ABORTED, this.manager.closeWithFinalSave(session, () -> {
            throw new AssertionError("unsynchronized session must not be saved remotely");
        }));

        assertEquals(SessionState.CLOSED, session.state());
        assertNull(this.manager.find(player));
    }

    @Test
    void shutdownRejectsNewSessions() {
        this.manager.shutdown();

        assertNull(this.manager.tryOpen(UUID.randomUUID(), "LatePlayer", this.connection));
    }
}
