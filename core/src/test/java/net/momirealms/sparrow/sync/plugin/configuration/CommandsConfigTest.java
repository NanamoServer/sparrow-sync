package net.momirealms.sparrow.sync.plugin.configuration;

import net.momirealms.sparrow.sync.plugin.dependency.DependencyVersions;
import net.momirealms.sparrow.yaml.SparrowYaml;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandsConfigTest {
    @TempDir
    Path directory;

    @Test
    void createsCanonicalCommands() throws Exception {
        CommandsConfig config = new CommandsConfig(this.directory, SparrowYaml.builder().build());
        for (String feature : List.of("status", "reload")) {
            assertEquals("sparrow_sync.command." + feature, config.configDefinition().command(feature).getPermission());
            assertTrue(config.configDefinition().command(feature).isEnable());
            assertEquals(List.of("/sparrow-sync " + feature), config.configDefinition().command(feature).getUsages());
        }
        assertTrue(Files.readString(this.directory.resolve("commands.yml")).contains("status:"));
        for (String feature : List.of("gui", "snapshot_view", "snapshot_list", "exception_list", "exception_view")) {
            assertTrue(config.configDefinition().command(feature).isEnable());
            assertEquals("sparrow_sync.command.view", config.configDefinition().command(feature).getPermission());
        }
        assertEquals(List.of("/sparrow-sync snapshot list"), config.configDefinition().command("snapshot_list").getUsages());
        assertEquals(List.of("/sparrow-sync exception list"), config.configDefinition().command("exception_list").getUsages());
        assertEquals(List.of("/sparrow-sync exception view"), config.configDefinition().command("exception_view").getUsages());
        assertEquals(List.of("/sparrow-sync gui"), config.configDefinition().command("gui").getUsages());
        assertEquals(List.of("/sparrow-sync snapshot view"), config.configDefinition().command("snapshot_view").getUsages());
        assertThrows(IllegalArgumentException.class, () -> config.configDefinition().command("snapshot"));
        assertThrows(IllegalArgumentException.class, () -> config.configDefinition().command("about"));
        assertThrows(IllegalArgumentException.class, () -> config.configDefinition().command("help"));
    }


    @Test
    void preservesCustomPermissionAndUsages() throws Exception {
        Files.writeString(this.directory.resolve("commands.yml"), """
                config-version: '20'
                reload:
                  enable: false
                  permission: network.admin
                  usages:
                    - /network reload-sync
                """);
        CommandsConfig config = new CommandsConfig(this.directory, SparrowYaml.builder().build());
        assertEquals("network.admin", config.configDefinition().command("reload").getPermission());
        assertEquals(List.of("/network reload-sync"), config.configDefinition().command("reload").getUsages());
        assertFalse(config.configDefinition().command("reload").isEnable());
    }


    @Test
    void publishedCommandsRemainFixedWhileFileChanges() throws Exception {
        CommandsConfig config = new CommandsConfig(this.directory, SparrowYaml.builder().build());
        Object published = config.configDefinition();
        Files.writeString(this.directory.resolve("commands.yml"), """
                config-version: '%s'
                status:
                  enable: false
                  permission: custom.status
                  usages:
                    - /different-status
                """.formatted(DependencyVersions.CONFIG_VERSION));
        assertSame(published, config.configDefinition());
        assertEquals("sparrow_sync.command.status", config.configDefinition().command("status").getPermission());
        assertEquals("custom.status", new CommandsConfig(this.directory, SparrowYaml.builder().build()).configDefinition().command("status").getPermission());
    }

}
