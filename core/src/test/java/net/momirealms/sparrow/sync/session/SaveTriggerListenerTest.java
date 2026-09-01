package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.session.SaveTriggerListener.IntervalDeadline;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveTriggerListenerTest {

    @Test
    void intervalPhaseIsStableAndFallsInTheNextPeriod() {
        UUID player = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        long now = 1_756_300_123_456L;
        long period = 300_000L;

        IntervalDeadline first = IntervalDeadline.first(player, period, now);
        IntervalDeadline repeated = IntervalDeadline.first(player, period, now);

        assertEquals(first, repeated);
        assertTrue(first.dueAt() > now);
        assertTrue(first.dueAt() <= now + period);
    }

    @Test
    void uuidHashSpreadsPlayersAcrossThePeriod() {
        long now = 1_756_300_123_456L;
        long period = 300_000L;

        IntervalDeadline first = IntervalDeadline.first(UUID.fromString("00000000-0000-0000-0000-000000000001"), period, now);
        IntervalDeadline second = IntervalDeadline.first(UUID.fromString("00000000-0000-0000-0000-000000000002"), period, now);

        assertNotEquals(first.dueAt(), second.dueAt());
    }

    @Test
    void missedDeadlineAdvancesWithoutSubmittingCatchUpBursts() {
        IntervalDeadline deadline = new IntervalDeadline(60_000L, 100_000L);

        IntervalDeadline next = deadline.after(285_000L);

        assertEquals(60_000L, next.periodMillis());
        assertEquals(100_000L + 4L * 60_000L, next.dueAt());
        assertTrue(next.dueAt() > 285_000L);
    }
}
