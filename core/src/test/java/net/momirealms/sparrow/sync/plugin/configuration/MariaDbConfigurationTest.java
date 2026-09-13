package net.momirealms.sparrow.sync.plugin.configuration;

import net.momirealms.sparrow.sync.plugin.Plugin;
import net.momirealms.sparrow.sync.storage.StorageType;
import net.momirealms.sparrow.sync.test.PluginConfigExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(PluginConfigExtension.class)
class MariaDbConfigurationTest {
    @Test
    void upgradesMysqlConfigAndLoadsIndependentMariaDbOptions(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("config.yml");
        Files.writeString(path, """
                ___version___: '5'
                database:
                  type: MYSQL
                  mysql:
                    url: jdbc:mysql://localhost:3306/previous
                    username: previous_user
                    password: previous_password
                    table-prefix: previous_
                """);
        Plugin plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, (proxy, method, args) -> {
            if (method.getName().equals("dataFolderPath")) return directory;
            throw new AssertionError(method.getName());
        });
        PluginConfig config = new PluginConfig(plugin, new ConfigurationManager(plugin).sparrowYaml());
        config.reload();
        assertEquals(StorageType.MYSQL, PluginConfig.database$type());
        assertEquals("jdbc:mysql://localhost:3306/previous", PluginConfig.database$mysql().url());
        assertEquals("previous_user", PluginConfig.database$mysql().username());
        assertEquals("previous_password", PluginConfig.database$mysql().password());
        assertEquals("previous_", PluginConfig.database$mysql().tablePrefix());
        assertEquals("jdbc:mariadb://localhost:3306/minecraft?connectTimeout=5000&socketTimeout=10000&characterEncoding=UTF-8", PluginConfig.database$mariadb().url());
        assertTrue(Files.readString(path).contains("mariadb:"));

        Files.writeString(path, """
                ___version___: '6'
                database:
                  type: mariadb
                  mariadb:
                    username: maria_user
                    password: maria_password
                    table-prefix: maria_
                """);
        config.reload();
        assertEquals(StorageType.MARIADB, PluginConfig.database$type());
        assertTrue(PluginConfig.database$mariadb().url().startsWith("jdbc:mariadb://"));
        assertEquals("maria_user", PluginConfig.database$mariadb().username());
        assertEquals("maria_password", PluginConfig.database$mariadb().password());
        assertEquals("maria_", PluginConfig.database$mariadb().tablePrefix());
        assertEquals("root", PluginConfig.database$mysql().username());
    }
}
