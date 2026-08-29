package net.momirealms.sparrow.sync.configuration;

import net.momirealms.sparrow.sync.plugin.Plugin;
import net.momirealms.sparrow.yaml.SparrowYaml;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigurationManager {
    private final Plugin plugin;
    private final SparrowYaml sparrowYaml;
    private final PluginConfig pluginConfig;
    private final ServerConfig serverConfig;

    public ConfigurationManager(Plugin plugin) {
        this.plugin = plugin;
        this.sparrowYaml = SparrowYaml.builder()
                .setAllowDuplicateKeys(false)
                .setAllowObjectKeys(false)
                .build();
        this.pluginConfig = new PluginConfig(plugin, this.sparrowYaml);
        this.serverConfig = new ServerConfig(plugin, this.sparrowYaml);
    }

    public void reload() {
        this.pluginConfig.reload();
        this.serverConfig.reload();
    }

    @NotNull
    public SparrowYaml sparrowYaml() {
        return this.sparrowYaml;
    }

    /**
     * 根据传入的路径, 从插件资源文件夹中获取对应的文件.
     *
     * @param filePath 相对路径.
     * @return 目标文件真实 Path.
     */
    @NotNull
    public Path resolveConfig(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            throw new IllegalArgumentException("ResourcePath cannot be null or empty");
        }
        filePath = filePath.replace('\\', '/');
        Path configFile = this.plugin.dataFolderPath().resolve(filePath);
        // if the config doesn't exist, create it based on the template in the resources dir
        if (!Files.exists(configFile)) {
            try {
                Files.createDirectories(configFile.getParent());
            } catch (IOException ignored) {
            }
            try (InputStream is = this.plugin.resourceStream(filePath)) {
                if (is == null) {
                    throw new IllegalArgumentException("The embedded resource '" + filePath + "' cannot be found");
                }
                Files.copy(is, configFile);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return configFile;
    }
}
