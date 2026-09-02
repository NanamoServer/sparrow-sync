package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.session.SnapshotWriter.WriteAttempt;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
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
        // max-save-retries = 3 表示首发之后还能再排三次队尾, 一共四次尝试
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
        // 抖动和主从切换通常几十毫秒就过去, 前 5 次不等; 第 6 次起每次多等 100 毫秒, 封顶 1 秒
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

        assertEquals(first.snapshot(), second.snapshot());
        assertEquals(first.maxRetries(), second.maxRetries());
        assertEquals(first.acceptedAtNanos(), second.acceptedAtNanos());
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
        return WriteAttempt.first(new Snapshot(meta, Map.of()), "TestPlayer", maxRetries, 0L);
    }
}
