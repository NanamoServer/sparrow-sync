package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.nbt.CompoundTag;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;

public final class HealthDataType extends CodecDataType<HealthDataType.Health> implements NativePlayerDataType<HealthDataType.Health> {
    public static final DataKey HEALTH = DataKey.sparrow("health");

    public HealthDataType() {
        super(HEALTH, StorageFormat.STRUCTURED, Health.CODEC);
    }

    @Override
    @NotNull
    public Set<DataKey> dependencies() {
        return Set.of(AttributesDataType.ATTRIBUTES, HealthScaleDataType.HEALTH_SCALE);
    }

    @Override
    @NotNull
    protected Health captureValue(@NotNull Player player) {
        return new Health(player.getHealth());
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull Health value) {
        CraftPlayer craft = (CraftPlayer) player;
        // 快照死
        if (value.health() <= 0.0) {
            craft.setRealHealth(0.0);
            craft.updateScaledHealth(true);
            return;
        }
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        double max = maxHealth == null ? 20.0 : maxHealth.getValue();
        double target = Math.min(value.health(), max);
        // 本地死 + 快照活就原地复活
        if (player.getHealth() <= 0.0) {
            craft.setRealHealth(target);
            craft.updateScaledHealth(true);
            craft.getHandle().deathTime = 0;
            return;
        }
        // 本地活 + 快照活走正常规路径
        player.setHealth(target);
    }

    @Override
    @NotNull
    public NativeApplyResult applyNative(@NotNull UUID player, @NotNull CompoundTag playerData, @NotNull Health value) {
        if (Double.isNaN(value.health())) return NativeApplyResult.NOT_APPLIED;
        playerData.putFloat("Health", (float) value.health());
        // 本地死亡残留不能跟着活快照进入新 Player, 否则实体会带正血量继续死亡计时.
        if (value.health() > 0.0) playerData.putShort("DeathTime", (short) 0);
        return NativeApplyResult.APPLIED_PLAYER_DATA;
    }

    public record Health(double health) {
        public static final Codec<Health> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.DOUBLE.fieldOf("health").forGetter(Health::health)
        ).apply(instance, Health::new));
    }
}
