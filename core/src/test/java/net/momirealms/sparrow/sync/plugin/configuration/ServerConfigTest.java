package net.momirealms.sparrow.sync.plugin.configuration;

import net.momirealms.sparrow.sync.plugin.Plugin;
import net.momirealms.sparrow.sync.plugin.dependency.DependencyVersions;
import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.YamlDocument;
import net.momirealms.sparrow.yaml.route.Route;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerConfigTest {
    @TempDir
    Path directory;

    @Test
    void createsDefaultServerOptions() throws Exception {
        SparrowYaml yaml = newYaml();

        new ServerConfig(this.plugin(), yaml).reload();

        assertEquals("", ServerConfig.serverId());
        assertEquals("main", ServerConfig.clusterId());
        YamlDocument document = yaml.load(this.directory.resolve("server.yml"));
        assertEquals(DependencyVersions.CONFIG_VERSION, document.getString(Route.from("config-version")));
    }

    @Test
    void upgradesVersionTenWithoutChangingServerIdentity() throws Exception {
        Path file = this.directory.resolve("server.yml");
        Files.writeString(file, """
                config-version: "10"
                server-id: "test"
                cluster-id: "network"
                """, StandardCharsets.UTF_8);
        SparrowYaml yaml = newYaml();

        new ServerConfig(this.plugin(), yaml).reload();

        assertEquals("test", ServerConfig.serverId());
        assertEquals("network", ServerConfig.clusterId());
        YamlDocument upgraded = yaml.load(file);
        assertEquals(DependencyVersions.CONFIG_VERSION, upgraded.getString(Route.from("config-version")));
    }

    private static SparrowYaml newYaml() {
        return SparrowYaml.builder().setAllowDuplicateKeys(false).setAllowObjectKeys(false).build();
    }

    private Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
            case "dataFolderPath" -> this.directory;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }
}
