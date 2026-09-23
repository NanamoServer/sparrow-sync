package net.momirealms.sparrow.sync.proxy.purpur;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;
import org.bukkit.entity.Player;

@ReflectionProxy(name = "org.purpurmc.purpur.task.BossBarTask", activeIf = "has_patch=purpur")
public interface BossBarTaskProxy {
    BossBarTaskProxy INSTANCE = ASMProxyFactory.create(BossBarTaskProxy.class);

    @MethodInvoker(name = "removeFromAll", isStatic = true)
    void removeFromAll(Player player);

    @MethodInvoker(name = "addToAll", isStatic = true)
    void addToAll(@Type(name = "net.minecraft.server.level.ServerPlayer") Object player);
}
