package net.momirealms.sparrow.sync.proxy.minecraft.server.network;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldSetter;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;
import net.momirealms.sparrow.sync.proxy.minecraft.network.protocol.PacketProxy;

@ReflectionProxy(name = "net.minecraft.server.network.ServerCommonPacketListenerImpl", activeIf = "min_version=1.20.6")
public interface ServerCommonPacketListenerImplProxy {
    ServerCommonPacketListenerImplProxy INSTANCE = ASMProxyFactory.create(ServerCommonPacketListenerImplProxy.class);

    @MethodInvoker(name = "send")
    void send(Object target, @Type(clazz = PacketProxy.class) Object packet);

    @FieldSetter(name = "closed", activeIf = "min_version=1.20.6")
    void setClosed(Object target, boolean closed);

    @FieldSetter(name = "closedListenerTime", activeIf = "min_version=1.20.6")
    void setClosedListenerTime(Object target, long closedListenerTime);
}
