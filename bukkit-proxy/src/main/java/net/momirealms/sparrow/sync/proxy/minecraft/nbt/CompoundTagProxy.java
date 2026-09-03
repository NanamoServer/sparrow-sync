package net.momirealms.sparrow.sync.proxy.minecraft.nbt;

import net.minecraft.nbt.Tag;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

import java.util.Map;

@ReflectionProxy(name = "net.minecraft.nbt.CompoundTag", activeIf = "min_version=1.21.4")
public interface CompoundTagProxy {
    CompoundTagProxy INSTANCE = ASMProxyFactory.create(CompoundTagProxy.class);

    @FieldGetter(name = "tags")
    Map<String, Tag> getTags(Object target);
}
