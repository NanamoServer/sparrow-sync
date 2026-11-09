package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public final class FallDistanceDataType extends CodecDataType<Float> {
    public static final DataKey FALL_DISTANCE = DataKey.sparrow("fall_distance");

    public FallDistanceDataType() {
        super(FALL_DISTANCE, Codec.FLOAT);
    }

    @NotNull
    @Override
    public Set<DataKey> dependencies() {
        return Set.of(LocationDataType.LOCATION, GlidingDataType.GLIDING);
    }

    @NotNull
    @Override
    protected Float captureValue(@NotNull Player player, @NotNull CaptureMode mode) {
        return player.getFallDistance();
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull Float value) {
        player.setFallDistance(value);
    }
}