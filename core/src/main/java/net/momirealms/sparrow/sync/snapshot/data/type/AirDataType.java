package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class AirDataType extends CodecDataType<AirDataType.Air> implements NativePlayerDataType<AirDataType.Air> {
    public static final DataKey AIR = DataKey.sparrow("air");

    public AirDataType() {
        super(AIR, Air.CODEC);
    }

    @Override
    public boolean shouldApply(@NotNull PlayerSession session) {
        return PluginConfig.synchronization$nativeAsyncApply().playerData();
    }

    @NotNull
    @Override
    public NativeApplyResult applyNative(@NotNull PlayerSession session, @NotNull CompoundTag playerData, @NotNull AirDataType.Air value) {
        playerData.putShort("Air", (short) value.remaining());
        playerData.putInt("Bukkit.MaxAirSupply", value.maximum());
        return NativeApplyResult.APPLIED_PLAYER_DATA;
    }

    @NotNull
    @Override
    protected Air captureValue(@NotNull Player player, @NotNull CaptureMode mode) {
        return new Air(player.getRemainingAir(), player.getMaximumAir());
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull Air value) {
        player.setMaximumAir(value.maximum());
        player.setRemainingAir(value.remaining());
    }

    public record Air(int remaining, int maximum) {
        public static final Codec<Air> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("remaining").forGetter(Air::remaining),
                Codec.INT.fieldOf("maximum").forGetter(Air::maximum)
        ).apply(instance, Air::new));
    }
}
