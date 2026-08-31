package net.momirealms.sparrow.sync.configuration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerConfigTest {

    @Test
    void defaultsServerIdToEmpty() {
        assertEquals("", new ServerConfig.ConfigDefinition().serverId);
    }
}
