package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;


public final class EnchantmentSeedDataType extends CodecDataType<Integer> implements NativePlayerDataType<Integer> {
    public static final DataKey ENCHANTMENT_SEED = DataKey.sparrow("enchantment_seed");

    public EnchantmentSeedDataType() {
        super(ENCHANTMENT_SEED, Codec.INT);
    }

    @Override
    public boolean supportsAsyncCapture() {
        return true;
    }

    @Override
    @NotNull
    protected Integer captureValue(@NotNull Player player, @NotNull CaptureMode mode) {
        return ((CraftPlayer) player).getHandle().getEnchantmentSeed();
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull Integer value) {
        player.setEnchantmentSeed(value);
    }

    @Override
    public boolean shouldApply(@NotNull PlayerSession session) {
        return PluginConfig.synchronization$nativeAsyncApply().playerData();
    }

    @Override
    @NotNull
    public NativeApplyResult applyNative(@NotNull PlayerSession session, @NotNull CompoundTag playerData, @NotNull Integer value) {
        // 原版把零值当作“缺失”并在 load 时重新随机, 该边界只能留给 join setter 保真.
        if (value == 0) return NativeApplyResult.NOT_APPLIED;
        playerData.putInt("XpSeed", value);
        return NativeApplyResult.APPLIED_PLAYER_DATA;
    }
}
