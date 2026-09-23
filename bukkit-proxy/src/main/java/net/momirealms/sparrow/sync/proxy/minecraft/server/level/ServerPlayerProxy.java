package net.momirealms.sparrow.sync.proxy.minecraft.server.level;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.function.Consumer;

@ReflectionProxy(name = "net.minecraft.server.level.ServerPlayer", activeIf = "min_version=1.21.4")
public interface ServerPlayerProxy {
    ServerPlayerProxy INSTANCE = ASMProxyFactory.create(ServerPlayerProxy.class);

    @FieldGetter(name = "language")
    String getLanguage(Object target);

    @FieldGetter(name = "connection")
    Object getConnection(Object target);

    @MethodInvoker(name = "respawn", activeIf = "has_patch=folia")
    void respawn(Object target, Consumer<Object> completed, PlayerRespawnEvent.RespawnReason reason);

    @MethodInvoker(name = "tpsBar", activeIf = "has_patch=purpur")
    boolean tpsBar(Object player);

    @MethodInvoker(name = "tpsBar", activeIf = "has_patch=purpur")
    void tpsBar(Object player, boolean value);

    @MethodInvoker(name = "compassBar", activeIf = "has_patch=purpur")
    boolean compassBar(Object player);

    @MethodInvoker(name = "compassBar", activeIf = "has_patch=purpur")
    void compassBar(Object player, boolean value);

    @MethodInvoker(name = "ramBar", activeIf = "has_patch=purpur")
    boolean ramBar(Object player);

    @MethodInvoker(name = "ramBar", activeIf = "has_patch=purpur")
    void ramBar(Object player, boolean value);
}
