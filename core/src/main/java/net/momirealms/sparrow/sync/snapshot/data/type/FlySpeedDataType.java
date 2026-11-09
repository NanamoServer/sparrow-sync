package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public final class FlySpeedDataType extends CodecDataType<Float> {
    public static final DataKey FLY_SPEED = DataKey.sparrow("fly_speed");

    public FlySpeedDataType() {
        super(FLY_SPEED, Codec.floatRange(-1.0F, 1.0F));
    }

    @NotNull
    @Override
    public Set<DataKey> dependencies() {
        return Set.of(GameModeDataType.GAME_MODE, FlightStatusDataType.FLIGHT_STATUS);
    }

    @NotNull
    @Override
    protected Float captureValue(@NotNull Player player, @NotNull CaptureMode mode) {
        return player.getFlySpeed();
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull Float value) {
        player.setFlySpeed(value);
    }
}
