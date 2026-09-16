package net.momirealms.sparrow.sync.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;
import net.momirealms.sparrow.sync.proxy.minecraft.network.chat.ComponentProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.network.protocol.PacketProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ClientboundSystemChatPacket", activeIf = "min_version=1.21.4")
public interface ClientboundSystemChatPacketProxy extends PacketProxy {
    ClientboundSystemChatPacketProxy INSTANCE = ASMProxyFactory.create(ClientboundSystemChatPacketProxy.class);

    @ConstructorInvoker
    Object newInstance(@Type(clazz = ComponentProxy.class) Object content, boolean overlay);
}
