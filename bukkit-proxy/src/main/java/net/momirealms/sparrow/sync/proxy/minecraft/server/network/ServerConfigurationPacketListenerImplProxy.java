package net.momirealms.sparrow.sync.proxy.minecraft.server.network;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.server.network.ServerConfigurationPacketListenerImpl")
public interface ServerConfigurationPacketListenerImplProxy {
    ServerConfigurationPacketListenerImplProxy INSTANCE = ASMProxyFactory.create(ServerConfigurationPacketListenerImplProxy.class);

    @FieldGetter(name = "gameProfile")
    Object getGameProfile(Object target);
}
