package net.momirealms.sparrow.sync.session;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.session.operation.SnapshotLoadResult;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.data.SnapshotApplyContext;
import net.momirealms.sparrow.sync.test.ConnectionFixture;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSessionTest {
    private final Connection connection = ConnectionFixture.create();

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
        PlayerSession session = new PlayerSession(UUID.randomUUID(), "Steve", this.connection);

        assertThrows(IllegalStateException.class, () -> session.transition(SessionState.SAVING));
        assertEquals(SessionState.PREPARING, session.state());
    }

    @Test
    void tryTransitionFailsOnStaleExpectation() {
        PlayerSession session = new PlayerSession(UUID.randomUUID(), "Steve", this.connection);
        session.transition(SessionState.APPLYING);

        assertFalse(session.tryTransition(SessionState.PREPARING, SessionState.APPLYING));
        assertTrue(session.tryTransition(SessionState.APPLYING, SessionState.ACTIVE));
    }

    @Test
    void concurrentTransitionAdmitsExactlyOneWinner() throws InterruptedException {
        // 并发争抢 PREPARING -> CLOSED (断线清理) 与 PREPARING -> APPLYING (应用段), 恰有一方赢
        for (int round = 0; round < 100; round++) {
            PlayerSession session = new PlayerSession(UUID.randomUUID(), "Steve", this.connection);
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
    void loginDataPublishesAndConsumesPlayerDataWithSnapshotAtomically() {
        PlayerSession session = new PlayerSession(UUID.randomUUID(), "Steve", this.connection);
        DataKey unknown = DataKey.of("other", "unknown");
        Map<DataKey, Tag> passthrough = Map.of(unknown, NBT.createString("retained"));
        Snapshot snapshot = new Snapshot(SnapshotMeta.builder()
                .player(session.uuid())
                .timestamp(1L)
                .cause(SaveCause.DISCONNECT)
                .build(), passthrough);
        DataRegistry registry = new DataRegistry();
        registry.freeze();
        SnapshotLoadResult.Ready loaded = new SnapshotLoadResult.Ready(snapshot, newApplyContext(registry, passthrough), 0L);
        Optional<CompoundTag> playerData = Optional.of(new CompoundTag());

        session.publishLoginData(new PlayerDataPreload.Ready(playerData), loaded, 12L, 34L);
        assertSame(playerData, session.loadPlayerData(Optional::empty));
        LoginDataState.Ready ready = assertInstanceOf(LoginDataState.Ready.class, session.finishLoginData());

        assertSame(playerData, ready.playerData());
        assertSame(loaded, ready.snapshot());
        assertEquals(1, ready.loads());
        assertEquals(12L, ready.asyncReadNanos());
        assertEquals(34L, ready.nativeApplyNanos());
        assertInstanceOf(LoginDataState.Cleared.class, session.finishLoginData());

        Map<DataKey, Tag> replacement = Map.of(unknown, NBT.createString("replacement"));
        session.retainedData(replacement);
        assertEquals(replacement, session.retainedData());
    }

    @Test
    void readyPlayerDataCanBeLoadedMoreThanOnce() {
        PlayerSession session = new PlayerSession(UUID.randomUUID(), "Steve", this.connection);
        Optional<CompoundTag> playerData = Optional.of(new CompoundTag());

        assertInstanceOf(LoginDataState.Ready.class, session.publishLoginData(new PlayerDataPreload.Ready(playerData), null, 0L, 0L));
        assertSame(playerData, session.loadPlayerData(() -> {
            throw new AssertionError("ready cache must not read original data");
        }));
        assertSame(playerData, session.loadPlayerData(() -> {
            throw new AssertionError("ready cache must not read original data");
        }));

        LoginDataState.Ready state = assertInstanceOf(LoginDataState.Ready.class, session.finishLoginData());
        assertEquals(2, state.loads());
        Optional<CompoundTag> original = Optional.of(new CompoundTag());
        assertSame(original, session.loadPlayerData(() -> original));
    }

    @Test
    void emptyPlayerDataIsStillAReadyCacheHit() {
        PlayerSession session = new PlayerSession(UUID.randomUUID(), "Steve", this.connection);
        Optional<CompoundTag> empty = Optional.empty();

        session.publishLoginData(new PlayerDataPreload.Ready(empty), null, 0L, 0L);

        assertSame(empty, session.loadPlayerData(() -> Optional.of(new CompoundTag())));
        assertEquals(1, assertInstanceOf(LoginDataState.Ready.class, session.finishLoginData()).loads());
    }

    @Test
    void failedLocalLoadPublishesAnEmptyReadyCache() {
        PlayerSession session = new PlayerSession(UUID.randomUUID(), "Steve", this.connection);

        session.publishLoginData(PlayerDataPreload.FALLBACK, null, 0L, 0L);

        assertEquals(Optional.empty(), session.loadPlayerData(() -> Optional.of(new CompoundTag())));
        assertEquals(1, assertInstanceOf(LoginDataState.Ready.class, session.finishLoginData()).loads());
    }

    @Test
    void unservedReadyPlayerDataReportsZeroLoads() {
        PlayerSession session = new PlayerSession(UUID.randomUUID(), "Steve", this.connection);
        session.publishLoginData(new PlayerDataPreload.Ready(Optional.of(new CompoundTag())), null, 0L, 0L);

        assertEquals(0, assertInstanceOf(LoginDataState.Ready.class, session.finishLoginData()).loads());
    }

    @Test
    void earlyPlayerDataLoadFallsBackAndRejectsPublication() {
        PlayerSession session = new PlayerSession(UUID.randomUUID(), "Steve", this.connection);
        Optional<CompoundTag> original = Optional.of(new CompoundTag());

        assertSame(original, session.loadPlayerData(() -> original));

        LoginDataState.Failed failed = assertInstanceOf(LoginDataState.Failed.class, session.publishLoginData(new PlayerDataPreload.Ready(Optional.empty()), null, 0L, 0L));
        assertEquals("PlayerDataStorage.load ran before player data preload completed", failed.detail());
        assertEquals(failed, session.finishLoginData());
    }

    @Test
    void firstPlayerDataFailureWins() {
        PlayerSession session = new PlayerSession(UUID.randomUUID(), "Steve", this.connection);

        assertEquals("first", assertInstanceOf(LoginDataState.Failed.class, session.failLoginData("first")).detail());
        assertEquals("first", assertInstanceOf(LoginDataState.Failed.class, session.failLoginData("second")).detail());
        assertEquals("first", assertInstanceOf(LoginDataState.Failed.class, session.publishLoginData(new PlayerDataPreload.Ready(Optional.empty()), null, 0L, 0L)).detail());
    }

    @Test
    void clearedPlayerDataRejectsLatePublication() {
        PlayerSession session = new PlayerSession(UUID.randomUUID(), "Steve", this.connection);
        session.finishLoginData();

        assertInstanceOf(LoginDataState.Cleared.class, session.publishLoginData(new PlayerDataPreload.Ready(Optional.empty()), null, 0L, 0L));
    }

    private static SnapshotApplyContext newApplyContext(DataRegistry registry, Map<DataKey, Tag> passthrough) {
        try {
            Constructor<SnapshotApplyContext> constructor = SnapshotApplyContext.class.getDeclaredConstructor(DataRegistry.class, Map.class, Object[].class);
            constructor.setAccessible(true);
            return constructor.newInstance(registry, passthrough, new Object[registry.size()]);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

}
