package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.sync.snapshot.SnapshotWriter.WriteAttempt;
import net.momirealms.sparrow.sync.snapshot.model.EagerSnapshotData;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteAttemptTest {

    @Test
    void negativeLimitRetriesForever() {
        WriteAttempt attempt = attemptWith(-1);
        for (int i = 0; i < 1000; i++) {
            assertTrue(attempt.canRetry(), "attempt " + attempt.number() + " should keep retrying");
            attempt = attempt.next();
        }
    }

    @Test
    void limitCountsRetriesAfterTheFirstAttempt() {
        WriteAttempt first = attemptWith(3);
        assertTrue(first.canRetry());
        WriteAttempt second = first.next();
        assertTrue(second.canRetry());
        WriteAttempt third = second.next();
        assertTrue(third.canRetry());
        WriteAttempt fourth = third.next();

        assertEquals(4, fourth.number());
        assertFalse(fourth.canRetry());
    }

    @Test
    void zeroLimitGivesUpAfterTheFirstAttempt() {
        assertFalse(attemptWith(0).canRetry());
    }

    @Test
    void cooldownRampsUpAndCapsAtOneSecond() {
        WriteAttempt attempt = attemptWith(-1);
        for (int i = 1; i <= 5; i++) {
            assertEquals(0, attempt.retryDelayMillis(), "attempt " + i + " should not wait");
            attempt = attempt.next();
        }
        assertEquals(100, attempt.retryDelayMillis());
        assertEquals(200, attempt.next().retryDelayMillis());
        assertEquals(300, attempt.next().next().retryDelayMillis());

        WriteAttempt late = attemptWith(-1);
        for (int i = 1; i < 20; i++) {
            late = late.next();
        }
        assertEquals(1000, late.retryDelayMillis());
    }

    @Test
    void nextKeepsSnapshotAndPolicy() {
        WriteAttempt first = attemptWith(5);
        WriteAttempt second = first.next();

        assertEquals(first.request(), second.request());
        assertEquals(first.maxRetries(), second.maxRetries());
        assertEquals(first.request().captureNanos(), second.request().captureNanos());
        assertEquals(first.number() + 1, second.number());
    }

    @Test
    void retryLoggingUsesTheFirstAndEveryTenthAttempt() {
        WriteAttempt attempt = attemptWith(-1);
        for (int number = 1; number <= 20; number++) {
            assertEquals(number == 1 || number % 10 == 0, attempt.shouldLog(), "attempt " + number);
            attempt = attempt.next();
        }
    }

    private static WriteAttempt attemptWith(int maxRetries) {
        SnapshotMeta meta = SnapshotMeta.builder()
                .player(UUID.randomUUID())
                .timestamp(1_756_300_000_000L)
                .cause(SaveCause.DISCONNECT)
                .server("test")
                .build();
        SaveRequest request = new SaveRequest(meta, "TestPlayer", EagerSnapshotData.EMPTY, null);
        request.updateSnapshot(new Snapshot(meta, Map.of()));
        return WriteAttempt.first(request, maxRetries);
    }
}
