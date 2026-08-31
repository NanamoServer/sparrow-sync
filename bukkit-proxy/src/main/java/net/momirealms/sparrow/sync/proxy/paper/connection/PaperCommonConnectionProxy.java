package net.momirealms.sparrow.sync.proxy.paper.connection;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "io.papermc.paper.connection.PaperCommonConnection", activeIf = "min_version=1.21.7")
public interface PaperCommonConnectionProxy {
    PaperCommonConnectionProxy INSTANCE = ASMProxyFactory.create(PaperCommonConnectionProxy.class);

    @FieldGetter(name = {"handle", "packetListener"})
    Object getHandle(Object target);
}
