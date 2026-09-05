package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public final class HealthScaleDataType extends CodecDataType<HealthScaleDataType.HealthScale> {
    public static final DataKey HEALTH_SCALE = DataKey.sparrow("health_scale");

    public HealthScaleDataType() {
        super(HEALTH_SCALE, StorageFormat.STRUCTURED, HealthScale.CODEC);
    }

    @Override
    @NotNull
    public Set<DataKey> dependencies() {
        return Set.of(AttributesDataType.ATTRIBUTES);
    }

    @Override
    public boolean supportsAsyncCapture() {
        return true;
    }

    @Override
    @NotNull
    protected HealthScale captureValue(@NotNull Player player, @NotNull CaptureMode mode) {
        return new HealthScale(player.getHealthScale(), player.isHealthScaled());
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull HealthScale value) {
        // setHealthScale 会开启缩放, 最后恢复快照中的开关.
        player.setHealthScale(Math.max(1.0, value.scale()));
        player.setHealthScaled(value.scaled());
    }

    public record HealthScale(double scale, boolean scaled) {
        public static final Codec<HealthScale> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.DOUBLE.fieldOf("scale").forGetter(HealthScale::scale),
                Codec.BOOL.fieldOf("scaled").forGetter(HealthScale::scaled)
        ).apply(instance, HealthScale::new));
    }
}
