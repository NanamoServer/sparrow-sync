package net.momirealms.sparrow.sync.proxy.minecraft.world.entity;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldSetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.entity.LivingEntity", activeIf = "min_version=1.21.4")
public interface LivingEntityMotionProxy {
    LivingEntityMotionProxy INSTANCE = ASMProxyFactory.create(LivingEntityMotionProxy.class);

    @FieldSetter(name = "fallFlyTicks")
    void setFallFlyTicks(Object target, int value);
}
