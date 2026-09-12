package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.food.FoodData;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.proxy.minecraft.world.food.FoodDataProxy;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public final class HungerDataType extends CodecDataType<HungerDataType.Hunger> implements NativePlayerDataType<HungerDataType.Hunger> {
    public static final DataKey HUNGER = DataKey.sparrow("hunger");


    public HungerDataType() {
        super(HUNGER, Hunger.CODEC);
    }

    @Override
    @NotNull
    public Set<DataKey> dependencies() {
        return Set.of(AttributesDataType.ATTRIBUTES);
    }

    @Override
    public boolean supportsAsyncCapture() {
        return true;
    }

    @Override
    @NotNull
    protected Hunger captureValue(@NotNull Player player, @NotNull CaptureMode mode) {
        FoodData food = ((CraftPlayer) player).getHandle().getFoodData();
        return new Hunger(food.getFoodLevel(), food.getSaturationLevel(), food.exhaustionLevel, FoodDataProxy.INSTANCE.getTickTimer(food));
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull Hunger value) {
        player.setFoodLevel(value.food());
        player.setSaturation(value.saturation());
        player.setExhaustion(value.exhaustion());
        FoodDataProxy.INSTANCE.setTickTimer(((CraftPlayer) player).getHandle().getFoodData(), value.tickTimer());
        // 饥饿是纯数据写入, 而先应用的 health 已把旧饥饿值随血量包发给了客户端,
        // vanilla 只在值区别于上次发送时才重发, 这里主动推一次保证 HUD 同步
        player.sendHealthUpdate();
    }

    @Override
    public boolean shouldApply(@NotNull PlayerSession session) {
        return PluginConfig.synchronization$nativeAsyncApply().playerData();
    }

    @Override
    @NotNull
    public NativeApplyResult applyNative(@NotNull PlayerSession session, @NotNull CompoundTag playerData, @NotNull Hunger value) {
        playerData.putInt("foodLevel", value.food());
        playerData.putFloat("foodSaturationLevel", value.saturation());
        playerData.putFloat("foodExhaustionLevel", value.exhaustion());
        playerData.putInt("foodTickTimer", value.tickTimer());
        return NativeApplyResult.APPLIED_PLAYER_DATA;
    }

    public record Hunger(int food, float saturation, float exhaustion, int tickTimer) {
        public static final Codec<Hunger> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("food").forGetter(Hunger::food),
                Codec.FLOAT.fieldOf("saturation").forGetter(Hunger::saturation),
                Codec.FLOAT.fieldOf("exhaustion").forGetter(Hunger::exhaustion),
                Codec.INT.optionalFieldOf("tickTimer", 0).forGetter(Hunger::tickTimer)
        ).apply(instance, Hunger::new));
    }
}
