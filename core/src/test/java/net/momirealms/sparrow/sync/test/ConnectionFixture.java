package net.momirealms.sparrow.sync.test;

import io.papermc.paper.configuration.GlobalConfiguration;
import net.minecraft.network.Connection;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;

public final class ConnectionFixture {
    private ConnectionFixture() {}

    @NotNull
    public static Connection create() {
        try {
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
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Connection) unsafeClass.getMethod("allocateInstance", Class.class).invoke(field.get(null), Connection.class);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
