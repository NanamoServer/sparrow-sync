package net.momirealms.sparrow.sync.proxy.minecraft.server.level;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldSetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.server.level.ServerPlayer", activeIf = "min_version=1.21.4")
public interface PlayerRespawnProxy {
    PlayerRespawnProxy INSTANCE = ASMProxyFactory.create(PlayerRespawnProxy.class);

    @FieldGetter(name = "respawnConfig", activeIf = "min_version=1.21.5")
    Object getConfig(Object target);

    @FieldSetter(name = "respawnConfig", activeIf = "min_version=1.21.5")
    void setConfig(Object target, Object value);

    @FieldGetter(name = "respawnPosition", activeIf = "max_version=1.21.4")
    BlockPos getPosition(Object target);

    @FieldSetter(name = "respawnPosition", activeIf = "max_version=1.21.4")
    void setPosition(Object target, BlockPos value);

    @FieldGetter(name = "respawnDimension", activeIf = "max_version=1.21.4")
    ResourceKey<Level> getDimension(Object target);

    @FieldSetter(name = "respawnDimension", activeIf = "max_version=1.21.4")
    void setDimension(Object target, ResourceKey<Level> value);

    @FieldGetter(name = "respawnAngle", activeIf = "max_version=1.21.4")
    float getAngle(Object target);

    @FieldSetter(name = "respawnAngle", activeIf = "max_version=1.21.4")
    void setAngle(Object target, float value);

    @FieldGetter(name = "respawnForced", activeIf = "max_version=1.21.4")
    boolean getForced(Object target);

    @FieldSetter(name = "respawnForced", activeIf = "max_version=1.21.4")
    void setForced(Object target, boolean value);
}
