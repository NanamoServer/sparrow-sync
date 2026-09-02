package net.momirealms.sparrow.sync.proxy.minecraft.advancements;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

import java.util.Map;

@ReflectionProxy(name = "net.minecraft.advancements.AdvancementProgress", activeIf = "min_version=1.21.4")
public interface AdvancementProgressProxy {
    AdvancementProgressProxy INSTANCE = ASMProxyFactory.create(AdvancementProgressProxy.class);

    @FieldGetter(name = "criteria")
    Map<String, Object> getCriteria(Object target);
}
