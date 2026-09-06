package net.momirealms.sparrow.sync.proxy.minecraft.world.item.component;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

import java.util.List;

@ReflectionProxy(name = "net.minecraft.world.item.component.BundleContents")
public interface BundleContentsProxy {
    BundleContentsProxy INSTANCE = ASMProxyFactory.create(BundleContentsProxy.class);

    @FieldGetter(name = "items")
    List<?> getItems(Object target);
}
