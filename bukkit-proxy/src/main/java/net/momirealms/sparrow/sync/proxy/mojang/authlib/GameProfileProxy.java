package net.momirealms.sparrow.sync.proxy.mojang.authlib;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

import java.util.UUID;

@ReflectionProxy(name = "com.mojang.authlib.GameProfile")
public interface GameProfileProxy {
    GameProfileProxy INSTANCE = ASMProxyFactory.create(GameProfileProxy.class);

    @FieldGetter(name = "id")
    UUID getId(Object target);

    @FieldGetter(name = "name")
    String getName(Object target);
}
