package net.momirealms.sparrow.sync.proxy.minecraft.server.players;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldSetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.server.players.PlayerList", activeIf = "min_version=1.21.4")
public interface PlayerListProxy {
    PlayerListProxy INSTANCE = ASMProxyFactory.create(PlayerListProxy.class);

    @FieldGetter(name = "playerIo")
    Object getPlayerIo(Object target);

    @FieldSetter(name = "playerIo")
    void setPlayerIo(Object target, @Type(name = "net.minecraft.world.level.storage.PlayerDataStorage") Object value);
}
