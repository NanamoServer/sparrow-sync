package net.momirealms.sparrow.sync.proxy.minecraft.world.entity.ai.attributes;

import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

import java.util.Map;

@ReflectionProxy(name = "net.minecraft.world.entity.ai.attributes.AttributeInstance", activeIf = "min_version=1.21.4")
public interface AttributeInstanceProxy {
    AttributeInstanceProxy INSTANCE = ASMProxyFactory.create(AttributeInstanceProxy.class);

    @FieldGetter(name = "modifierById")
    Map<Object, AttributeModifier> getModifierById(Object target);
}
