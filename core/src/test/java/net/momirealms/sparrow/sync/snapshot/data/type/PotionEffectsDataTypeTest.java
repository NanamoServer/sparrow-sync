package net.momirealms.sparrow.sync.snapshot.data.type;

import net.minecraft.SharedConstants;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.RegistryLayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig.AttributeOptions;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.codec.ops.MinecraftRegistryOps;
import net.momirealms.sparrow.sync.snapshot.data.type.AttributesDataType.AttributeValue;
import net.momirealms.sparrow.sync.snapshot.data.type.AttributesDataType.Attributes;
import net.momirealms.sparrow.sync.snapshot.data.type.AttributesDataType.ModifierValue;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PotionEffectsDataTypeTest {
    private static Object previousOps;
    private final PotionEffectsDataType type = new PotionEffectsDataType();
    private final PlayerSession session = NmsPlayerFixture.allocate(PlayerSession.class);

    @BeforeAll
    static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Field field = MinecraftRegistryOps.class.getDeclaredField("sparrowNbt");
        field.setAccessible(true);
        previousOps = field.get(null);
        field.set(null, RegistryOps.create(NBTOps.INSTANCE, RegistryLayer.createRegistryAccess().compositeAccess()));
    }

    @AfterAll
    static void restoreOps() {
        NmsPlayerFixture.set(MinecraftRegistryOps.class, null, "sparrowNbt", previousOps);
    }

    @Test
    void expiredEffectsRemoveOrphanedModifiersOutsideTheAttributeWhitelist() {
        CompoundTag playerData = NBT.createCompound();
        ListTag attributes = NBT.createList();
        attributes.add(attribute("minecraft:attack_damage", 1.0, "minecraft:effect.strength", 30.0));
        attributes.add(attribute("minecraft:movement_speed", 0.1, "minecraft:effect.speed", 0.4));
        attributes.add(attribute("minecraft:safe_fall_distance", 3.0, "minecraft:effect.jump_boost", 2.0));
        attributes.getCompound(0).getList("modifiers").add(modifier("example:bonus", 7.0));
        playerData.put("attributes", attributes);

        this.type.applyNative(this.session, playerData, List.of());

        assertTrue(playerData.getList("active_effects").isEmpty());
        ListTag result = playerData.getList("attributes");
        assertEquals(1.0, result.getCompound(0).getDouble("base"));
        assertEquals(1, result.getCompound(0).getList("modifiers").size());
        assertEquals("example:bonus", result.getCompound(0).getList("modifiers").getCompound(0).getString("id"));
        assertTrue(result.getCompound(1).getList("modifiers").isEmpty());
        assertTrue(result.getCompound(2).getList("modifiers").isEmpty());
        assertEquals(2, attributes.getCompound(0).getList("modifiers").size());
    }

    @Test
    void changedAmplifierReplacesTheLocalModifierAndKeepsOnlyOneCopy() throws Exception {
        CompoundTag playerData = NBT.createCompound();
        ListTag attributes = NBT.createList();
        attributes.add(attribute("minecraft:attack_damage", 5.0, "minecraft:effect.strength", 6.0));
        playerData.put("attributes", attributes);
        List<MobEffectInstance> effects = List.of(new MobEffectInstance(MobEffects.STRENGTH, 200, 9));

        this.type.applyNative(this.session, playerData, effects);
        this.type.applyNative(this.session, playerData, effects);

        CompoundTag attack = playerData.getList("attributes").getCompound(0);
        assertEquals(5.0, attack.getDouble("base"));
        assertEquals(1, attack.getList("modifiers").size());
        assertEquals(30.0, attack.getList("modifiers").getCompound(0).getDouble("amount"));
        assertEquals("add_value", attack.getList("modifiers").getCompound(0).getString("operation"));
        assertEquals(9, this.type.decode(playerData.get("active_effects")).getFirst().getAmplifier());
    }

    @Test
    void newPlayerGetsEffectAttributesWithPlayerDefaultBaseValues() {
        CompoundTag playerData = NBT.createCompound();

        this.type.applyNative(this.session, playerData, List.of(new MobEffectInstance(MobEffects.STRENGTH, 200, 1)));

        CompoundTag attack = playerData.getList("attributes").getCompound(0);
        assertEquals("minecraft:attack_damage", attack.getString("id"));
        assertEquals(1.0, attack.getDouble("base"));
        assertEquals(6.0, attack.getList("modifiers").getCompound(0).getDouble("amount"));
    }

    @Test
    void defaultAttributeMergePreservesRebuiltHealthBoostAndOtherLocalModifiers() {
        CompoundTag playerData = NBT.createCompound();
        ListTag attributes = NBT.createList();
        attributes.add(attribute("minecraft:max_health", 20.0, "minecraft:effect.health_boost", 4.0));
        attributes.getCompound(0).getList("modifiers").add(modifier("minecraft:creative_mode_test", 1.0));
        playerData.put("attributes", attributes);
        this.type.applyNative(this.session, playerData, List.of(new MobEffectInstance(MobEffects.HEALTH_BOOST, 200, 2)));
        Attributes remote = new Attributes(new AttributeValue[]{
                new AttributeValue(NamespacedKey.minecraft("max_health"), 40.0, new ModifierValue[0])
        });

        ListTag merged = (ListTag) AttributesDataType.mergeNative(playerData.get("attributes"), remote, new AttributeOptions());

        CompoundTag health = merged.getCompound(0);
        assertEquals(40.0, health.getDouble("base"));
        assertEquals(2, health.getList("modifiers").size());
        assertEquals("minecraft:creative_mode_test", health.getList("modifiers").getCompound(0).getString("id"));
        assertEquals(12.0, health.getList("modifiers").getCompound(1).getDouble("amount"));
        assertFalse(new AttributeOptions().attributeAllowed("minecraft:attack_damage"));
    }

    private static CompoundTag attribute(String id, double base, String modifierId, double amount) {
        CompoundTag attribute = NBT.createCompound();
        attribute.putString("id", id);
        attribute.putDouble("base", base);
        ListTag modifiers = NBT.createList();
        modifiers.add(modifier(modifierId, amount));
        attribute.put("modifiers", modifiers);
        return attribute;
    }

    private static CompoundTag modifier(String id, double amount) {
        CompoundTag modifier = NBT.createCompound();
        modifier.putString("id", id);
        modifier.putDouble("amount", amount);
        modifier.putString("operation", "add_value");
        return modifier;
    }
}
