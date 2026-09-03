package net.momirealms.sparrow.sync.proxy.minecraft.advancements;

import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = {"net.minecraft.advancements.Criterion", "net.minecraft.advancements.triggers.Criterion"}, activeIf = "min_version=1.21.4")
public interface CriterionProxy {
    CriterionProxy INSTANCE = ASMProxyFactory.create(CriterionProxy.class);
    Class<?> SIMPLE_TRIGGER = SparrowClass.find(
            "net.minecraft.advancements.critereon.SimpleCriterionTrigger",
            "net.minecraft.advancements.criterion.SimpleCriterionTrigger",
            "net.minecraft.advancements.triggers.SimpleCriterionTrigger"
    );

    @MethodInvoker(name = "trigger")
    Object trigger(Object target);

    @MethodInvoker(name = "triggerInstance")
    Object triggerInstance(Object target);
}
