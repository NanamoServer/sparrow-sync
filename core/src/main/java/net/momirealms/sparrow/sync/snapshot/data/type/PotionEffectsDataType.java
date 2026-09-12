package net.momirealms.sparrow.sync.snapshot.data.type;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.codec.ops.MinecraftRegistryOps;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public final class PotionEffectsDataType extends CodecDataType<List<MobEffectInstance>> implements NativePlayerDataType<List<MobEffectInstance>> {
    public static final DataKey POTION_EFFECTS = DataKey.sparrow("potion_effects");


    public PotionEffectsDataType() {
        super(POTION_EFFECTS, MobEffectInstance.CODEC.listOf(), MinecraftRegistryOps::sparrowNbt);
    }

    @Override
    @NotNull
    protected List<MobEffectInstance> captureValue(@NotNull Player player, @NotNull CaptureMode mode) {
        List<MobEffectInstance> effects = new ArrayList<>();
        for (MobEffectInstance instance : handle(player).getActiveEffects()) {
            if (instance.isAmbient()) continue;
            effects.add(mode == CaptureMode.OFFLINE ? instance : copyOf(instance));
        }
        return effects;
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull List<MobEffectInstance> value) {
        ServerPlayer handle = handle(player);
        // 清空后放入的效果不与既有效果合并, 隐藏效果链随实例原样进入玩家.
        handle.removeAllEffects();
        int size = value.size();
        for (int i = 0; i < size; i++) {
            handle.addEffect(value.get(i));
        }
    }

    @Override
    public boolean shouldApply(@NotNull PlayerSession session) {
        return PluginConfig.synchronization$nativeAsyncApply().playerData();
    }

    @Override
    @NotNull
    public NativeApplyResult applyNative(@NotNull PlayerSession session, @NotNull CompoundTag playerData, @NotNull List<MobEffectInstance> value) {
        Tag effects = value.isEmpty()
                ? NBT.createList()
                : this.encode(value);
        // 空列表也必须显式写入, 否则本服 .dat 中的旧效果会在 vanilla load 时复活.
        playerData.put("active_effects", effects);
        return NativeApplyResult.APPLIED_PLAYER_DATA;
    }

    private static ServerPlayer handle(Player player) {
        return ((CraftPlayer) player).getHandle();
    }

    // 递归复制隐藏效果链, capture 返回后玩家身上的剩余时长仍会继续变化
    private static MobEffectInstance copyOf(MobEffectInstance instance) {
        MobEffectInstance hidden = instance.hiddenEffect;
        return new MobEffectInstance(
                instance.getEffect(),
                instance.getDuration(),
                instance.getAmplifier(),
                instance.isAmbient(),
                instance.isVisible(),
                instance.showIcon(),
                hidden == null ? null : copyOf(hidden)
        );
    }
}
