package net.momirealms.sparrow.sync.snapshot.data.type;

import net.minecraft.world.item.ItemStack;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig.AttributeOptions;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.session.SessionManager;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType.NativeApplyResult;
import net.momirealms.sparrow.sync.snapshot.data.type.AttributesDataType.AttributeValue;
import net.momirealms.sparrow.sync.snapshot.data.type.AttributesDataType.Attributes;
import net.momirealms.sparrow.sync.snapshot.data.type.AttributesDataType.ModifierValue;
import net.momirealms.sparrow.sync.snapshot.data.type.ExperienceDataType.Experience;
import net.momirealms.sparrow.sync.snapshot.data.type.HealthDataType.Health;
import net.momirealms.sparrow.sync.snapshot.data.type.HealthScaleDataType.HealthScale;
import net.momirealms.sparrow.sync.snapshot.data.type.HungerDataType.Hunger;
import net.momirealms.sparrow.sync.snapshot.data.type.InventoryDataType.Inventory;
import net.momirealms.sparrow.sync.snapshot.data.type.LocationDataType.PlayerLocation;
import net.momirealms.sparrow.sync.test.ConnectionFixture;
import net.momirealms.sparrow.sync.util.ItemCodec;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class NativeDataTypeTest {
    private static final UUID PLAYER = new UUID(0L, 0L);

    private final PlayerSession session = new SessionManager(null).tryOpen(PLAYER, "Steve", ConnectionFixture.create());

    @Test
    void nativePdcMergesIntoSparrowTagWithoutChangingSourceTrees() throws Exception {
        CompoundTag local = NBT.createCompound();
        local.putString("local", "kept");
        local.putString("shared", "old");
        CompoundTag playerData = NBT.createCompound();
        playerData.put("BukkitValues", local);
        net.minecraft.nbt.CompoundTag remote = new net.minecraft.nbt.CompoundTag();
        remote.putString("shared", "new");
        net.minecraft.nbt.CompoundTag remoteNested = new net.minecraft.nbt.CompoundTag();
        remoteNested.putString("marker", "remote");
        remote.put("nested", remoteNested);

        Field configField = PluginConfig.class.getDeclaredField("config");
        configField.setAccessible(true);
        Object previous = configField.get(null);
        configField.set(null, new PluginConfig.ConfigDefinition());
        try {
            assertEquals(NativeApplyResult.APPLIED_PLAYER_DATA, new PDCDataType().applyNative(this.session, playerData, remote));

            CompoundTag merged = playerData.getCompound("BukkitValues");
            assertEquals("kept", merged.getString("local"));
            assertEquals("new", merged.getString("shared"));
            assertEquals("old", local.getString("shared"));
            merged.getCompound("nested").putString("marker", "changed");
            assertEquals("remote", remoteNested.getString("marker").orElseThrow());
        } finally {
            configField.set(null, previous);
        }
    }

    @Test
    void scalarTypesWriteVanillaFields() {
        CompoundTag playerData = NBT.createCompound();
        playerData.putInt("foodTickTimer", 17);

        assertEquals(NativeApplyResult.APPLIED_PLAYER_DATA, new ExperienceDataType().applyNative(this.session, playerData, new Experience(1200, 31, 1.5f)));
        assertEquals(NativeApplyResult.APPLIED_PLAYER_DATA, new EnchantmentSeedDataType().applyNative(this.session, playerData, 13579));
        assertEquals(NativeApplyResult.APPLIED_PLAYER_DATA, new HungerDataType().applyNative(this.session, playerData, new Hunger(18, 4.5f, 0.75f, 43)));
        assertEquals(NativeApplyResult.APPLIED_PLAYER_DATA, new GameModeDataType().applyNative(this.session, playerData, GameMode.CREATIVE));

        CompoundTag stored = playerData;
        assertEquals(1200, stored.getInt("XpTotal"));
        assertEquals(31, stored.getInt("XpLevel"));
        assertEquals(1.0f, stored.getFloat("XpP"));
        assertEquals(13579, stored.getInt("XpSeed"));
        assertEquals(18, stored.getInt("foodLevel"));
        assertEquals(4.5f, stored.getFloat("foodSaturationLevel"));
        assertEquals(0.75f, stored.getFloat("foodExhaustionLevel"));
        assertEquals(43, stored.getInt("foodTickTimer"));
        assertEquals(1, stored.getInt("playerGameType"));
    }

    @Test
    void zeroEnchantmentSeedFallsBackWithoutChangingTheTag() {
        CompoundTag playerData = NBT.createCompound();
        playerData.putInt("XpSeed", 42);

        assertEquals(NativeApplyResult.NOT_APPLIED, new EnchantmentSeedDataType().applyNative(this.session, playerData, 0));

        assertEquals(42, playerData.getInt("XpSeed"));
    }

    @Test
    void healthWritesVanillaState() {
        HealthDataType type = new HealthDataType();
        CompoundTag playerData = NBT.createCompound();
        playerData.putShort("DeathTime", (short) 19);

        assertEquals(NativeApplyResult.APPLIED_PLAYER_DATA, type.applyNative(this.session, playerData, new Health(16.5)));

        CompoundTag stored = playerData;
        assertEquals(16.5f, stored.getFloat("Health"));
        assertEquals(0, stored.getShort("DeathTime"));
    }

    @Test
    void healthScaleAppliesAsAnIndependentJoinOnlyType() {
        double[] scale = new double[1];
        boolean[] scaled = {true};
        Player player = playerScale(scale, scaled);
        new HealthScaleDataType().apply(player, new HealthScale(36.0, false));

        assertEquals(36.0, scale[0]);
        assertFalse(scaled[0]);
    }

    @Test
    void emptyPotionSnapshotExplicitlyClearsTheNativeList() {
        PotionEffectsDataType type = allocateWithoutConstructor(PotionEffectsDataType.class);
        CompoundTag playerData = NBT.createCompound();

        assertEquals(NativeApplyResult.APPLIED_PLAYER_DATA, type.applyNative(this.session, playerData, List.of()));

        assertEquals(0, assertInstanceOf(ListTag.class, playerData.get("active_effects")).size());
    }

    @Test
    void standardEnderChestUsesNativeListAndExpandedChestFallsBack() {
        EnderChestDataType type = allocateWithoutConstructor(EnderChestDataType.class);
        CompoundTag standard = NBT.createCompound();

        assertEquals(NativeApplyResult.APPLIED_PLAYER_DATA, type.applyNative(this.session, standard, new ItemCodec.LoadedItems(new ItemStack[27], 0)));
        assertEquals(0, assertInstanceOf(ListTag.class, standard.get("EnderItems")).size());

        CompoundTag expanded = NBT.createCompound();
        expanded.putInt("marker", 1);
        assertEquals(NativeApplyResult.NOT_APPLIED, type.applyNative(this.session, expanded, new ItemCodec.LoadedItems(new ItemStack[54], 0)));
        assertNull(expanded.get("EnderItems"));
        assertEquals(1, expanded.getInt("marker"));
    }

    @Test
    void inventoryOnlyUsesNativeLayoutWhenCapacityMatchesThisVersion() {
        InventoryDataType type = allocateWithoutConstructor(InventoryDataType.class);
        int nativeSize = VersionHelper.isOrAbove1_21_5() ? 43 : 41;
        CompoundTag playerData = NBT.createCompound();
        if (VersionHelper.isOrAbove1_21_5()) {
            CompoundTag equipment = NBT.createCompound();
            equipment.putString("mainhand", "local-mainhand");
            equipment.putString("feet", "local-feet");
            equipment.putString("plugin-data", "kept");
            playerData.put("equipment", equipment);
        }

        assertEquals(NativeApplyResult.APPLIED_PLAYER_DATA, type.applyNative(this.session, playerData, new Inventory(new ItemStack[nativeSize], 6, 0)));

        CompoundTag stored = playerData;
        assertEquals(0, stored.getList("Inventory").size());
        assertEquals(6, stored.getInt("SelectedItemSlot"));
        if (VersionHelper.isOrAbove1_21_5()) {
            CompoundTag equipment = stored.getCompound("equipment");
            assertNull(equipment.get("mainhand"));
            assertNull(equipment.get("feet"));
            assertEquals("kept", equipment.getString("plugin-data"));
        }

        CompoundTag mismatched = NBT.createCompound();
        assertEquals(NativeApplyResult.NOT_APPLIED, type.applyNative(this.session, mismatched, new Inventory(new ItemStack[nativeSize == 43 ? 41 : 43], 0, 0)));
        assertNull(mismatched.get("Inventory"));
    }

    @Test
    void inventorySnapshotDoesNotWriteAndIgnoresLegacyCursor() throws IOException {
        InventoryDataType type = allocateWithoutConstructor(InventoryDataType.class);
        CompoundTag encoded = assertInstanceOf(CompoundTag.class, type.encode(new Inventory(new ItemStack[41], 3, 0)));

        assertNull(encoded.get("cursor"));
        encoded.putString("cursor", "legacy");
        Inventory decoded = type.decode(encoded);
        assertEquals(41, decoded.contents().length);
        assertEquals(3, decoded.heldSlot());
    }

    @Test
    void invalidHeldSlotBecomesZeroBeforeEncodingDecodingAndNativeApply() throws IOException {
        InventoryDataType type = allocateWithoutConstructor(InventoryDataType.class);
        int nativeSize = VersionHelper.isOrAbove1_21_5() ? 43 : 41;
        for (int heldSlot : new int[]{Integer.MIN_VALUE, -1, 0, 1, 8, 9, Integer.MAX_VALUE}) {
            int expected = heldSlot >= 0 && heldSlot <= 8 ? heldSlot : 0;
            Inventory value = new Inventory(new ItemStack[nativeSize], heldSlot, 0);
            assertEquals(expected, value.heldSlot());
            CompoundTag encoded = assertInstanceOf(CompoundTag.class, type.encode(value));
            assertEquals(expected, encoded.getInt("heldSlot"));
            encoded.putInt("heldSlot", heldSlot);
            assertEquals(expected, type.decode(encoded).heldSlot());

            CompoundTag playerData = NBT.createCompound();
            assertEquals(NativeApplyResult.APPLIED_PLAYER_DATA, type.applyNative(this.session, playerData, value));
            assertEquals(expected, playerData.getInt("SelectedItemSlot"));
        }
    }

    @Test
    void nativeAttributesReplaceSynchronizedValuesAndKeepLocalModifiers() {
        AttributeOptions options = new AttributeOptions();
        CompoundTag maxHealth = NBT.createCompound();
        maxHealth.putString("id", "minecraft:max_health");
        maxHealth.putDouble("base", 20.0);
        ListTag localModifiers = NBT.createList();
        localModifiers.add(modifier("minecraft:effect.health_boost", 4.0));
        localModifiers.add(modifier("example:old", 1.0));
        maxHealth.put("modifiers", localModifiers);
        CompoundTag unknown = NBT.createCompound();
        unknown.putString("id", "example:local_only");
        unknown.putDouble("base", 7.0);
        ListTag local = NBT.createList();
        local.add(maxHealth);
        local.add(unknown);
        Attributes remote = new Attributes(new AttributeValue[]{new AttributeValue(
                NamespacedKey.minecraft("max_health"),
                40.0,
                new ModifierValue[]{new ModifierValue(NamespacedKey.fromString("example:remote"), 2.0, Operation.ADD_NUMBER, EquipmentSlotGroup.ANY)}
        )});

        Tag mergedNative = AttributesDataType.mergeNative(local, remote, options);
        ListTag merged = assertInstanceOf(ListTag.class, mergedNative);
        CompoundTag mergedHealth = merged.getCompound(0);
        assertEquals(40.0, mergedHealth.getDouble("base"));
        ListTag modifiers = mergedHealth.getList("modifiers");
        assertEquals(2, modifiers.size());
        assertEquals("minecraft:effect.health_boost", modifiers.getCompound(0).getString("id"));
        assertEquals("example:remote", modifiers.getCompound(1).getString("id"));
        assertEquals("example:local_only", merged.getCompound(1).getString("id"));
    }

    @Test
    void locationWritesWorldNameAndClearsCompetingWorldIdentity() {
        LocationDataType type = new LocationDataType();
        CompoundTag playerData = NBT.createCompound();
        playerData.putString("Dimension", "minecraft:overworld");
        playerData.putLong("WorldUUIDMost", 12L);
        playerData.putLong("WorldUUIDLeast", 34L);

        assertEquals(NativeApplyResult.APPLIED_PLAYER_DATA, type.applyNative(this.session, playerData, new PlayerLocation("target", 12.5, 70.0, -4.25, 90.0f, -15.0f)));

        CompoundTag stored = playerData;
        assertEquals("target", stored.getString("world"));
        assertEquals(List.of(12.5, 70.0, -4.25), List.of(stored.getList("Pos").getDouble(0), stored.getList("Pos").getDouble(1), stored.getList("Pos").getDouble(2)));
        assertEquals(90.0f, stored.getList("Rotation").getFloat(0));
        assertEquals(-15.0f, stored.getList("Rotation").getFloat(1));
        assertNull(playerData.get("Dimension"));
        assertNull(playerData.get("WorldUUIDMost"));
        assertNull(playerData.get("WorldUUIDLeast"));
    }

    @Test
    void locationRejectsInvalidValuesWithoutChangingPlayerData() {
        LocationDataType type = new LocationDataType();

        CompoundTag playerData = NBT.createCompound();
        playerData.putInt("marker", 1);
        assertEquals(NativeApplyResult.NOT_APPLIED, type.applyNative(this.session, playerData, new PlayerLocation("target", Double.NaN, 0.0, 0.0, 0.0f, 0.0f)));
        assertEquals(NativeApplyResult.NOT_APPLIED, type.applyNative(this.session, playerData, new PlayerLocation("", 0.0, 0.0, 0.0, 0.0f, 0.0f)));
        assertEquals(1, playerData.getInt("marker"));
        assertNull(playerData.get("world"));
        assertNull(playerData.get("Pos"));
    }

    private static CompoundTag modifier(String id, double amount) {
        CompoundTag modifier = NBT.createCompound();
        modifier.putString("id", id);
        modifier.putDouble("amount", amount);
        modifier.putString("operation", "add_value");
        return modifier;
    }

    private static <T> T allocateWithoutConstructor(Class<T> type) {
        try {
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            Field field = unsafeType.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            Method allocateInstance = unsafeType.getMethod("allocateInstance", Class.class);
            return type.cast(allocateInstance.invoke(unsafe, type));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Player playerScale(double[] scale, boolean[] scaled) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
            case "setHealthScale" -> {
                scale[0] = (double) args[0];
                yield null;
            }
            case "setHealthScaled" -> {
                scaled[0] = (boolean) args[0];
                yield null;
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }
}
