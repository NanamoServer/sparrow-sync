package net.momirealms.sparrow.sync.compatibility.migration;

import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.data.type.AdvancementsDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.AttributesDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.EnderChestDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.ExperienceDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.FlightStatusDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.GameModeDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.HealthDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.HealthScaleDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.HungerDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.InventoryDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.LocationDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.PotionEffectsDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.StatisticsDataType;
import org.jetbrains.annotations.NotNull;

public final class MigrationDataTypes {
    private MigrationDataTypes() {
    }

    @NotNull
    public static DataRegistry createRegistry() {
        DataRegistry registry = new DataRegistry();
        registry.register(new InventoryDataType());
        registry.register(new EnderChestDataType());
        registry.register(new ExperienceDataType());
        registry.register(new HealthScaleDataType());
        registry.register(new HealthDataType());
        registry.register(new HungerDataType());
        registry.register(new GameModeDataType());
        registry.register(new PotionEffectsDataType());
        registry.register(new AdvancementsDataType());
        registry.register(new StatisticsDataType());
        registry.register(new AttributesDataType());
        registry.register(new LocationDataType());
        registry.register(new FlightStatusDataType());
        registry.freeze();
        return registry;
    }
}
