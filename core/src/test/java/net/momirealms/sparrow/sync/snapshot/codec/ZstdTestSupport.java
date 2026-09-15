package net.momirealms.sparrow.sync.snapshot.codec;

import com.github.luben.zstd.Zstd;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.dependency.Dependencies;
import net.momirealms.sparrow.sync.plugin.dependency.DependencyManager;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.ZstdCompressor;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class ZstdTestSupport {
    private static ClassLoader classLoader;

    private ZstdTestSupport() {}

    public static synchronized ClassLoader initialize() throws Exception {
        if (classLoader != null) return classLoader;

        // 只填充依赖缓存, 通过实际 DependencyManager 创建隔离加载器
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        DependencyManager manager = (DependencyManager) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, DependencyManager.class);
        Field loaded = DependencyManager.class.getDeclaredField("loaded");
        loaded.setAccessible(true);
        loaded.set(manager, new HashMap<>(Map.of(Dependencies.ZSTD_JNI, Path.of(Zstd.class.getProtectionDomain().getCodeSource().getLocation().toURI()))));
        Field loaders = DependencyManager.class.getDeclaredField("loaders");
        loaders.setAccessible(true);
        loaders.set(manager, new HashMap<>());

        SparrowSync plugin = (SparrowSync) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, SparrowSync.class);
        Field managerField = SparrowSync.class.getDeclaredField("dependencyManager");
        managerField.setAccessible(true);
        managerField.set(plugin, manager);
        Field instance = SparrowSync.class.getDeclaredField("instance");
        instance.setAccessible(true);
        Object previous = instance.get(null);
        try {
            instance.set(null, plugin);
            Class.forName(ZstdCompressor.class.getName() + "$ZstdAccess", true, ZstdCompressor.class.getClassLoader());
            classLoader = manager.obtainClassLoaderWith(Set.of(Dependencies.ZSTD_JNI));
        } finally {
            instance.set(null, previous);
        }
        return classLoader;
    }
}
