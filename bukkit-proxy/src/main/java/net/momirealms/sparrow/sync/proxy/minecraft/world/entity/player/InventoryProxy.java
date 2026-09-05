package net.momirealms.sparrow.sync.proxy.minecraft.world.entity.player;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldSetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.entity.player.Inventory", activeIf = "min_version=1.21.4")
public interface InventoryProxy {
    InventoryProxy INSTANCE = ASMProxyFactory.create(InventoryProxy.class);

    @FieldGetter(name = "selected")
    int getSelected(Object target);

    @FieldSetter(name = "selected")
    void setSelected(Object target, int value);
}
