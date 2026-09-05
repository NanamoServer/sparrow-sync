package net.momirealms.sparrow.sync.test;

import com.mojang.authlib.GameProfile;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.food.FoodData;
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
