package net.momirealms.sparrow.sync.proxy.minecraft.advancements;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.advancements.AdvancementHolder", activeIf = "min_version=1.21.4")
public interface AdvancementHolderProxy {
    AdvancementHolderProxy INSTANCE = ASMProxyFactory.create(AdvancementHolderProxy.class);

    @MethodInvoker(name = "id")
    Object id(Object target);
}
