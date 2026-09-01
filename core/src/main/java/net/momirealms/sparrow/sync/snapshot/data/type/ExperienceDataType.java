package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public final class ExperienceDataType extends CodecDataType<ExperienceDataType.Experience> {
    public static final DataKey EXPERIENCE = DataKey.sparrow("experience");


    public ExperienceDataType() {
        super(EXPERIENCE, StorageFormat.STRUCTURED, Experience.CODEC);
    }

    @Override
    @NotNull
    public Set<DataKey> dependencies() {
        return Set.of(AdvancementsDataType.ADVANCEMENTS);
    }

    @Override
    @NotNull
    protected Experience captureValue(@NotNull Player player) {
        return new Experience(player.getTotalExperience(), player.getLevel(), player.getExp());
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull Experience value) {
        player.setTotalExperience(value.total());
        player.setLevel(value.level());
        player.setExp(Math.clamp(value.progress(), 0.0f, 1.0f));
    }

    public record Experience(int total, int level, float progress) {
        public static final Codec<Experience> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("total").forGetter(Experience::total),
                Codec.INT.fieldOf("level").forGetter(Experience::level),
                Codec.FLOAT.fieldOf("progress").forGetter(Experience::progress)
        ).apply(instance, Experience::new));
    }
}
