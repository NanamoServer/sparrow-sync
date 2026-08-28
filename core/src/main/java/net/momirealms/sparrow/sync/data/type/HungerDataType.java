package net.momirealms.sparrow.sync.data.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.momirealms.sparrow.sync.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * 饥饿同步: 饥饿值, 饱和度与消耗度.
 */
public final class HungerDataType extends CodecDataType<HungerDataType.Hunger> {
    public static final DataKey HUNGER = DataKey.sparrow("hunger");


    public HungerDataType() {
        super(HUNGER, StorageFormat.STRUCTURED, Hunger.CODEC);
    }

    @Override
    @NotNull
    protected Hunger captureValue(@NotNull Player player) {
        return new Hunger(player.getFoodLevel(), player.getSaturation(), player.getExhaustion());
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull Hunger value) {
        player.setFoodLevel(value.food());
        player.setSaturation(value.saturation());
        player.setExhaustion(value.exhaustion());
    }

    public record Hunger(int food, float saturation, float exhaustion) {
        public static final Codec<Hunger> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("food").forGetter(Hunger::food),
                Codec.FLOAT.fieldOf("saturation").forGetter(Hunger::saturation),
                Codec.FLOAT.fieldOf("exhaustion").forGetter(Hunger::exhaustion)
        ).apply(instance, Hunger::new));
    }
}
