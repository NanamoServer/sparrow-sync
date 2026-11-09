package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.momirealms.sparrow.sync.proxy.minecraft.world.entity.LivingEntityMotionProxy;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public final class GlidingDataType extends CodecDataType<GlidingDataType.Gliding> {
    public static final DataKey GLIDING = DataKey.sparrow("gliding");

    public GlidingDataType() {
        super(GLIDING, Gliding.CODEC);
    }

    @NotNull
    @Override
    public Set<DataKey> dependencies() {
        return Set.of(InventoryDataType.INVENTORY, AttributesDataType.ATTRIBUTES, PotionEffectsDataType.POTION_EFFECTS, FlightStatusDataType.FLIGHT_STATUS, LocationDataType.LOCATION);
    }

    @NotNull
    @Override
    protected Gliding captureValue(@NotNull Player player, @NotNull CaptureMode mode) {
        return new Gliding(player.isGliding(), ((CraftPlayer) player).getHandle().getFallFlyingTicks());
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull Gliding value) {
        player.setGliding(value.gliding());
        LivingEntityMotionProxy.INSTANCE.setFallFlyTicks(((CraftPlayer) player).getHandle(), player.isGliding() ? value.ticks() : 0);
    }

    public record Gliding(boolean gliding, int ticks) {
        public static final Codec<Gliding> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.fieldOf("gliding").forGetter(Gliding::gliding),
                Codec.INT.fieldOf("ticks").forGetter(Gliding::ticks)
        ).apply(instance, Gliding::new));
    }
}