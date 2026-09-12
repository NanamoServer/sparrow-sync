package net.momirealms.sparrow.sync.snapshot;

import org.junit.jupiter.api.BeforeEach;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataPipeline;
import net.momirealms.sparrow.sync.snapshot.data.SnapshotDecoder;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataType;
import net.momirealms.sparrow.sync.snapshot.data.SnapshotApplyContext;
import net.momirealms.sparrow.sync.snapshot.data.type.HealthDataType;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import net.momirealms.sparrow.sync.test.PluginConfigExtension;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(PluginConfigExtension.class)
class RestoredHealthTest {
    private CraftPlayer player;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setup() throws Exception {
        this.player = NmsPlayerFixture.create();
        SynchedEntityData.Builder builder = new SynchedEntityData.Builder(this.player.getHandle());
        String[] fields = {"DATA_SHARED_FLAGS_ID", "DATA_AIR_SUPPLY_ID", "DATA_CUSTOM_NAME_VISIBLE", "DATA_CUSTOM_NAME", "DATA_SILENT", "DATA_NO_GRAVITY", "DATA_POSE", "DATA_TICKS_FROZEN"};
        Object[] defaults = {(byte) 0, 300, false, Optional.empty(), false, false, Pose.STANDING, 0};
        for (int i = 0; i < fields.length; i++) {
            Field field = Entity.class.getDeclaredField(fields[i]);
            field.setAccessible(true);
            builder.define((EntityDataAccessor) field.get(null), defaults[i]);
        }
        Method define = net.minecraft.world.entity.player.Player.class.getDeclaredMethod("defineSynchedData", SynchedEntityData.Builder.class);
        define.setAccessible(true);
        define.invoke(this.player.getHandle(), builder);
        NmsPlayerFixture.set(Entity.class, this.player.getHandle(), "entityData", builder.build());
    }

    @ParameterizedTest
    @CsvSource({"0, 18", "20, 0", "0, 0", "10, 18"})
    void appliesAllLifeTransitionsWithoutDeathOrRespawn(double previous, double target) {
        this.player.setRealHealth(previous);
        this.player.getHandle().deathTime = previous == 0 ? 40 : 0;
        PlayerDataPipeline pipeline = this.pipeline(new HealthDataType());
        SnapshotApplyContext context = this.context(pipeline, Map.of(HealthDataType.HEALTH, new HealthDataType().encode(new HealthDataType.Health(target))));
        assertInstanceOf(PlayerDataPipeline.ApplyResult.Success.class, pipeline.apply(this.player, context));
        assertEquals(target, this.player.getHealth());
        if (previous == 0 && target > 0) assertEquals(0, this.player.getHandle().deathTime);
        assertEquals(List.of(HealthDataType.HEALTH), context.applied());
    }

    private PlayerDataPipeline pipeline(PlayerDataType<?>... types) {
        DataRegistry registry = new DataRegistry();
        for (PlayerDataType<?> type : types) registry.register(type);
        registry.freeze();
        PlayerDataPipeline pipeline = new PlayerDataPipeline(null);
        NmsPlayerFixture.set(PlayerDataPipeline.class, pipeline, "dataRegistry", registry);
        NmsPlayerFixture.set(PlayerDataPipeline.class, pipeline, "decoder", new SnapshotDecoder(registry));
        PluginLogger logger = (PluginLogger) Proxy.newProxyInstance(PluginLogger.class.getClassLoader(), new Class<?>[]{PluginLogger.class}, (instance, method, args) -> null);
        NmsPlayerFixture.set(PlayerDataPipeline.class, pipeline, "logger", new SyncLogger(logger));
        return pipeline;
    }

    private SnapshotApplyContext context(PlayerDataPipeline pipeline, Map<DataKey, Tag> data) {
        Snapshot snapshot = new Snapshot(new SnapshotMeta(UUID.randomUUID(), this.player.getUniqueId(), 1, SaveCause.COMMAND, false, "test", 0), data);
        return assertInstanceOf(PlayerDataPipeline.DecodeResult.Ready.class, pipeline.decode(snapshot)).context();
    }

}
