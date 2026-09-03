package net.momirealms.sparrow.sync.proxy.minecraft.advancements;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.advancements.CriterionTrigger$Listener", activeIf = "min_version=1.21.4 && max_version=26.1.2")
public interface CriterionListenerProxy {
    CriterionListenerProxy INSTANCE = ASMProxyFactory.create(CriterionListenerProxy.class);

    @ConstructorInvoker
    Object newInstance(
            @Type(name = "net.minecraft.advancements.CriterionTriggerInstance") Object triggerInstance,
            @Type(name = "net.minecraft.advancements.AdvancementHolder") Object advancement,
            String criterion
    );
}
