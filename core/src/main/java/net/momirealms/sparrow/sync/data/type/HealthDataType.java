package net.momirealms.sparrow.sync.data.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.momirealms.sparrow.sync.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * 血量同步: 血量, 血量条缩放与是否启用缩放. 快照原样记录死亡态 (health 0),
 * 应用按死活四象限对齐状态 (Q8): <strong>同步的职责是让状态一致, 不是重演事件</strong> ——
 * 玩家可能已在别的服死过或重生过, 那边的事件早已广播, 本服只做纯状态写入,
 * 不触发 PlayerDeathEvent / PlayerRespawnEvent / 掉落 / 死亡消息.
 * <p>
 * 本对齐只对 join 阶段成立 (客户端 LocalPlayer 的 health 默认 1.0, 第一个 SetHealth 包
 * 就是对齐后的值); 对<strong>已在线</strong>玩家推送"死转活"(M17 restore) 时客户端可能
 * 停在死亡界面且不会因 health 恢复而关闭, 那条路径必须真走 respawn 流程.
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

    public record Health(double health, double scale, boolean scaled) {
        public static final Codec<Health> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.DOUBLE.fieldOf("health").forGetter(Health::health),
                Codec.DOUBLE.fieldOf("scale").forGetter(Health::scale),
                Codec.BOOL.fieldOf("scaled").forGetter(Health::scaled)
        ).apply(instance, Health::new));
    }
}
