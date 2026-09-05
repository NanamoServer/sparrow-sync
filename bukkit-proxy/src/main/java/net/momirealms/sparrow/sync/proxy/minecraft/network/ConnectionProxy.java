package net.momirealms.sparrow.sync.proxy.minecraft.network;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.Connection", activeIf = "min_version=1.21.7 && has_patch=paper")
public interface ConnectionProxy {
    ConnectionProxy INSTANCE = ASMProxyFactory.create(ConnectionProxy.class);

    @FieldGetter(name = {"savedPlayerForLegacyEvents", "savedPlayerForLoginEventLegacy"})
    Object getSavedPlayerForLegacyEvents(Object target);
}
