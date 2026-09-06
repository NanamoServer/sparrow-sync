package net.momirealms.sparrow.sync.proxy.minecraft.world.item.component;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.item.component.UseRemainder")
public interface UseRemainderProxy {
    UseRemainderProxy INSTANCE = ASMProxyFactory.create(UseRemainderProxy.class);

    @FieldGetter(name = "convertInto")
    Object getConvertInto(Object target);
}
