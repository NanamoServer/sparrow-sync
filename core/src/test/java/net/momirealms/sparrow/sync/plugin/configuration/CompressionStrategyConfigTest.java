package net.momirealms.sparrow.sync.plugin.configuration;

import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.mapper.YamlMapper;
import net.momirealms.sparrow.yaml.mapper.YamlMapperFactory;
import net.momirealms.sparrow.yaml.serializer.auto.annotation.Comment;
import net.momirealms.sparrow.yaml.serializer.auto.annotation.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 锁定压缩策略在配置文件里的取值契约. 用与 {@link PluginConfig.SynchronizationOptions} 同款的注解类,
 * 经真实的 sparrow-yaml 往返, 因此策略改名或枚举嵌套形态变化都会在这里现形.
 */
class CompressionStrategyConfigTest {

    @TempDir
    Path directory;

    @Test
    void everyStrategyNameLoadsFromConfig() throws IOException {
        // 准备: 四个策略各写一次配置文件
        for (CompressorRegistry expected : CompressorRegistry.values()) {
            // 执行
            Section section = this.load("compression: " + expected.name());
            // 断言
            assertEquals(expected, section.compression, expected + " did not survive a config round trip");
        }
    }

    @Test
    void strategyNameIsCaseInsensitive() throws IOException {
        assertEquals(CompressorRegistry.DEFLATE, this.load("compression: deflate").compression);
    }

    @Test
    void missingKeyKeepsTheDefaultStrategy() throws IOException {
        assertEquals(CompressorRegistry.ZSTD, this.load("").compression);
    }

    @Test
    void unknownStrategyFallsBackToTheDefault() throws IOException {
        // sparrow-yaml 1.0.21 起: 未知枚举值不再让整份配置加载失败 —— 有默认值的字段静默保留默认,
        // 且不拖累同一份文件里的其余字段 (无默认值的枚举字段仍整份失败, 无处可退时不硬塞 null)
        Section section = this.load("compression: ZTSD\nmax-snapshots: 64");
        assertEquals(CompressorRegistry.ZSTD, section.compression);
        assertEquals(64, section.maxSnapshots);
    }

    private Section load(String yaml) throws IOException {
        Path file = this.directory.resolve("section-" + yaml.hashCode() + ".yml");
        Files.writeString(file, yaml.isEmpty() ? "{}" : yaml, StandardCharsets.UTF_8);
        YamlMapper<Section> mapper = YamlMapperFactory.builder()
                .sparrowYaml(SparrowYaml.builder().setAllowDuplicateKeys(false).setAllowObjectKeys(false).build())
                .build()
                .create(Section.class, Section::new);
        return mapper.load(file).value();
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class Section {
        @Comment("Mirrors the compression field of SynchronizationOptions")
        CompressorRegistry compression = CompressorRegistry.ZSTD;

        @Comment("A bystander field, proves a misspelled enum does not poison the rest of the file")
        int maxSnapshots = 32;
    }
}
