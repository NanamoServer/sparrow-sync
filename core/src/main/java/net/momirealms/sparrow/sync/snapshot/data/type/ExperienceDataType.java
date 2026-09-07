package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public final class ExperienceDataType extends CodecDataType<ExperienceDataType.Experience> implements NativePlayerDataType<ExperienceDataType.Experience> {
    public static final DataKey EXPERIENCE = DataKey.sparrow("experience");


    public ExperienceDataType() {
        super(EXPERIENCE, Experience.CODEC);
    }

    @Override
    @NotNull
    public Set<DataKey> dependencies() {
        return Set.of(AdvancementsDataType.ADVANCEMENTS);
    }

    @Override
    public boolean supportsAsyncCapture() {
        return true;
    }

    @Override
    @NotNull
    protected Experience captureValue(@NotNull Player player, @NotNull CaptureMode mode) {
        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        return new Experience(handle.totalExperience, handle.experienceLevel, handle.experienceProgress);
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull Experience value) {
        player.setTotalExperience(value.total());
        player.setLevel(value.level());
        player.setExp(Math.clamp(value.progress(), 0.0f, 1.0f));
    }

    @Override
    public boolean shouldApply(@NotNull PlayerSession session) {
        return PluginConfig.synchronization$nativeAsyncApply().playerData();
    }

    @Override
    @NotNull
    public NativeApplyResult applyNative(@NotNull PlayerSession session, @NotNull CompoundTag playerData, @NotNull Experience value) {
        if (value.total() < 0 || value.level() < 0 || Float.isNaN(value.progress())) return NativeApplyResult.NOT_APPLIED;
        playerData.putInt("XpTotal", value.total());
        playerData.putInt("XpLevel", value.level());
        playerData.putFloat("XpP", Math.clamp(value.progress(), 0.0f, 1.0f));
        return NativeApplyResult.APPLIED_PLAYER_DATA;
    }

    public record Experience(int total, int level, float progress) {
        public static final Codec<Experience> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("total").forGetter(Experience::total),
                Codec.INT.fieldOf("level").forGetter(Experience::level),
                Codec.FLOAT.fieldOf("progress").forGetter(Experience::progress)
        ).apply(instance, Experience::new));
    }
}
