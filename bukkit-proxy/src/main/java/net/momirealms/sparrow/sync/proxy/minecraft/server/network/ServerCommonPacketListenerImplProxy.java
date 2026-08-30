package net.momirealms.sparrow.sync.proxy.minecraft.server.network;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldSetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.server.network.ServerCommonPacketListenerImpl", activeIf = "min_version=1.20.6")
public interface ServerCommonPacketListenerImplProxy {
    ServerCommonPacketListenerImplProxy INSTANCE = ASMProxyFactory.create(ServerCommonPacketListenerImplProxy.class);

    @FieldSetter(name = "closed", activeIf = "min_version=1.20.6")
    void setClosed(Object target, boolean closed);

    @FieldSetter(name = "closedListenerTime", activeIf = "min_version=1.20.6")
    void setClosedListenerTime(Object target, long closedListenerTime);
}
