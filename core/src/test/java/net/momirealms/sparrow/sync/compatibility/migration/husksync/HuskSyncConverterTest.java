package net.momirealms.sparrow.sync.compatibility.migration.husksync;

import com.google.gson.Gson;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.compatibility.migration.MigrationAssertions;
import net.momirealms.sparrow.sync.compatibility.migration.MigrationDataTypes;
import net.momirealms.sparrow.sync.compatibility.migration.MigrationSource;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.codec.ops.MinecraftRegistryOps;
import net.momirealms.sparrow.sync.snapshot.data.SnapshotDecoder;
import net.momirealms.sparrow.sync.snapshot.data.type.HealthDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.HealthScaleDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.HungerDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.InventoryDataType;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import net.william278.husksync.data.BukkitData;
import net.william278.husksync.data.Data;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class HuskSyncConverterTest {
    private final Map<Field, Object> previous = new LinkedHashMap<>();
    private DataRegistry registry;

    @BeforeEach
    void setup() throws Exception {
        BukkitProxy.init("1.21.8", List.of("paper"));
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        this.replace(CraftRegistry.class, "registry", RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        this.replace(MinecraftRegistryOps.class, "sparrowNbt", VanillaRegistries.createLookup().createSerializationContext(NBTOps.INSTANCE));
        this.replace(SparrowSync.class, "instance", NmsPlayerFixture.allocate(SparrowSync.class));
        this.registry = MigrationDataTypes.createRegistry();
    }

    private void replace(Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        this.previous.put(field, field.get(null));
        field.set(null, value);
    }

    @AfterEach
    void restore() throws Exception {
        for (var entry : this.previous.entrySet()) {
            entry.getKey().set(null, entry.getValue());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disabledRuntimeTypesSurviveMigrationZip(boolean inventoryEnabled, @TempDir Path directory) throws Exception {
        DataRegistry runtime = new DataRegistry();
        if (inventoryEnabled) {
            runtime.register(new InventoryDataType());
        }
        runtime.freeze();
        Map<DataKey, Tag> converted = new HuskSyncConverter(this.registry).convert(this.fields());
        assertEquals(Set.of("inventory", "ender_chest", "health", "health_scale", "hunger", "experience", "game_mode", "flight_status", "location", "potion_effects", "attributes", "advancements", "statistics").stream().map(DataKey::sparrow).collect(Collectors.toSet()), converted.keySet());
        assertEquals(new HealthDataType.Health(12), new HealthDataType().decode(converted.get(HealthDataType.HEALTH), 0));
        assertEquals(new HealthScaleDataType.HealthScale(40, true), new HealthScaleDataType().decode(converted.get(HealthScaleDataType.HEALTH_SCALE), 0));
        assertEquals(new HungerDataType.Hunger(13, 4, 2, 0), new HungerDataType().decode(converted.get(HungerDataType.HUNGER), 0));
        MigrationSource source = new MigrationSource() {
            public String id() { return "husksync"; }
            public void read(Sink sink) throws Exception {
                sink.accept(new PlayerData(new UUID(0, 1), null, null, SharedConstants.getCurrentVersion().dataVersion().version(), converted));
            }
        };
        var snapshot = MigrationAssertions.assertZipRoundTrip(directory, source, converted);
        var decoded = new SnapshotDecoder(runtime).decodeForApply(snapshot);
        for (var key : converted.keySet()) {
            if (inventoryEnabled && key.equals(InventoryDataType.INVENTORY)) {
                assertNotNull(decoded.value(key));
            } else {
                assertNull(runtime.type(key));
                assertNull(decoded.value(key));
            }
        }
        assertEquals(inventoryEnabled ? 1 : 0, runtime.size());
    }

    @Test
    void unknownSourceFieldStillFailsConversion() {
        assertThrows(IOException.class, () -> new HuskSyncConverter(this.registry).convert(Map.of("custom:unknown", BukkitData.Health.from(12, 20, false))));
    }

    private Map<String, Data> fields() {
        Map<String, Data> fields = new LinkedHashMap<>();
        fields.put("husksync:inventory", BukkitData.Items.Inventory.from(new ItemStack[41], 3));
        fields.put("husksync:ender_chest", BukkitData.Items.EnderChest.empty());
        fields.put("husksync:health", BukkitData.Health.from(12, 40, true));
        fields.put("husksync:hunger", BukkitData.Hunger.from(13, 4, 2));
        fields.put("husksync:experience", BukkitData.Experience.from(500, 25, 0.75f));
        fields.put("husksync:game_mode", BukkitData.GameMode.from("SURVIVAL"));
        fields.put("husksync:flight_status", BukkitData.FlightStatus.from(true, true));
        fields.put("husksync:location", BukkitData.Location.from(12, 64, -3, 90, 0, new Data.Location.World("unloaded-world", new UUID(0, 2), "NORMAL")));
        fields.put("husksync:potion_effects", BukkitData.PotionEffects.from(List.of()));
        fields.put("husksync:attributes", new Gson().fromJson("{\"attributes\":[{\"name\":\"minecraft:max_health\",\"baseValue\":36,\"modifiers\":[]}]}", BukkitData.Attributes.class));
        fields.put("husksync:advancements", BukkitData.Advancements.from(List.of()));
        fields.put("husksync:statistics", new Gson().fromJson("{\"genericStatistics\":{},\"blockStatistics\":{},\"itemStatistics\":{},\"entityStatistics\":{}}", BukkitData.Statistics.class));
        return fields;
    }
}
