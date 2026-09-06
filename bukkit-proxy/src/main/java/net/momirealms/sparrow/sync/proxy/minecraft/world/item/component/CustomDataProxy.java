package net.momirealms.sparrow.sync.proxy.minecraft.world.item.component;

import net.minecraft.nbt.CompoundTag;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.item.component.CustomData")
public interface CustomDataProxy {
    CustomDataProxy INSTANCE = ASMProxyFactory.create(CustomDataProxy.class);

    @FieldGetter(name = "tag")
    CompoundTag getTag(Object target);
}
