package net.momirealms.sparrow.sync.proxy.minecraft.world.food;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldSetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.food.FoodData", activeIf = "min_version=1.21.4")
public interface FoodDataProxy {
    FoodDataProxy INSTANCE = ASMProxyFactory.create(FoodDataProxy.class);

    @FieldGetter(name = "tickTimer")
    int getTickTimer(Object target);

    @FieldSetter(name = "tickTimer")
    void setTickTimer(Object target, int value);
}
