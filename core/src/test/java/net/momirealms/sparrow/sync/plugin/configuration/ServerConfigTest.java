package net.momirealms.sparrow.sync.plugin.configuration;

import net.momirealms.sparrow.sync.plugin.Plugin;
import net.momirealms.sparrow.sync.plugin.dependency.DependencyVersions;
import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.route.Route;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(strings = {"28", DependencyVersions.SERVER_CONFIG_VERSION})
    void loadsServerIdentityWithoutAClusterSetting(String version) throws Exception {
        Files.writeString(this.directory.resolve("server.yml"), """
                config-version: "%s"
                server-id: "survival-A"
                """.formatted(version));
        this.config().reload();
        assertEquals("survival-A", ServerConfig.serverId());
        assertEquals(DependencyVersions.SERVER_CONFIG_VERSION, SparrowYaml.builder().build()
                .load(Files.readString(this.directory.resolve("server.yml"))).getString(Route.from("config-version")));
    }

    private ServerConfig config() {
        Plugin plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
            case "dataFolderPath" -> this.directory;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        return new ServerConfig(plugin, SparrowYaml.builder().setAllowDuplicateKeys(false).setAllowObjectKeys(false).build());
    }
}
