package net.momirealms.sparrow.sync.plugin.configuration;

import net.momirealms.sparrow.sync.plugin.Plugin;
import net.momirealms.sparrow.sync.plugin.dependency.DependencyVersions;
import net.momirealms.sparrow.yaml.SparrowYaml;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {
    @TempDir
    Path directory;

    @Test
    void generatesServerIdentityWithoutAClusterSetting() throws Exception {
        this.config().reload();
        String yaml = Files.readString(this.directory.resolve("server.yml"));
        assertTrue(yaml.contains("server-id:"));
        assertFalse(yaml.contains("cluster-id"));
    }

    @Test
    void loadsServerIdentityWithoutAClusterSetting() throws Exception {
        Files.writeString(this.directory.resolve("server.yml"), """
                config-version: "%s"
                server-id: "survival-A"
                """.formatted(DependencyVersions.CONFIG_VERSION));
        this.config().reload();
        assertEquals("survival-A", ServerConfig.serverId());
    }

    private ServerConfig config() {
        Plugin plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
            case "dataFolderPath" -> this.directory;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        return new ServerConfig(plugin, SparrowYaml.builder().setAllowDuplicateKeys(false).setAllowObjectKeys(false).build());
    }
}
