package net.momirealms.sparrow.sync.test;

import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.reflect.Field;

/**
 * 为使用真实注册表的测试提供启动配置, 每个用例结束后恢复之前的静态配置.
 * 回调早于测试的 BeforeEach 方法执行, 注册表应在该方法或测试正文中创建.
 */
public final class PluginConfigExtension implements BeforeEachCallback, AfterEachCallback {
    private Field configField;     // 当前版本 PluginConfig 的静态配置字段
    private Object previousConfig; // 进入本用例前的配置, 尚未加载时为 null

    /**
     * 在测试装配业务对象之前安装默认配置, 未知数据丢弃名单初始为空.
     *
     * @param context 当前测试的 JUnit 上下文
     * @throws ReflectiveOperationException 当测试无法访问静态配置时
     */
    @Override
    public void beforeEach(@NotNull ExtensionContext context) throws ReflectiveOperationException {
        this.configField = PluginConfig.class.getDeclaredField("config");
        this.configField.setAccessible(true);
        this.previousConfig = this.configField.get(null);
        this.configField.set(null, new PluginConfig.ConfigDefinition());
    }

    /**
     * 测试自身的清理方法执行完后恢复配置, 避免后续用例依赖执行顺序.
     *
     * @param context 当前测试的 JUnit 上下文
     * @throws IllegalAccessException 当测试无法写回原配置时
     */
    @Override
    public void afterEach(@NotNull ExtensionContext context) throws IllegalAccessException {
        this.configField.set(null, this.previousConfig);
    }
}
