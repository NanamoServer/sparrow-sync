package net.momirealms.sparrow.sync.proxy.minecraft.server.level;

import com.mojang.serialization.Codec;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.server.level.ServerPlayer$RespawnConfig", activeIf = "min_version=1.21.5")
public interface RespawnConfigProxy {
    RespawnConfigProxy INSTANCE = ASMProxyFactory.create(RespawnConfigProxy.class);

    @FieldGetter(name = "CODEC", isStatic = true)
    Codec<Object> getCodec();
}
