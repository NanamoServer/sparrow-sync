package net.momirealms.sparrow.sync.snapshot.data.type;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig.AttributeOptions;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.type.AttributesDataType.AttributeValue;
import net.momirealms.sparrow.sync.snapshot.data.type.AttributesDataType.Attributes;
import net.momirealms.sparrow.sync.snapshot.data.type.AttributesDataType.ModifierValue;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class AttributesDataTypeTest {
    private Object previousConfig;

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void isolateConfiguration() throws Exception {
        Field field = PluginConfig.class.getDeclaredField("config");
        field.setAccessible(true);
        this.previousConfig = field.get(null);
        field.set(null, new PluginConfig.ConfigDefinition());
    }

    @AfterEach
    void restoreConfiguration() throws Exception {
        Field field = PluginConfig.class.getDeclaredField("config");
        field.setAccessible(true);
        field.set(null, this.previousConfig);
    }

    @Test
    void captureReadsLiveValuesAndDetachesModifiers() throws Exception {
        Player player = player(new AttributeMap(net.minecraft.world.entity.player.Player.createAttributes().build()));
        AttributeInstance health = player.getAttribute(Attribute.MAX_HEALTH);
        health.setBaseValue(32.0);
        health.addModifier(modifier("example:bonus", 3.0));
        health.addModifier(modifier("minecraft:effect.health_boost", 4.0));
        AttributesDataType type = new AttributesDataType();

        Attributes first = type.capture(player, CaptureMode.SYNC);
        Object targets = targets(type);
        AttributeValue capturedHealth = find(first, "max_health");
        assertEquals(6, first.values().length);
        assertEquals(32.0, capturedHealth.base());
        assertEquals(1, capturedHealth.modifiers().length);
        assertEquals("example:bonus", capturedHealth.modifiers()[0].key().toString());
        health.setBaseValue(40.0);
        health.removeModifier(modifier("example:bonus", 3.0));

        Attributes second = type.capture(player, CaptureMode.SYNC);
        assertSame(targets, targets(type));
        assertEquals(40.0, find(second, "max_health").base());
        assertEquals(0, find(second, "max_health").modifiers().length);
        assertEquals(32.0, capturedHealth.base());
        assertEquals(1, capturedHealth.modifiers().length);
    }

    @Test
    void captureRebuildsTargetsWhenConfigurationChanges() throws Exception {
        Player player = player(new AttributeMap(net.minecraft.world.entity.player.Player.createAttributes().build()));
        AttributesDataType type = new AttributesDataType();
        configure(List.of("max_health"), List.of());
        assertEquals(List.of("minecraft:max_health"), keys(type.capture(player, CaptureMode.SYNC)));
        Object firstTargets = targets(type);

        configure(List.of("luck", "minecraft:luck", "minecraft:movement_*"), List.of());

        assertEquals(Set.of("minecraft:luck", "minecraft:movement_speed", "minecraft:movement_efficiency"), Set.copyOf(keys(type.capture(player, CaptureMode.SYNC))));
        assertEquals(3, type.capture(player, CaptureMode.SYNC).values().length);
        assertNotSame(firstTargets, targets(type));
    }

    @Test
    void captureSkipsMissingInstancesAndHandlesEmptyWhitelist() throws Exception {
        Player empty = player(new AttributeMap(AttributeSupplier.builder().build()));
        AttributesDataType type = new AttributesDataType();
        assertEquals(0, type.capture(empty, CaptureMode.SYNC).values().length);
        configure(List.of(), List.of());
        Player unused = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> {
            if (method.getName().equals("getUniqueId")) return new UUID(0L, 0L);
            throw new AssertionError("empty whitelist accessed player: " + method.getName());
        });
        assertEquals(0, type.capture(unused, CaptureMode.SYNC).values().length);
    }

    @Test
    void applyUsesReloadedFiltersAndPreservesLocalModifiers() throws Exception {
        Player player = player(new AttributeMap(net.minecraft.world.entity.player.Player.createAttributes().build()));
        AttributeInstance health = player.getAttribute(Attribute.MAX_HEALTH);
        AttributeInstance luck = player.getAttribute(Attribute.LUCK);
        health.addModifier(modifier("example:local", 2.0));
        health.addModifier(modifier("example:old", 3.0));
        configure(List.of("max_health"), List.of("example:local"));
        AttributesDataType type = new AttributesDataType();
        Attributes remote = new Attributes(new AttributeValue[]{
                new AttributeValue(NamespacedKey.minecraft("max_health"), 40.0, new ModifierValue[]{
                        new ModifierValue(NamespacedKey.fromString("example:local"), 99.0, Operation.ADD_NUMBER, EquipmentSlotGroup.ANY),
                        new ModifierValue(NamespacedKey.fromString("example:remote"), 5.0, Operation.ADD_NUMBER, EquipmentSlotGroup.ANY)
                }),
                new AttributeValue(NamespacedKey.minecraft("luck"), 7.0, new ModifierValue[0])
        });

        type.apply(player, remote);

        assertEquals(40.0, health.getBaseValue());
        assertEquals(0.0, luck.getBaseValue());
        assertEquals(Set.of(modifier("example:local", 2.0), modifier("example:remote", 5.0)), Set.copyOf(health.getModifiers()));
        configure(List.of("luck"), List.of());
        type.apply(player, remote);
        assertEquals(7.0, luck.getBaseValue());
        assertEquals(40.0, health.getBaseValue());
    }

    @Test
    void emptyModifierBlacklistCapturesEveryModifier() throws Exception {
        configure(List.of("max_health"), List.of());
        Player player = player(new AttributeMap(net.minecraft.world.entity.player.Player.createAttributes().build()));
        player.getAttribute(Attribute.MAX_HEALTH).addModifier(modifier("minecraft:effect.health_boost", 4.0));

        AttributeValue health = find(new AttributesDataType().capture(player, CaptureMode.SYNC), "max_health");

        assertEquals(1, health.modifiers().length);
        assertEquals("minecraft:effect.health_boost", health.modifiers()[0].key().toString());
    }

    @Test
    void codecPreservesBaseValuesAndModifiers() {
        Attributes expected = new Attributes(new AttributeValue[]{new AttributeValue(
                NamespacedKey.minecraft("max_health"),
                24.0,
                new ModifierValue[]{
                        new ModifierValue(NamespacedKey.minecraft("custom_health"), 4.0, Operation.ADD_NUMBER, EquipmentSlotGroup.ANY),
                        new ModifierValue(NamespacedKey.minecraft("scaled_health"), 0.2, Operation.ADD_SCALAR, EquipmentSlotGroup.ANY),
                        new ModifierValue(NamespacedKey.minecraft("total_health"), 0.1, Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY)
                }
        )});

        Tag encoded = AttributesDataType.CODEC.encodeStart(NBTOps.INSTANCE, expected).getOrThrow();
        Attributes decoded = AttributesDataType.CODEC.parse(NBTOps.INSTANCE, encoded).getOrThrow();

        assertEquals(expected.values()[0].key(), decoded.values()[0].key());
        assertEquals(expected.values()[0].base(), decoded.values()[0].base());
        assertArrayEquals(expected.values()[0].modifiers(), decoded.values()[0].modifiers());
        CompoundTag attribute = assertInstanceOf(ListTag.class, encoded).getCompound(0);
        assertEquals("minecraft:max_health", attribute.getString("key"));
        ListTag modifiers = attribute.getList("modifiers");
        assertEquals("add_value", modifiers.getCompound(0).getString("operation"));
        assertEquals("add_multiplied_base", modifiers.getCompound(1).getString("operation"));
        assertEquals("add_multiplied_total", modifiers.getCompound(2).getString("operation"));
    }

    @Test
    void dependenciesPlaceInventoryAndEffectsBeforeAttributes() {
        AttributesDataType type = new AttributesDataType();

        assertEquals(
                Set.of(InventoryDataType.INVENTORY, PotionEffectsDataType.POTION_EFFECTS),
                type.dependencies()
        );
    }

    static Player player(AttributeMap attributes) {
        CraftPlayer player = NmsPlayerFixture.create();
        NmsPlayerFixture.attributes(player, attributes);
        return player;
    }

    static void configure(List<String> whitelist, List<String> blacklist) throws Exception {
        Object config = new PluginConfig.ConfigDefinition();
        Field synchronizationField = config.getClass().getDeclaredField("synchronization");
        synchronizationField.setAccessible(true);
        Object synchronization = synchronizationField.get(config);
        Field optionsField = synchronization.getClass().getDeclaredField("attributes");
        optionsField.setAccessible(true);
        AttributeOptions options = (AttributeOptions) optionsField.get(synchronization);
        for (String name : List.of("whitelist", "modifierBlacklist")) {
            Field field = AttributeOptions.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(options, name.equals("whitelist") ? whitelist : blacklist);
        }
        Method freeze = AttributeOptions.class.getDeclaredMethod("freeze");
        freeze.setAccessible(true);
        freeze.invoke(options);
        Field field = PluginConfig.class.getDeclaredField("config");
        field.setAccessible(true);
        field.set(null, config);
    }

    private static AttributeModifier modifier(String key, double amount) {
        return new AttributeModifier(NamespacedKey.fromString(key), amount, Operation.ADD_NUMBER, EquipmentSlotGroup.ANY);
    }

    private static AttributeValue find(Attributes attributes, String key) {
        return Arrays.stream(attributes.values()).filter(value -> value.key().equals(NamespacedKey.minecraft(key))).findFirst().orElseThrow();
    }

    private static List<String> keys(Attributes attributes) {
        return Arrays.stream(attributes.values()).map(value -> value.key().toString()).toList();
    }

    private static Object targets(AttributesDataType type) throws Exception {
        Field field = AttributesDataType.class.getDeclaredField("captureTargets");
        field.setAccessible(true);
        Object targets = field.get(type);
        assertNotNull(targets);
        return targets;
    }
}
