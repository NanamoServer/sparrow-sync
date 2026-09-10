package net.momirealms.sparrow.sync.storage;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SnapshotQueryTest {
    private static final UUID PLAYER = UUID.fromString("7f2b3c1d-0a9e-4b8c-9d6f-112233445566");

    @Test
    void nonPositiveLimitMeansUnlimited() {
        assertEquals(SnapshotQuery.NO_LIMIT, SnapshotQuery.of(PLAYER).withLimit(-5).limit());
    }

    @Test
    void invertedBoundsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SnapshotQuery.of(PLAYER).between(20L, 10L));
    }

    @Test
    void negativeOffsetIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SnapshotQuery.of(PLAYER).withOffset(-1));
    }
}
