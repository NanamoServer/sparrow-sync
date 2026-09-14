package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.effect.MobEffectInstance;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.codec.ops.MinecraftRegistryOps;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class PotionEffectsDataType implements NativePlayerDataType<List<MobEffectInstance>> {
    public static final DataKey POTION_EFFECTS = DataKey.sparrow("potion_effects");
    private static final Codec<List<MobEffectInstance>> CODEC = MobEffectInstance.CODEC.listOf();
    private static final String EFFECTS_KEY = "active_effects";

    @Override
    @NotNull
    public DataKey key() {
        return POTION_EFFECTS;
    }

    @Override
    @NotNull
    public List<MobEffectInstance> capture(@NotNull Player player, @NotNull CaptureMode mode) {
        List<MobEffectInstance> effects = new ArrayList<>();
        for (MobEffectInstance instance : handle(player).getActiveEffects()) {
            if (instance.isAmbient()) continue;
            effects.add(mode == CaptureMode.OFFLINE ? instance : copyOf(instance));
        }
        return effects;
    }

    @Override
    @NotNull
    public Tag encode(@NotNull List<MobEffectInstance> value) {
        CompoundTag root = NBT.createCompound();
        root.putInt("DataVersion", VersionHelper.WORLD_VERSION);
        Tag effectTag = CODEC.encodeStart(MinecraftRegistryOps.sparrowNbt(), value).getOrThrow(message -> new IllegalStateException("failed to encode " + POTION_EFFECTS + ": " + message));
        root.put(EFFECTS_KEY, effectTag);
        return root;
    }

    @Override
    @NotNull
    public List<MobEffectInstance> decode(@NotNull Tag data) throws IOException {
        // 旧快照列表没有版本信息, 按当前效果格式读取
        Tag effects = data;
        if (data instanceof CompoundTag root) {
            int dataVersion = root.getInt("DataVersion");
            int current = VersionHelper.WORLD_VERSION;
            if (dataVersion > current) {
                throw new IOException("potion effects data version " + dataVersion + " is newer than this server (" + current + ")");
            }
            if (dataVersion > 0 && dataVersion < current) {
                // 效果的版本升级规则属于 PLAYER, 包含字段改名和隐藏效果链
                root = (CompoundTag) DataFixers.getDataFixer().update(References.PLAYER, new Dynamic<>(NBTOps.INSTANCE, root), dataVersion, current).getValue();
            }
            effects = root.get(EFFECTS_KEY);
            if (effects == null) {
                throw new IOException("potion effects data is missing " + EFFECTS_KEY);
            }
        }
        return CODEC.parse(MinecraftRegistryOps.sparrowNbt(), effects)
                .getOrThrow(message -> new IOException("failed to decode " + POTION_EFFECTS + ": " + message));
    }

    @Override
    public void apply(@NotNull Player player, @NotNull List<MobEffectInstance> value) {
        ServerPlayer handle = handle(player);
        // 先清空旧效果再写入, 保留隐藏效果链
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
                : CODEC.encodeStart(MinecraftRegistryOps.sparrowNbt(), value).getOrThrow(message -> new IllegalStateException("failed to encode " + POTION_EFFECTS + ": " + message));;
        playerData.put(EFFECTS_KEY, effects);
        return NativeApplyResult.APPLIED_PLAYER_DATA;
    }

    private static ServerPlayer handle(Player player) {
        return ((CraftPlayer) player).getHandle();
    }

    // 递归复制隐藏效果, 玩家身上的剩余时长会在采集后继续变化
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
