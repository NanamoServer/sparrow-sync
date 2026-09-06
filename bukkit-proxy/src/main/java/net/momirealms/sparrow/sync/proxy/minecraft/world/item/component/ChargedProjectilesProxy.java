package net.momirealms.sparrow.sync.proxy.minecraft.world.item.component;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

import java.util.List;

@ReflectionProxy(name = "net.minecraft.world.item.component.ChargedProjectiles")
public interface ChargedProjectilesProxy {
    ChargedProjectilesProxy INSTANCE = ASMProxyFactory.create(ChargedProjectilesProxy.class);

    @FieldGetter(name = "items")
    List<?> getItems(Object target);
}
