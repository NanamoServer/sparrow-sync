package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import org.bukkit.util.Vector;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public final class VelocityDataType extends CodecDataType<VelocityDataType.Velocity> {
    public static final DataKey VELOCITY = DataKey.sparrow("velocity");

    public VelocityDataType() {
        super(VELOCITY, Velocity.CODEC);
    }

    @NotNull
    @Override
    public Set<DataKey> dependencies() {
        return Set.of(LocationDataType.LOCATION, GlidingDataType.GLIDING, FallDistanceDataType.FALL_DISTANCE, WalkSpeedDataType.WALK_SPEED, FlySpeedDataType.FLY_SPEED);
    }

    @NotNull
    @Override
    protected Velocity captureValue(@NotNull Player player, @NotNull CaptureMode mode) {
        Vector velocity = player.getVelocity();
        return new Velocity(velocity.getX(), velocity.getY(), velocity.getZ());
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull Velocity value) {
        player.setVelocity(new Vector(value.x(), value.y(), value.z()));
    }

    public record Velocity(double x, double y, double z) {
        public static final Codec<Velocity> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.DOUBLE.fieldOf("x").forGetter(Velocity::x),
                Codec.DOUBLE.fieldOf("y").forGetter(Velocity::y),
                Codec.DOUBLE.fieldOf("z").forGetter(Velocity::z)
        ).apply(instance, Velocity::new));
    }
}
