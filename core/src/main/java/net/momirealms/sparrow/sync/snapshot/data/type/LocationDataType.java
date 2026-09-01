package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public final class LocationDataType extends CodecDataType<LocationDataType.PlayerLocation> {
    public static final DataKey LOCATION = DataKey.sparrow("location");

    public LocationDataType() {
        super(LOCATION, StorageFormat.STRUCTURED, PlayerLocation.CODEC);
    }

    @Override
    @NotNull
    public Set<DataKey> dependencies() {
        return Set.of(FlightStatusDataType.FLIGHT_STATUS, PotionEffectsDataType.POTION_EFFECTS);
    }

    @Override
    @NotNull
    protected PlayerLocation captureValue(@NotNull Player player) {
        Location location = player.getLocation();
        return new PlayerLocation(location.getWorld().getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull PlayerLocation value) {
        World world = player.getServer().getWorld(value.world());
        if (world == null) {
            throw new IllegalStateException("location world is not loaded: " + value.world());
        }
        if (!player.teleport(new Location(world, value.x(), value.y(), value.z(), value.yaw(), value.pitch()))) {
            throw new IllegalStateException("location teleport was rejected: " + value.world());
        }
    }

    public record PlayerLocation(@NotNull String world, double x, double y, double z, float yaw, float pitch) {
        public static final Codec<PlayerLocation> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("world").forGetter(PlayerLocation::world),
                Codec.DOUBLE.fieldOf("x").forGetter(PlayerLocation::x),
                Codec.DOUBLE.fieldOf("y").forGetter(PlayerLocation::y),
                Codec.DOUBLE.fieldOf("z").forGetter(PlayerLocation::z),
                Codec.FLOAT.fieldOf("yaw").forGetter(PlayerLocation::yaw),
                Codec.FLOAT.fieldOf("pitch").forGetter(PlayerLocation::pitch)
        ).apply(instance, PlayerLocation::new));
    }
}
