package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public final class WalkSpeedDataType extends CodecDataType<Float> {
    public static final DataKey WALK_SPEED = DataKey.sparrow("walk_speed");

    public WalkSpeedDataType() {
        super(WALK_SPEED, Codec.floatRange(-1.0F, 1.0F));
    }

    @NotNull
    @Override
    public Set<DataKey> dependencies() {
        return Set.of(GameModeDataType.GAME_MODE, FlightStatusDataType.FLIGHT_STATUS, AttributesDataType.ATTRIBUTES);
    }

    @NotNull
    @Override
    protected Float captureValue(@NotNull Player player, @NotNull CaptureMode mode) {
        return player.getWalkSpeed();
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull Float value) {
        if (PluginConfig.synchronization$dataTypes().attributes()
                && PluginConfig.synchronization$attributes().attributeAllowed(Attribute.MOVEMENT_SPEED.getKey().toString())) {
            AttributeInstance movement = player.getAttribute(Attribute.MOVEMENT_SPEED);
            double base = movement.getBaseValue();
            // Bukkit 同时修改能力值和属性基础值, 属性同步开启时保留已恢复的属性值.
            player.setWalkSpeed(value);
            movement.setBaseValue(base);
        } else {
            player.setWalkSpeed(value);
        }
    }
}
