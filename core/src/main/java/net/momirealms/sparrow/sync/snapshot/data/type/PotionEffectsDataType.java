package net.momirealms.sparrow.sync.snapshot.data.type;

import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.snapshot.codec.ops.MinecraftRegistryOps;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 药水效果同步, 经 NMS MobEffectInstance CODEC 序列化以保留递归的隐藏效果链
 * (力量 III 压住的力量 II 在 III 过期后恢复, Bukkit 的 addPotionEffect 无法直接重建该链).
 * ambient 效果 (信标, 潮涌核心) 由环境持续施加, 按顶层过滤不采集; 应用时清空现有效果后逐个放入.
 */
public final class PotionEffectsDataType extends CodecDataType<List<MobEffectInstance>> implements NativePlayerDataType<List<MobEffectInstance>> {
    public static final DataKey POTION_EFFECTS = DataKey.sparrow("potion_effects");


    public PotionEffectsDataType() {
        super(POTION_EFFECTS, StorageFormat.STRUCTURED, MobEffectInstance.CODEC.listOf(), MinecraftRegistryOps::sparrowNbt);
    }

    @Override
    @NotNull
    protected List<MobEffectInstance> captureValue(@NotNull Player player) {
        List<MobEffectInstance> effects = new ArrayList<>();
        for (MobEffectInstance instance : handle(player).getActiveEffects()) {
            if (instance.isAmbient()) continue;
            effects.add(copyOf(instance)); // todo 我觉得得分同步采集和异步采集2个方法, 不然同步采集也复制不好.
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
    @NotNull
    public NativeApplyResult applyNative(@NotNull UUID player, @NotNull net.minecraft.nbt.CompoundTag playerData, @NotNull List<MobEffectInstance> value) {
        net.minecraft.nbt.Tag effects = value.isEmpty()
                ? new net.minecraft.nbt.ListTag()
                : NBTOps.INSTANCE.convertTo(NbtOps.INSTANCE, this.encode(value));
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
