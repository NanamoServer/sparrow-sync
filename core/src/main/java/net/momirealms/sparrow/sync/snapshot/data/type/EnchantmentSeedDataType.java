package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
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
        // 原版 load 遇到零值会重新随机, 留到 Join 再写回零值
        if (value == 0) return NativeApplyResult.NOT_APPLIED;
        playerData.putInt("XpSeed", value);
        return NativeApplyResult.APPLIED_PLAYER_DATA;
    }
}
