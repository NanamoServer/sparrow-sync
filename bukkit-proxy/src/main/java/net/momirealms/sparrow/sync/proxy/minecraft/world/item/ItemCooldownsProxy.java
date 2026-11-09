package net.momirealms.sparrow.sync.proxy.minecraft.world.item;

import net.minecraft.world.item.ItemCooldowns;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;
import net.momirealms.sparrow.sync.proxy.minecraft.resources.IdentifierProxy;

import java.util.Map;

@ReflectionProxy(name = "net.minecraft.world.item.ItemCooldowns", activeIf = "min_version=1.21.4")
public interface ItemCooldownsProxy {
    ItemCooldownsProxy INSTANCE = ASMProxyFactory.create(ItemCooldownsProxy.class);

    @FieldGetter(name = "cooldowns")
    Map<Object, ItemCooldowns.CooldownInstance> getCooldowns(Object target);

    @MethodInvoker(name = "onCooldownStarted")
    void onCooldownStarted(Object target, @Type(clazz = IdentifierProxy.class) Object group, int duration);

    @MethodInvoker(name = "onCooldownEnded")
    void onCooldownEnded(Object target, @Type(clazz = IdentifierProxy.class) Object group);
}
