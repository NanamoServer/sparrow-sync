package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public final class HealthDataType extends CodecDataType<HealthDataType.Health> implements NativePlayerDataType<HealthDataType.Health> {
    public static final DataKey HEALTH = DataKey.sparrow("health");

    public HealthDataType() {
        super(HEALTH, Health.CODEC);
    }

    @Override
    @NotNull
    public Set<DataKey> dependencies() {
        return Set.of(AttributesDataType.ATTRIBUTES, HealthScaleDataType.HEALTH_SCALE);
    }

    @Override
    public boolean supportsAsyncCapture() {
        return true;
    }

    @Override
    @NotNull
    protected Health captureValue(@NotNull Player player, @NotNull CaptureMode mode) {
        return new Health(player.getHealth());
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull Health value) {
        CraftPlayer craft = (CraftPlayer) player;
        // 零血量作为快照状态写入, 登录应用只写入状态.
        if (value.health() <= 0.0) {
            craft.setRealHealth(0.0);
            craft.updateScaledHealth(true);
            return;
        }
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        double max = maxHealth == null ? 20.0 : maxHealth.getValue();
        double target = Math.min(value.health(), max);
        // 活快照清除登录数据中的死亡计时.
        if (player.getHealth() <= 0.0) {
            craft.setRealHealth(target);
            craft.updateScaledHealth(true);
            craft.getHandle().deathTime = 0;
            return;
        }
        // 正血量沿用 Bukkit 的范围检查.
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
