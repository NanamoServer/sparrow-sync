package net.momirealms.sparrow.sync.plugin.configuration;

import net.momirealms.sparrow.sync.plugin.Plugin;
import net.momirealms.sparrow.sync.plugin.dependency.DependencyVersions;
import net.momirealms.sparrow.sync.storage.StorageType;
import net.momirealms.sparrow.yaml.SparrowYaml;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PostgresConfigTest {
    @TempDir Path directory;

    @Test
    void recognizesPostgresqlAndKeepsItsDriverOptions() throws Exception {
        Files.writeString(this.directory.resolve("config.yml"), """
                config-version: "%s"
                database:
                  type: POSTGRESQL
                  postgresql:
                    url: "jdbc:postgresql://localhost:5432/example?currentSchema=game&socketTimeout=25"
                    username: "game"
                    password: "example"
                    table-prefix: "game_"
                """.formatted(DependencyVersions.CONFIG_VERSION));
        this.config().reload();
        assertEquals(StorageType.POSTGRESQL, PluginConfig.database$type());
        PluginConfig.PostgresOptions options = PluginConfig.database$postgresql();
        assertTrue(options.url().endsWith("currentSchema=game&socketTimeout=25"));
        assertEquals("game", options.username());
        assertEquals("example", options.password());
        assertEquals("game_", options.tablePrefix());
    }

    @Test
    void upgradingConfigAddsPostgresWithoutChangingExistingMysqlValues() throws Exception {
        Files.writeString(this.directory.resolve("config.yml"), """
                config-version: "19"
                database:
                  type: MYSQL
                  mysql:
                    url: "jdbc:mysql://db:3306/existing"
                    username: "existing"
                    password: "kept"
                    table-prefix: "old_"
                """);
        this.config().reload();
        assertEquals(StorageType.MYSQL, PluginConfig.database$type());
        assertEquals("kept", PluginConfig.database$mysql().password());
        assertEquals("old_", PluginConfig.database$mysql().tablePrefix());
        assertTrue(PluginConfig.database$postgresql().url().startsWith("jdbc:postgresql://"));
        String written = Files.readString(this.directory.resolve("config.yml"));
        assertTrue(written.contains("postgresql:"));
        assertTrue(written.contains("jdbc:mysql://db:3306/existing"));
        try (var files = Files.list(this.directory)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("config.yml.bak.")));
        }
    }

    private PluginConfig config() {
        Plugin plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
            case "dataFolderPath" -> this.directory;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        return new PluginConfig(plugin, SparrowYaml.builder().setAllowDuplicateKeys(false).setAllowObjectKeys(false).build());
    }
}
