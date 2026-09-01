package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.snapshot.data.SnapshotApplier;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSessionTest {

    @Test
    void transitionMatrixMatchesLifecycle() {
        // 全枚举 5x5 转移矩阵, 合法集合之外的一律拒绝
        record Case(SessionState from, SessionState to, boolean legal) {
        }
        List<Case> cases = List.of(
                new Case(SessionState.PREPARING, SessionState.APPLYING, true),
                new Case(SessionState.PREPARING, SessionState.ACTIVE, true),
                new Case(SessionState.PREPARING, SessionState.CLOSED, true),
                new Case(SessionState.PREPARING, SessionState.SAVING, false),
                new Case(SessionState.PREPARING, SessionState.PREPARING, false),
                new Case(SessionState.APPLYING, SessionState.ACTIVE, true),
                new Case(SessionState.APPLYING, SessionState.CLOSED, true),
                new Case(SessionState.APPLYING, SessionState.PREPARING, false),
                new Case(SessionState.APPLYING, SessionState.SAVING, false),
                new Case(SessionState.APPLYING, SessionState.APPLYING, false),
                new Case(SessionState.ACTIVE, SessionState.SAVING, true),
                new Case(SessionState.ACTIVE, SessionState.CLOSED, false),
                new Case(SessionState.ACTIVE, SessionState.PREPARING, false),
                new Case(SessionState.ACTIVE, SessionState.APPLYING, false),
                new Case(SessionState.ACTIVE, SessionState.ACTIVE, false),
                new Case(SessionState.SAVING, SessionState.CLOSED, true),
                new Case(SessionState.SAVING, SessionState.PREPARING, false),
                new Case(SessionState.SAVING, SessionState.APPLYING, false),
                new Case(SessionState.SAVING, SessionState.ACTIVE, false),
                new Case(SessionState.SAVING, SessionState.SAVING, false),
                new Case(SessionState.CLOSED, SessionState.PREPARING, false),
                new Case(SessionState.CLOSED, SessionState.APPLYING, false),
                new Case(SessionState.CLOSED, SessionState.ACTIVE, false),
                new Case(SessionState.CLOSED, SessionState.SAVING, false),
                new Case(SessionState.CLOSED, SessionState.CLOSED, false)
        );
        for (int i = 0; i < cases.size(); i++) {
            Case current = cases.get(i);
            assertEquals(current.legal(), current.from().canTransitionTo(current.to()), current.from() + " -> " + current.to());
        }
    }

    @Test
    void illegalTransitionThrowsAndKeepsState() {
        PlayerSession session = new PlayerSession(UUID.randomUUID(), "Steve");

        assertThrows(IllegalStateException.class, () -> session.transition(SessionState.SAVING));
        assertEquals(SessionState.PREPARING, session.state());
    }

    @Test
    void tryTransitionFailsOnStaleExpectation() {
        PlayerSession session = new PlayerSession(UUID.randomUUID(), "Steve");
        session.transition(SessionState.APPLYING);

        assertFalse(session.tryTransition(SessionState.PREPARING, SessionState.APPLYING));
        assertTrue(session.tryTransition(SessionState.APPLYING, SessionState.ACTIVE));
    }

    @Test
    void concurrentTransitionAdmitsExactlyOneWinner() throws InterruptedException {
        // 并发争抢 PREPARING -> CLOSED (断线清理) 与 PREPARING -> APPLYING (应用段), 恰有一方赢
        for (int round = 0; round < 100; round++) {
            PlayerSession session = new PlayerSession(UUID.randomUUID(), "Steve");
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger winners = new AtomicInteger();
            Runnable closer = () -> {
                awaitQuietly(start);
                if (session.tryTransition(SessionState.PREPARING, SessionState.CLOSED)) winners.incrementAndGet();
            };
            Runnable applier = () -> {
                awaitQuietly(start);
                if (session.tryTransition(SessionState.PREPARING, SessionState.APPLYING)) winners.incrementAndGet();
            };
            Thread first = new Thread(closer);
            Thread second = new Thread(applier);
            first.start();
            second.start();
            start.countDown();
            first.join();
            second.join();

            assertEquals(1, winners.get());
        }
    }

    @Test
    void consumePreparedReturnsOnceThenNull() {
        PlayerSession session = new PlayerSession(UUID.randomUUID(), "Steve");
        Snapshot snapshot = new Snapshot(SnapshotMeta.builder()
                .player(session.uuid())
                .timestamp(1L)
                .cause(SaveCause.DISCONNECT)
                .build(), Map.of());
        SnapshotService.PreparedOutcome.Ready prepared = new SnapshotService.PreparedOutcome.Ready(
                snapshot, new SnapshotApplier.PreparedSnapshot.Ready(Map.of(), List.of()), 0L);
        session.prepared(prepared);

        assertSame(prepared, session.consumePrepared());
        assertNull(session.consumePrepared());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
