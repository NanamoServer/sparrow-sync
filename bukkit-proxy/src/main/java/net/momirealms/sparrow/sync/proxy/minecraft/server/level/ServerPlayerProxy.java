package net.momirealms.sparrow.sync.proxy.minecraft.server.level;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.function.Consumer;

@ReflectionProxy(name = "net.minecraft.server.level.ServerPlayer", activeIf = "min_version=1.21.4 && has_patch=folia")
public interface ServerPlayerProxy {
    ServerPlayerProxy INSTANCE = ASMProxyFactory.create(ServerPlayerProxy.class);

    @MethodInvoker(name = "respawn")
    void respawn(Object target, Consumer<Object> completed, PlayerRespawnEvent.RespawnReason reason);
}
