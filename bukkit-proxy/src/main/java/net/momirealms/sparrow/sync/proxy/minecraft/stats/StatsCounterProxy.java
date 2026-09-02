package net.momirealms.sparrow.sync.proxy.minecraft.stats;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.stats.StatsCounter", activeIf = "min_version=1.21.4")
public interface StatsCounterProxy {
    StatsCounterProxy INSTANCE = ASMProxyFactory.create(StatsCounterProxy.class);

    @FieldGetter(name = "stats")
    Object getStats(Object target);
}
