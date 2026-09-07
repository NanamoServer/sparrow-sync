package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.entity.player.Abilities;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public final class FlightStatusDataType extends CodecDataType<FlightStatusDataType.FlightStatus> {
    public static final DataKey FLIGHT_STATUS = DataKey.sparrow("flight_status");

    public FlightStatusDataType() {
        super(FLIGHT_STATUS, FlightStatus.CODEC);
    }

    @Override
    @NotNull
    public Set<DataKey> dependencies() {
        return Set.of(GameModeDataType.GAME_MODE);
    }

    @Override
    @NotNull
    protected FlightStatus captureValue(@NotNull Player player, @NotNull CaptureMode mode) {
        Abilities abilities = ((CraftPlayer) player).getHandle().getAbilities();
        return new FlightStatus(abilities.mayfly, abilities.flying);
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull FlightStatus value) {
        player.setAllowFlight(value.allowFlight());
        player.setFlying(value.allowFlight() && value.flying());
    }

    public record FlightStatus(boolean allowFlight, boolean flying) {
        public static final Codec<FlightStatus> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.fieldOf("allowFlight").forGetter(FlightStatus::allowFlight),
                Codec.BOOL.fieldOf("flying").forGetter(FlightStatus::flying)
        ).apply(instance, FlightStatus::new));
    }
}
