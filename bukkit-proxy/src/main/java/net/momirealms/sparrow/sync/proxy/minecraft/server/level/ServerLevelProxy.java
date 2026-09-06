package net.momirealms.sparrow.sync.proxy.minecraft.server.level;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.server.level.ServerLevel")
public interface ServerLevelProxy {
    ServerLevelProxy INSTANCE = ASMProxyFactory.create(ServerLevelProxy.class);

    @MethodInvoker(name = "getDataStorage")
    Object getDataStorage(Object target);
}
