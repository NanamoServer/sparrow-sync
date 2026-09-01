package net.momirealms.sparrow.sync.plugin.configuration;

import net.momirealms.sparrow.sync.plugin.configuration.ServerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerConfigTest {

    @Test
    void defaultsServerIdToEmpty() {
        assertEquals("", new ServerConfig.ConfigDefinition().serverId);
    }
}
