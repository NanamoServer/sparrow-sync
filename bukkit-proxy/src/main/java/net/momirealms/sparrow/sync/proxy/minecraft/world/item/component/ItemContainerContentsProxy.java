package net.momirealms.sparrow.sync.proxy.minecraft.world.item.component;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

import java.util.List;

@ReflectionProxy(name = "net.minecraft.world.item.component.ItemContainerContents")
public interface ItemContainerContentsProxy {
    ItemContainerContentsProxy INSTANCE = ASMProxyFactory.create(ItemContainerContentsProxy.class);

    @FieldGetter(name = "items")
    List<?> getItems(Object target);
}
