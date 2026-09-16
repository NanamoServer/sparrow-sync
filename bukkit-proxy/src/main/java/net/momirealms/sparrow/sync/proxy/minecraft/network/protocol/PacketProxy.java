package net.momirealms.sparrow.sync.proxy.minecraft.network.protocol;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.Packet", activeIf = "min_version=1.21.4")
public interface PacketProxy {
    PacketProxy INSTANCE = ASMProxyFactory.create(PacketProxy.class);
}
