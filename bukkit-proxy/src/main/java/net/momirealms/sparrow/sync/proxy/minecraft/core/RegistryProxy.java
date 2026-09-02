package net.momirealms.sparrow.sync.proxy.minecraft.core;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;
import net.momirealms.sparrow.sync.proxy.minecraft.resources.IdentifierProxy;

@ReflectionProxy(name = "net.minecraft.core.Registry", activeIf = "min_version=1.21.4")
public interface RegistryProxy {
    RegistryProxy INSTANCE = ASMProxyFactory.create(RegistryProxy.class);

    @MethodInvoker(name = "getKey")
    Object getKey(Object target, Object value);

    @MethodInvoker(name = "getValue")
    Object getValue(Object target, @Type(clazz = IdentifierProxy.class) Object key);
}
