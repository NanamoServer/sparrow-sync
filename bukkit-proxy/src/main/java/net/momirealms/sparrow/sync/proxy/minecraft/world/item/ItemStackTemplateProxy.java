package net.momirealms.sparrow.sync.proxy.minecraft.world.item;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.Item;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.item.ItemStackTemplate", activeIf = "min_version=26.1")
public interface ItemStackTemplateProxy {
    ItemStackTemplateProxy INSTANCE = ASMProxyFactory.create(ItemStackTemplateProxy.class);

    @MethodInvoker(name = "item")
    Holder<Item> item(Object target);

    @MethodInvoker(name = "get")
    <T> T get(Object target, DataComponentType<? extends T> type);
}
