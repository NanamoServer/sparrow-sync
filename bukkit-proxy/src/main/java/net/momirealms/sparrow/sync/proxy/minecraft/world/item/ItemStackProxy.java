package net.momirealms.sparrow.sync.proxy.minecraft.world.item;

import net.minecraft.world.item.Item;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.item.ItemStack")
public interface ItemStackProxy {
    ItemStackProxy INSTANCE = ASMProxyFactory.create(ItemStackProxy.class);

    @MethodInvoker(name = "getItem")
    Item getItem(Object target);
}
