package net.momirealms.sparrow.sync.test;

import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.reflect.Field;

public final class PluginConfigExtension implements BeforeEachCallback, AfterEachCallback {
    private Field configField;
    private Object previousConfig;

    @Override
    public void beforeEach(@NotNull ExtensionContext context) throws ReflectiveOperationException {
        this.configField = PluginConfig.class.getDeclaredField("config");
        this.configField.setAccessible(true);
        this.previousConfig = this.configField.get(null);
        this.configField.set(null, new PluginConfig.ConfigDefinition());
    }

    @Override
    public void afterEach(@NotNull ExtensionContext context) throws IllegalAccessException {
        this.configField.set(null, this.previousConfig);
    }
}
