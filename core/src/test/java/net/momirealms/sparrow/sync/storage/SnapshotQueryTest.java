package net.momirealms.sparrow.sync.storage;

import net.momirealms.sparrow.sync.storage.SnapshotQuery.PinFilter;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SnapshotQueryTest {
    private static final UUID PLAYER = UUID.fromString("7f2b3c1d-0a9e-4b8c-9d6f-112233445566");

    @Test
    void defaultQueryFiltersNothing() {
        SnapshotQuery query = SnapshotQuery.of(PLAYER);

        assertEquals(PLAYER, query.player());
        assertEquals(SnapshotQuery.UNBOUNDED_FROM, query.from());
        assertEquals(SnapshotQuery.UNBOUNDED_TO, query.to());
        assertEquals(PinFilter.ANY, query.pinned());
        assertEquals(SnapshotQuery.NO_LIMIT, query.limit());
        assertEquals(0, query.offset());
    }

    @Test
    void conditionsCompose() {
        SnapshotQuery query = SnapshotQuery.of(PLAYER).withOffset(6).between(10L, 20L).withPinned(PinFilter.PINNED).withLimit(3);

        assertEquals(10L, query.from());
        assertEquals(20L, query.to());
        assertEquals(PinFilter.PINNED, query.pinned());
        assertEquals(3, query.limit());
        assertEquals(6, query.offset());
    }

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
