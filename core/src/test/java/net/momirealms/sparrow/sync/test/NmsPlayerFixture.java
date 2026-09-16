package net.momirealms.sparrow.sync.test;

import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.food.FoodData;
import net.momirealms.sparrow.sync.snapshot.codec.ops.MinecraftRegistryOps;
import org.bukkit.craftbukkit.attribute.CraftAttributeMap;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer;
import org.bukkit.craftbukkit.persistence.CraftPersistentDataTypeRegistry;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.UUID;

public final class NmsPlayerFixture {
    private NmsPlayerFixture() {
    }

    public static CraftPlayer create() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ServerPlayer handle = allocate(ServerPlayer.class);
        CraftPlayer player = allocate(CraftPlayer.class);
        UUID uuid = new UUID(0L, 0L);
        set(CraftEntity.class, player, "entity", handle);
        set(Entity.class, handle, "bukkitEntity", player);
        set(Entity.class, handle, "uuid", uuid);
        set(net.minecraft.world.entity.player.Player.class, handle, "gameProfile", new GameProfile(uuid, "TestPlayer"));
        set(net.minecraft.world.entity.player.Player.class, handle, "abilities", new Abilities());
        set(net.minecraft.world.entity.player.Player.class, handle, "foodData", new FoodData());
        set(LivingEntity.class, handle, "activeEffects", new HashMap<>());
        set(CraftEntity.class, player, "persistentDataContainer", new CraftPersistentDataContainer(new CraftPersistentDataTypeRegistry()));
        attributes(player, new AttributeMap(net.minecraft.world.entity.player.Player.createAttributes().build()));
        return player;
    }

    /**
     * 给玩家挂一条已断开的连接, 发往这个玩家的包会被丢掉.
     * 测试环境起不来真正的 netty 连接, 这样发包代码至少能跑到底.
     */
    public static void silenceOutgoingPackets(CraftPlayer player) {
        ServerGamePacketListenerImpl listener = allocate(ServerGamePacketListenerImpl.class);
        set(ServerCommonPacketListenerImpl.class, listener, "processedDisconnect", true);
        player.getHandle().connection = listener;
    }

    /**
     * 把文本组件用的注册表 ops 指向内置注册表, 测试里没有真正的服务器实例可以取.
     */
    public static void bindStaticRegistryOps() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        set(MinecraftRegistryOps.class, null, "json", RegistryOps.create(JsonOps.INSTANCE, RegistryLayer.STATIC_ACCESS));
    }

    public static void attributes(CraftPlayer player, AttributeMap attributes) {
        set(LivingEntity.class, player.getHandle(), "attributes", attributes);
        set(LivingEntity.class, player.getHandle(), "craftAttributes", new CraftAttributeMap(attributes));
    }

    public static <T> T allocate(Class<T> type) {
        try {
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            Field field = unsafeType.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return type.cast(unsafeType.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    public static void set(Class<?> owner, Object instance, String name, Object value) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(instance, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
