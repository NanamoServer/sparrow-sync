package net.momirealms.sparrow.sync.proxy.minecraft.server;

import com.mojang.datafixers.DataFixer;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.server.MinecraftServer", activeIf = "min_version=1.21.4")
public interface MinecraftServerProxy {
    MinecraftServerProxy INSTANCE = ASMProxyFactory.create(MinecraftServerProxy.class);

    @MethodInvoker(name = "getServer", isStatic = true)
    Object getServer();

    @MethodInvoker(name = "getPlayerList")
    Object getPlayerList(Object target);

    @FieldGetter(name = "storageSource")
    Object getStorageSource(Object target);

    @MethodInvoker(name = "getFixerUpper")
    DataFixer getFixerUpper(Object target);

    @MethodInvoker(name = "hasStopped")
    boolean hasStopped(Object target);

    @MethodInvoker(name = "isRunning")
    boolean isRunning(Object target);

    @MethodInvoker(name = "getDataStorage", activeIf = "min_version=26.1")
    default Object getDataStorage(Object target) {
        throw new UnsupportedOperationException();
    }
}
