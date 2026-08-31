package net.momirealms.sparrow.sync.configuration;

import net.momirealms.sparrow.sync.plugin.Plugin;
import net.momirealms.sparrow.sync.plugin.dependency.DependencyVersions;
import net.momirealms.sparrow.sync.configuration.PluginConfig.PDCMergeBlacklist;
import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.mapper.YamlMapper;
import net.momirealms.sparrow.yaml.mapper.YamlMapperFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PDCMergeConfigTest {
    @TempDir
    Path directory;

    @Test
    void defaultBlacklistAndExamplesAreWrittenToYaml() throws IOException {
        Path file = this.directory.resolve("pdc-merge-default.yml");
        YamlMapper<PluginConfig.SynchronizationOptions> mapper = mapper();
        mapper.load(file);

        PluginConfig.SynchronizationOptions options = mapper.load(file).value();
        String yaml = Files.readString(file, StandardCharsets.UTF_8);

        assertEquals(List.of("sparrow-sync-ignore", List.of("sparrow-sync", "ignore")), options.pdcMergeNamespaces);
        assertTrue(yaml.contains("Use \"sparrow-sync-ignore\" for custom_data -> sparrow-sync-ignore"), yaml);
        assertTrue(yaml.contains("Use [\"sparrow-sync\", \"ignore\"] for custom_data -> sparrow-sync -> ignore"), yaml);
    }

    @Test
    void blacklistLoadsScalarAndSegmentedPathsFromYaml() throws IOException {
        Path file = this.directory.resolve("pdc-merge.yml");
        Files.writeString(file, """
                pdc-merge-namespaces:
                  - craftengine
                  - [craftengine, id]
                """, StandardCharsets.UTF_8);
        PluginConfig.SynchronizationOptions options = mapper().load(file).value();

        assertEquals(List.of("craftengine", List.of("craftengine", "id")), options.pdcMergeNamespaces);
    }

    @Test
    void reloadPublishesNormalizedBlacklistSnapshot() throws IOException {
        Path file = this.directory.resolve("config.yml");
        Files.writeString(file, """
                config-version: "%s"
                synchronization:
                  pdc-merge-namespaces:
                    - [sparrow, ignore]
                    - sparrow
                    - sparrow
                    - [sparrow, ignore, key]
                    - [other, key]
                    - [other, key]
                """.formatted(DependencyVersions.CONFIG_VERSION), StandardCharsets.UTF_8);
        PluginConfig pluginConfig = new PluginConfig(this.plugin(), newYaml());

        pluginConfig.reload();

        PDCMergeBlacklist first = PluginConfig.synchronization$pdcMergeNamespaces();
        PDCMergeBlacklist sparrow = first.child("sparrow");
        assertNotNull(sparrow);
        assertTrue(sparrow.terminal());
        assertNull(sparrow.child("ignore"));
        PDCMergeBlacklist other = first.child("other");
        assertNotNull(other);
        assertTrue(other.child("key").terminal());

        Files.writeString(file, """
                config-version: "%s"
                synchronization:
                  pdc-merge-namespaces:
                    - [next, key]
                """.formatted(DependencyVersions.CONFIG_VERSION), StandardCharsets.UTF_8);

        pluginConfig.reload();

        PDCMergeBlacklist second = PluginConfig.synchronization$pdcMergeNamespaces();
        PDCMergeBlacklist next = second.child("next");
        assertNotNull(next);
        assertTrue(next.child("key").terminal());
        assertTrue(first.child("sparrow").terminal());
    }

    @Test
    void scalarAndSegmentedPathsRemainDistinct() {
        PDCMergeBlacklist blacklist = PDCMergeBlacklist.of(List.of(
                "sparrow-sync:ignore",
                List.of("sparrow-sync", "ignore")
        ));

        assertTrue(blacklist.child("sparrow-sync:ignore").terminal());
        assertTrue(blacklist.child("sparrow-sync").child("ignore").terminal());
    }

    private static YamlMapper<PluginConfig.SynchronizationOptions> mapper() {
        return YamlMapperFactory.builder()
                .sparrowYaml(newYaml())
                .build()
                .create(PluginConfig.SynchronizationOptions.class, PluginConfig.SynchronizationOptions::new);
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
