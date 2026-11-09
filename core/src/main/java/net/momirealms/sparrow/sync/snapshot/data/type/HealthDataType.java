package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.data.DataDisplay;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.List;
import java.util.Optional;

public final class HealthDataType extends CodecDataType<HealthDataType.Health> implements NativePlayerDataType<HealthDataType.Health> {
    public static final DataKey HEALTH = DataKey.sparrow("health");

    public HealthDataType() {
        super(HEALTH, Health.CODEC);
    }

    @NotNull
    @Override
    public DataDisplay<Health> display() {
        return value -> List.of(Component.translatable("gui.additional.health").arguments(Component.text(value.health())));
    }

    @Override
    @NotNull
    public Set<DataKey> dependencies() {
        return Set.of(AttributesDataType.ATTRIBUTES, HealthScaleDataType.HEALTH_SCALE, PotionEffectsDataType.POTION_EFFECTS);
    }

    @Override
    public boolean supportsAsyncCapture() {
        return true;
    }

    @Override
    @NotNull
    protected Health captureValue(@NotNull Player player, @NotNull CaptureMode mode) {
        return new Health(player.getHealth(), Optional.of(player.getAbsorptionAmount()));
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull Health value) {
        value.absorption().ifPresent(player::setAbsorptionAmount);
        CraftPlayer craft = (CraftPlayer) player;
        // 登录时直接写入零血量状态
        if (value.health() <= 0.0) {
            craft.setRealHealth(0.0);
            craft.updateScaledHealth(true);
            return;
        }
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        double max = maxHealth == null ? 20.0 : maxHealth.getValue();
        double target = Math.min(value.health(), max);
        // 恢复存活快照时清除死亡计时
        if (player.getHealth() <= 0.0) {
            craft.setRealHealth(target);
            craft.updateScaledHealth(true);
            craft.getHandle().deathTime = 0;
            return;
        }
        // 正血量使用 Bukkit 的范围检查
        player.setHealth(target);
    }

    @Override
    public boolean shouldApply(@NotNull PlayerSession session) {
        return PluginConfig.synchronization$nativeAsyncApply().playerData();
    }

    @Override
    @NotNull
    public NativeApplyResult applyNative(@NotNull PlayerSession session, @NotNull CompoundTag playerData, @NotNull Health value) {
        if (Double.isNaN(value.health())) return NativeApplyResult.NOT_APPLIED;
        playerData.putFloat("Health", (float) value.health());
        value.absorption().ifPresent(absorption -> playerData.putFloat("AbsorptionAmount", absorption.floatValue()));
        // 恢复正血量时清除本地死亡状态, 防止新玩家实体继续死亡计时
        if (value.health() > 0.0) playerData.putShort("DeathTime", (short) 0);
        if (value.absorption().isEmpty()) return NativeApplyResult.APPLIED_PLAYER_DATA;
        // Join 阶段的药水恢复可能补满吸收值, 在其后应用快照中的剩余量.
        return NativeApplyResult.APPLIED_PLAYER_DATA.withHandoff(player -> player.setAbsorptionAmount(value.absorption().get()));
    }

    public record Health(double health, @NotNull Optional<Double> absorption) {
        public static final Codec<Health> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.DOUBLE.fieldOf("health").forGetter(Health::health),
                Codec.DOUBLE.optionalFieldOf("absorption").forGetter(Health::absorption)
        ).apply(instance, Health::new));

        public Health(double health) {
            this(health, Optional.empty());
        }
    }
}
