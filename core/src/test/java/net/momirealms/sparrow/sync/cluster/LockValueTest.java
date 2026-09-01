package net.momirealms.sparrow.sync.cluster;

import net.momirealms.sparrow.sync.cluster.LockValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LockValueTest {

    @Test
    void formatAndParseRoundTrip() {
        LockValue value = new LockValue("lobby", "3f2a77c0-9d1e-4b58-8c11-000000000001");

        LockValue parsed = LockValue.parse(value.format());

        assertEquals(value, parsed);
    }

    @Test
    void parseKeepsColonsInsideServerId() {
        LockValue parsed = LockValue.parse("survival:eu:01:ab12cd34");

        assertEquals("survival:eu:01", parsed.serverId());
        assertEquals("ab12cd34", parsed.token());
    }

    @Test
    void parseRejectsMalformedInput() {
        assertNull(LockValue.parse("no-separator"));
        assertNull(LockValue.parse(":token-only"));
        assertNull(LockValue.parse("server-only:"));
    }
}
