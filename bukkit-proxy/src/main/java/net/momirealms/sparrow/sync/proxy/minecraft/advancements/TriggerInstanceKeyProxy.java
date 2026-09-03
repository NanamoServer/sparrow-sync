package net.momirealms.sparrow.sync.proxy.minecraft.advancements;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.server.PlayerAdvancements$TriggerInstanceKey", activeIf = "min_version=26.2")
public interface TriggerInstanceKeyProxy {
    TriggerInstanceKeyProxy INSTANCE = ASMProxyFactory.create(TriggerInstanceKeyProxy.class);

    @ConstructorInvoker
    Object newInstance(@Type(name = "net.minecraft.advancements.AdvancementHolder") Object advancement, String criterion);
}
