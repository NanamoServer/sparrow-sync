package net.momirealms.sparrow.sync.snapshot.data.type;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig.AttributeOptions;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.proxy.minecraft.resources.IdentifierProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.entity.ai.attributes.AttributeInstanceProxy;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void captureWithoutInjectionLeavesOriginalCallbacksUntouched() {
        AttributeMap map = new AttributeMap(net.minecraft.world.entity.player.Player.createAttributes().build());
        Player player = player(map);
        AttributesDataType type = new AttributesDataType();
        var health = map.getInstance(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        Object original = AttributeInstanceProxy.INSTANCE.getOnDirty(health);

        AttributeValue first = find(type.capture(player, CaptureMode.SYNC), "max_health");
        assertNotSame(first, find(type.capture(player, CaptureMode.SYNC), "max_health"));
        health.setBaseValue(34.0);
        AttributeValue offline = find(type.capture(player, CaptureMode.OFFLINE), "max_health");

        assertEquals(34.0, offline.base());
        assertEquals(20.0, first.base());
        assertSame(original, AttributeInstanceProxy.INSTANCE.getOnDirty(health));
    }

    @Test
    void injectionDefersValueConstructionUntilCapture() throws Exception {
        AttributeMap map = new AttributeMap(net.minecraft.world.entity.player.Player.createAttributes().build());
        Player player = player(map);
        AttributesDataType type = new AttributesDataType();
        var health = map.getInstance(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        Object original = AttributeInstanceProxy.INSTANCE.getOnDirty(health);

        type.injectTracker(player);

        Object installed = AttributeInstanceProxy.INSTANCE.getOnDirty(health);
        assertNotSame(original, installed);
        Field cachedValue = installed.getClass().getDeclaredField("value");
        cachedValue.setAccessible(true);
        assertNull(cachedValue.get(installed));
        health.setBaseValue(37.0);
        assertEquals(37.0, find(type.capture(player, CaptureMode.SYNC), "max_health").base());
        assertNotNull(cachedValue.get(installed));
    }

    @Test
    void injectionFailureAffectsOnlyThatInstanceAndDoesNotRepeatDuringCapture() {
        IllegalStateException failure = new IllegalStateException("attribute temporarily unavailable");
        AttributeMap map = new AttributeMap(net.minecraft.world.entity.player.Player.createAttributes().build()) {
            private boolean failHealth = true;

            @Override
            public net.minecraft.world.entity.ai.attributes.AttributeInstance getInstance(net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute) {
                if (this.failHealth && attribute == net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH) {
                    this.failHealth = false;
                    throw failure;
                }
                return super.getInstance(attribute);
            }
        };
        Player player = player(map);
        AttributesDataType type = new AttributesDataType();
        List<String> warnings = new ArrayList<>();
        PluginLogger console = (PluginLogger) Proxy.newProxyInstance(PluginLogger.class.getClassLoader(), new Class<?>[]{PluginLogger.class}, (proxy, method, args) -> {
            assertEquals("warn", method.getName());
            warnings.add((String) args[0]);
            assertSame(failure, args[1]);
            return null;
        });
        SparrowSync previous = SparrowSync.instance();
        SparrowSync plugin = NmsPlayerFixture.allocate(SparrowSync.class);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "logger", new SyncLogger(console));
        NmsPlayerFixture.set(SparrowSync.class, null, "instance", plugin);
        try {
            type.injectTracker(player);
            Attributes first = type.capture(player, CaptureMode.SYNC);
            Attributes second = type.capture(player, CaptureMode.SYNC);
            assertNotSame(find(first, "max_health"), find(second, "max_health"));
            assertSame(find(first, "luck"), find(second, "luck"));
            player.getAttribute(Attribute.MAX_HEALTH).setBaseValue(29.0);
            assertEquals(29.0, find(type.capture(player, CaptureMode.SYNC), "max_health").base());

            Player other = player(new AttributeMap(net.minecraft.world.entity.player.Player.createAttributes().build()));
            type.injectTracker(other);
            AttributeValue otherHealth = find(type.capture(other, CaptureMode.SYNC), "max_health");
            assertSame(otherHealth, find(type.capture(other, CaptureMode.SYNC), "max_health"));
            assertEquals(1, warnings.size());
            assertTrue(warnings.getFirst().contains("TestPlayer"));
            assertTrue(warnings.getFirst().contains("minecraft:max_health"));

            type.injectTracker(player);
            AttributeValue retried = find(type.capture(player, CaptureMode.SYNC), "max_health");
            assertSame(retried, find(type.capture(player, CaptureMode.SYNC), "max_health"));
            assertEquals(1, warnings.size());
        } finally {
            NmsPlayerFixture.set(SparrowSync.class, null, "instance", previous);
        }
    }

    @Test
    void captureReadsLiveValuesAndDetachesModifiers() throws Exception {
        Player player = player(new AttributeMap(net.minecraft.world.entity.player.Player.createAttributes().build()));
        AttributeInstance health = player.getAttribute(Attribute.MAX_HEALTH);
        health.setBaseValue(32.0);
        health.addModifier(modifier("example:bonus", 3.0));
        health.addModifier(modifier("minecraft:effect.health_boost", 4.0));
        AttributesDataType type = new AttributesDataType();

        type.injectTracker(player);
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
    void cacheReusesOnlyUnchangedInstancesAndKeepsEachCaptureArrayIndependent() {
        AttributeMap map = new AttributeMap(net.minecraft.world.entity.player.Player.createAttributes().build());
        Player player = player(map);
        AttributesDataType type = new AttributesDataType();
        type.injectTracker(player);
        Attributes first = type.capture(player, CaptureMode.SYNC);
        var health = map.getInstance(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        Object callback = AttributeInstanceProxy.INSTANCE.getOnDirty(health);

        type.injectTracker(player);
        Attributes same = type.capture(player, CaptureMode.SYNC);

        assertNotSame(first.values(), same.values());
        for (int i = 0; i < first.values().length; i++) {
            assertSame(first.values()[i], same.values()[i]);
        }
        assertSame(callback, AttributeInstanceProxy.INSTANCE.getOnDirty(health));
        health.setBaseValue(31.0);
        health.getValue();
        Attributes changed = type.capture(player, CaptureMode.SYNC);
        assertNotSame(find(first, "max_health"), find(changed, "max_health"));
        assertEquals(31.0, find(changed, "max_health").base());
        assertSame(find(first, "luck"), find(changed, "luck"));
        assertSame(find(changed, "max_health"), find(type.capture(player, CaptureMode.OFFLINE), "max_health"));
        assertFalse(type.supportsAsyncCapture());
    }

    @Test
    void transientModifierUpdatesAndLastRemovalInvalidateWithoutChangingOldSnapshots() {
        AttributeMap map = new AttributeMap(net.minecraft.world.entity.player.Player.createAttributes().build());
        Player player = player(map);
        AttributesDataType type = new AttributesDataType();
        type.injectTracker(player);
        var health = map.getInstance(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        var firstModifier = new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                net.minecraft.resources.ResourceLocation.parse("example:transient"), 2.0, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE);
        AttributeValue empty = find(type.capture(player, CaptureMode.SYNC), "max_health");
        health.addTransientModifier(firstModifier);
        AttributeValue added = find(type.capture(player, CaptureMode.SYNC), "max_health");
        assertNotSame(empty, added);
        assertEquals(2.0, added.modifiers()[0].amount());

        health.addOrUpdateTransientModifier(firstModifier);
        assertSame(added, find(type.capture(player, CaptureMode.SYNC), "max_health"));
        var updatedModifier = new net.minecraft.world.entity.ai.attributes.AttributeModifier(firstModifier.id(), 5.0, firstModifier.operation());
        health.addOrUpdateTransientModifier(updatedModifier);
        AttributeValue updated = find(type.capture(player, CaptureMode.SYNC), "max_health");
        assertNotSame(added, updated);
        assertEquals(5.0, updated.modifiers()[0].amount());

        health.removeModifier(updatedModifier);
        AttributeValue removed = find(type.capture(player, CaptureMode.SYNC), "max_health");
        assertNotSame(updated, removed);
        assertEquals(0, removed.modifiers().length);
        assertEquals(2.0, added.modifiers()[0].amount());
        assertEquals(5.0, updated.modifiers()[0].amount());
    }

    @Test
    void blacklistReloadInvalidatesCachedValuesWithoutAnAttributeMutation() throws Exception {
        configure(List.of("max_health"), List.of());
        Player player = player(new AttributeMap(net.minecraft.world.entity.player.Player.createAttributes().build()));
        player.getAttribute(Attribute.MAX_HEALTH).addModifier(modifier("example:local", 2.0));
        AttributesDataType type = new AttributesDataType();
        type.injectTracker(player);
        AttributeValue first = find(type.capture(player, CaptureMode.SYNC), "max_health");

        configure(List.of("max_health"), List.of("example:local"));
        AttributeValue filtered = find(type.capture(player, CaptureMode.SYNC), "max_health");

        assertNotSame(first, filtered);
        assertEquals(1, first.modifiers().length);
        assertEquals(0, filtered.modifiers().length);
    }

    @Test
    void replacedAttributeInstanceUsesDenseCaptureUntilExplicitInjection() {
        AttributeMap map = new AttributeMap(net.minecraft.world.entity.player.Player.createAttributes().build());
        Player player = player(map);
        AttributesDataType type = new AttributesDataType();
        type.injectTracker(player);
        AttributeValue first = find(type.capture(player, CaptureMode.SYNC), "max_health");
        var original = map.getInstance(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);

        map.registerAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        var replacement = map.getInstance(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        Object replacementCallback = AttributeInstanceProxy.INSTANCE.getOnDirty(replacement);
        replacement.setBaseValue(45.0);
        AttributeValue replaced = find(type.capture(player, CaptureMode.SYNC), "max_health");

        assertNotSame(original, replacement);
        assertNotSame(first, replaced);
        assertEquals(45.0, replaced.base());
        assertNotSame(replaced, find(type.capture(player, CaptureMode.SYNC), "max_health"));
        assertSame(replacementCallback, AttributeInstanceProxy.INSTANCE.getOnDirty(replacement));
        type.injectTracker(player);
        AttributeValue cached = find(type.capture(player, CaptureMode.SYNC), "max_health");
        assertSame(cached, find(type.capture(player, CaptureMode.SYNC), "max_health"));
    }

    @Test
    void invalidationPrecedesOriginalCallbackAndSurvivesItsFailure() {
        AttributeMap map = new AttributeMap(net.minecraft.world.entity.player.Player.createAttributes().build());
        Player player = player(map);
        AttributesDataType type = new AttributesDataType();
        var health = map.getInstance(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        Consumer<net.minecraft.world.entity.ai.attributes.AttributeInstance> original = AttributeInstanceProxy.INSTANCE.getOnDirty(health);
        AtomicInteger calls = new AtomicInteger();
        AttributeInstanceProxy.INSTANCE.setOnDirty(health, instance -> {
            original.accept(instance);
            calls.incrementAndGet();
            assertEquals(instance.getBaseValue(), find(type.capture(player, CaptureMode.SYNC), "max_health").base());
            throw new IllegalStateException("callback failure");
        });
        type.injectTracker(player);
        AttributeValue first = find(type.capture(player, CaptureMode.SYNC), "max_health");

        assertThrows(IllegalStateException.class, () -> health.setBaseValue(39.0));

        assertEquals(1, calls.get());
        assertNotSame(first, find(type.capture(player, CaptureMode.SYNC), "max_health"));
        assertEquals(39.0, find(type.capture(player, CaptureMode.SYNC), "max_health").base());
    }

    @Test
    void failedRebuildRetriesAndReplaceFromInvalidates() {
        AttributeMap map = new AttributeMap(net.minecraft.world.entity.player.Player.createAttributes().build());
        Player player = player(map);
        AttributesDataType type = new AttributesDataType();
        type.injectTracker(player);
        AttributeValue first = find(type.capture(player, CaptureMode.SYNC), "max_health");
        var health = map.getInstance(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        Map<Object, net.minecraft.world.entity.ai.attributes.AttributeModifier> modifiers = AttributeInstanceProxy.INSTANCE.getModifierById(health);
        Object broken = IdentifierProxy.INSTANCE.newInstance("example", "broken");
        modifiers.put(broken, null);
        health.setBaseValue(33.0);
        assertThrows(NullPointerException.class, () -> type.capture(player, CaptureMode.SYNC));
        modifiers.remove(broken);

        AttributeValue retried = find(type.capture(player, CaptureMode.SYNC), "max_health");
        assertEquals(33.0, retried.base());
        assertEquals(20.0, first.base());
        var source = new net.minecraft.world.entity.ai.attributes.AttributeInstance(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH, ignored -> {});
        source.setBaseValue(47.0);
        health.replaceFrom(source);
        assertEquals(47.0, find(type.capture(player, CaptureMode.SYNC), "max_health").base());
        assertNotSame(retried, find(type.capture(player, CaptureMode.SYNC), "max_health"));
        source.setBaseValue(51.0);
        health.apply(source.pack());
        assertEquals(51.0, find(type.capture(player, CaptureMode.SYNC), "max_health").base());
    }

    @Test
    void captureRebuildsTargetsWhenConfigurationChanges() throws Exception {
        Player player = player(new AttributeMap(net.minecraft.world.entity.player.Player.createAttributes().build()));
        AttributesDataType type = new AttributesDataType();
        configure(List.of("max_health"), List.of());
        type.injectTracker(player);
        assertEquals(List.of("minecraft:max_health"), keys(type.capture(player, CaptureMode.SYNC)));
        Object firstTargets = targets(type);

        configure(List.of("luck", "minecraft:luck", "minecraft:movement_*"), List.of());

        assertEquals(Set.of("minecraft:luck", "minecraft:movement_speed", "minecraft:movement_efficiency"), Set.copyOf(keys(type.capture(player, CaptureMode.SYNC))));
        assertEquals(3, type.capture(player, CaptureMode.SYNC).values().length);
        assertNotSame(firstTargets, targets(type));
        Attributes uncached = type.capture(player, CaptureMode.SYNC);
        assertNotSame(find(uncached, "luck"), find(type.capture(player, CaptureMode.SYNC), "luck"));
        type.injectTracker(player);
        Attributes cached = type.capture(player, CaptureMode.SYNC);
        assertSame(find(cached, "luck"), find(type.capture(player, CaptureMode.SYNC), "luck"));
    }

    @Test
    void captureSkipsMissingInstancesAndHandlesEmptyWhitelist() throws Exception {
        Player empty = player(new AttributeMap(AttributeSupplier.builder().build()));
        AttributesDataType type = new AttributesDataType();
        type.injectTracker(empty);
        assertEquals(0, type.capture(empty, CaptureMode.SYNC).values().length);
        configure(List.of(), List.of());
        Player unused = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> {
            if (method.getName().equals("getUniqueId")) return new UUID(0L, 0L);
            throw new AssertionError("empty whitelist accessed player: " + method.getName());
        });
        type.injectTracker(unused);
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

        type.injectTracker(player);
        AttributeValue cachedHealth = find(type.capture(player, CaptureMode.SYNC), "max_health");
        type.apply(player, remote);

        assertEquals(40.0, health.getBaseValue());
        assertEquals(0.0, luck.getBaseValue());
        assertEquals(Set.of(modifier("example:local", 2.0), modifier("example:remote", 5.0)), Set.copyOf(health.getModifiers()));
        AttributeValue appliedHealth = find(type.capture(player, CaptureMode.SYNC), "max_health");
        assertNotSame(cachedHealth, appliedHealth);
        assertEquals(40.0, appliedHealth.base());
        assertEquals("example:remote", appliedHealth.modifiers()[0].key().toString());
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
