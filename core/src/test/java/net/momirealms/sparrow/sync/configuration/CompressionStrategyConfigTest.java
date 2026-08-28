package net.momirealms.sparrow.sync.configuration;

import net.momirealms.sparrow.sync.codec.compressor.CompressorRegistry;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertEquals(CompressorRegistry.SIZE, this.load("compression: size").compression);
    }

    @Test
    void missingKeyKeepsTheDefaultStrategy() throws IOException {
        assertEquals(CompressorRegistry.SPEED, this.load("").compression);
    }

    @Test
    void unknownStrategyFailsTheWholeLoad() {
        // 当前 sparrow-yaml 行为: 未知枚举值让整份配置加载失败而不是回退到默认值.
        // 上层因此拿不到 config, 一个拼错的取值会连带其余配置一起丢失; 修复在 sparrow-yaml 侧
        assertThrows(Exception.class, () -> this.load("compression: ZTSD"));
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
        CompressorRegistry compression = CompressorRegistry.SPEED;
    }
}
