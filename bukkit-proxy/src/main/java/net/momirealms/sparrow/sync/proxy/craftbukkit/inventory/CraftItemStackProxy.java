package net.momirealms.sparrow.sync.proxy.craftbukkit.inventory;

import net.minecraft.world.item.ItemStack;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "org.bukkit.craftbukkit.inventory.CraftItemStack")
public interface CraftItemStackProxy {
    CraftItemStackProxy INSTANCE = ASMProxyFactory.create(CraftItemStackProxy.class);

    @FieldGetter(name = "handle")
    ItemStack getHandle(Object target);

    @MethodInvoker(name = {"asBukkitMirror", "asCraftMirror"}, isStatic = true)
    org.bukkit.inventory.ItemStack asCraftMirror(ItemStack item);
}
