package net.momirealms.sparrow.sync.test;

import io.papermc.paper.configuration.GlobalConfiguration;
import net.minecraft.network.Connection;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;

public final class ConnectionFixture {
    private ConnectionFixture() {}

    /** 创建仅供身份和字段读取测试使用的 Connection. */
    @NotNull
    public static Connection create() {
        try {
            // Connection 的静态初始化读取 Paper 配置, 独立测试需要补上这一步
            Field configField = GlobalConfiguration.class.getDeclaredField("instance");
            configField.setAccessible(true);
            Object previous = configField.get(null);
            GlobalConfiguration config = new GlobalConfiguration();
            config.misc = config.new Misc();
            configField.set(null, config);
            try {
                Class.forName(Connection.class.getName());
            } finally {
                configField.set(null, previous);
            }
            // 跳过连接构造器中的限流器初始化, 测试不收发网络数据
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Connection) unsafeClass.getMethod("allocateInstance", Class.class).invoke(field.get(null), Connection.class);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
