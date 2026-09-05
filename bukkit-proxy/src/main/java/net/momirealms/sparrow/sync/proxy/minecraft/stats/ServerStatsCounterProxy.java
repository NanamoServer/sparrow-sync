package net.momirealms.sparrow.sync.proxy.minecraft.stats;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.stats.ServerStatsCounter", activeIf = "min_version=1.21.4")
public interface ServerStatsCounterProxy {
    ServerStatsCounterProxy INSTANCE = ASMProxyFactory.create(ServerStatsCounterProxy.class);

    @FieldGetter(name = "dirty")
    Object getDirty(Object target);
}
