package net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.world.level.storage.PlayerDataStorage", activeIf = "min_version=1.21.4")
public interface PlayerDataStorageProxy {
    PlayerDataStorageProxy INSTANCE = ASMProxyFactory.create(PlayerDataStorageProxy.class);

    @MethodInvoker(name = "load", activeIf = "max_version=1.21.5")
    Object load$0(Object target, String name, String uuid);

    @MethodInvoker(name = "load", activeIf = "min_version=1.21.6 && max_version=1.21.8")
    Object load$1(Object target, String name, String uuid, @Type(name = "net.minecraft.util.ProblemReporter") Object reporter);

    @MethodInvoker(name = "load", activeIf = "min_version=1.21.9")
    Object load$2(Object target, @Type(name = "net.minecraft.server.players.NameAndId") Object nameAndId);
}
