package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.session.SnapshotService.SaveAttempt;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveAttemptTest {

    @Test
    void negativeLimitRetriesForever() {
        SaveAttempt attempt = attemptWith(-1);
        for (int i = 0; i < 1000; i++) {
            assertTrue(attempt.retryAllowed(), "attempt " + attempt.number() + " should keep retrying");
            attempt = attempt.next();
        }
    }

    @Test
    void limitCountsRetriesAfterTheFirstAttempt() {
        // max-save-retries = 3 表示首发之后还能再排三次队尾, 一共四次尝试
        SaveAttempt first = attemptWith(3);
        assertTrue(first.retryAllowed());
        SaveAttempt second = first.next();
        assertTrue(second.retryAllowed());
        SaveAttempt third = second.next();
        assertTrue(third.retryAllowed());
        SaveAttempt fourth = third.next();

        assertEquals(4, fourth.number());
        assertFalse(fourth.retryAllowed());
    }

    @Test
    void zeroLimitGivesUpAfterTheFirstAttempt() {
        assertFalse(attemptWith(0).retryAllowed());
    }

    @Test
    void nextKeepsSnapshotAndPolicy() {
        SaveAttempt first = attemptWith(5);
        SaveAttempt second = first.next();

        assertEquals(first.snapshot(), second.snapshot());
        assertEquals(first.maxRetries(), second.maxRetries());
        assertEquals(first.captureStart(), second.captureStart());
        assertEquals(first.number() + 1, second.number());
    }

    private static SaveAttempt attemptWith(int maxRetries) {
        SnapshotMeta meta = SnapshotMeta.builder()
                .player(UUID.randomUUID())
                .timestamp(1_756_300_000_000L)
                .cause(SaveCause.DISCONNECT)
                .server("test")
                .build();
        return new SaveAttempt(new Snapshot(meta, Map.of()), "TestPlayer", 1, maxRetries, 0L);
    }
}
