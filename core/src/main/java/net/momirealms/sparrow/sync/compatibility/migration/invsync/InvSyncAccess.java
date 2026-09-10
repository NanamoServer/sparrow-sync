package net.momirealms.sparrow.sync.compatibility.migration.invsync;

import net.momirealms.sparrow.sync.util.ReflectionUtils;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class InvSyncAccess {
    private final Plugin plugin;

    InvSyncAccess(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    // 从 InvSync 的类加载器加载其内部或重定位后的类。
    @NotNull
    Class<?> type(String name) {
        try {
            return Class.forName("com.xbaimiao.invsync." + name, true, this.plugin.getClass().getClassLoader());
        } catch (ClassNotFoundException exception) {
            throw new LinkageError("InvSync class is unavailable: " + name, exception);
        }
    }

    // 取得 Kotlin object 编译生成的 {@code INSTANCE} 单例。
    Object singleton(String name) {
        try {
            return this.type(name).getField("INSTANCE").get(null);
        } catch (ReflectiveOperationException exception) {
            throw new LinkageError("InvSync singleton is unavailable: " + name, exception);
        }
    }

    // 使用 InvSync 自己配置的 Gson 将 JSON 还原为它的内部模型。
    Object decodeJson(String json, String model) throws Exception {
        Object gson = call(this.plugin, "getGson");
        return invoke(method(gson.getClass(), "fromJson", String.class, Class.class), gson, json, this.type(model));
    }

    // 将 UTF-8 字节交给 InvSync Gson 读取。
    Object decodeJson(byte[] bytes, String model) throws Exception {
        return this.decodeJson(new String(bytes, StandardCharsets.UTF_8), model);
    }

    // 解开 InvSync 压缩的统计数据，并读取为其 StatisticsData 模型。
    Object decodeStatistics(byte[] bytes) throws Exception {
        Object compression = this.singleton("bukkit.util.CompressUtil");
        String json = (String) call(compression, "ungzipString", byte[].class, bytes);
        return this.decodeJson(json, "bukkit.serializer.statistics.StatisticsData");
    }

    // 调用成就序列化器中只读取字节数组的重载。
    List<?> decodeAdvancements(Object serializer, byte[] bytes) throws Exception {
        MethodHandle decoder;
        try {
            // 精确解析此签名, 源类的其他重载还引用了可选插件 HuskSync.
            decoder = ReflectionUtils.LOOKUP.findVirtual(serializer.getClass(), "deserializer", MethodType.methodType(this.type("bukkit.serializer.advancement.GsonAdvancementData"), byte[].class));
        } catch (ReflectiveOperationException exception) {
            throw new LinkageError("InvSync advancement decoder is unavailable", exception);
        }
        try {
            return (List<?>) decoder.invoke(serializer, bytes);
        } catch (Throwable cause) {
            if (cause instanceof Exception failure) throw failure;
            if (cause instanceof Error failure) throw failure;
            throw new IllegalStateException(cause);
        }
    }

    // 将 InvSync PDC 字段解析为 SNBT 文本, 供 Sparrow NBT 解析器接续转换。
    String decodePersistentData(byte[] bytes) throws Exception {
        // InvSync 的 Shadow 配置把 NBT-API 放在此包下, 由源 NBTContainer 解析 SNBT.
        Class<?> container = this.type("shadow.nbt.changeme.nbtapi.NBTContainer");
        try {
            return container.getConstructor(String.class).newInstance(new String(bytes, StandardCharsets.UTF_8)).toString();
        } catch (InvocationTargetException exception) {
            throw failure(exception);
        } catch (ReflectiveOperationException exception) {
            throw new LinkageError("InvSync NBTContainer is unavailable", exception);
        }
    }

    // 读取来源对象声明的私有字段。
    static Object field(Object receiver, String name) throws Exception {
        try {
            return ReflectionUtils.setAccessible(receiver.getClass().getDeclaredField(name)).get(receiver);
        } catch (ReflectiveOperationException exception) {
            throw new LinkageError("InvSync field is unavailable: " + name, exception);
        }
    }

    // 调用来源方法。
    static Object call(Object receiver, String name) throws Exception {
        return invoke(method(receiver.getClass(), name), receiver);
    }

    static Object call(Object receiver, String name, Class<?> parameter, Object argument) throws Exception {
        return invoke(method(receiver.getClass(), name, parameter), receiver, argument);
    }

    // 按方法名和精确参数类型查找公开来源方法。
    @NotNull
    static Method method(Class<?> owner, String name, Class<?>... parameters) {
        try {
            return ReflectionUtils.setAccessible(owner.getMethod(name, parameters));
        } catch (ReflectiveOperationException exception) {
            throw new LinkageError("InvSync method is unavailable: " + owner.getName() + "." + name, exception);
        }
    }

    // 调用已定位的来源方法，并保留原始失败原因。
    static Object invoke(Method method, Object receiver, Object... arguments) throws Exception {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException exception) {
            throw failure(exception);
        } catch (IllegalAccessException exception) {
            throw new LinkageError("InvSync method is inaccessible: " + method, exception);
        }
    }

    // 从 InvocationTargetException 还原来源调用实际抛出的异常。
    private static Exception failure(InvocationTargetException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof Exception failure) return failure;
        if (cause instanceof Error failure) throw failure;
        return new IllegalStateException(cause);
    }
}
