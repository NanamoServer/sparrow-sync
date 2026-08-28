package net.momirealms.sparrow.sync.data.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.momirealms.sparrow.sync.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * 血量同步: 血量, 血量条缩放与是否启用缩放.
 */
public final class HealthDataType extends CodecDataType<HealthDataType.Health> {
    public static final DataKey HEALTH = DataKey.sparrow("health");


    public HealthDataType() {
        super(HEALTH, StorageFormat.STRUCTURED, Health.CODEC);
    }

    @Override
    @NotNull
    protected Health captureValue(@NotNull Player player) {
        return new Health(player.getHealth(), player.getHealthScale(), player.isHealthScaled());
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull Health value) {
        // setHealthScale 会顺带把缩放开关置真, 必须先设数值再落开关
        player.setHealthScale(Math.max(1.0, value.scale()));
        player.setHealthScaled(value.scaled());
        // 下限 1: 应用 0 血快照会当场杀死玩家
        // todo 如果玩家本身死亡会受到影响吗?
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        double max = maxHealth == null ? 20.0 : maxHealth.getValue();
        player.setHealth(Math.clamp(value.health(), 1.0, max));
    }

    public record Health(double health, double scale, boolean scaled) {
        public static final Codec<Health> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.DOUBLE.fieldOf("health").forGetter(Health::health),
                Codec.DOUBLE.fieldOf("scale").forGetter(Health::scale),
                Codec.BOOL.fieldOf("scaled").forGetter(Health::scaled)
        ).apply(instance, Health::new));
    }
}
