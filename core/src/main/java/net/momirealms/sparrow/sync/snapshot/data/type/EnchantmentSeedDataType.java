package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class EnchantmentSeedDataType extends CodecDataType<Integer> implements NativePlayerDataType<Integer> {
    public static final DataKey ENCHANTMENT_SEED = DataKey.sparrow("enchantment_seed");

    public EnchantmentSeedDataType() {
        super(ENCHANTMENT_SEED, StorageFormat.STRUCTURED, Codec.INT);
    }

    @Override
    @NotNull
    protected Integer captureValue(@NotNull Player player) {
        return player.getEnchantmentSeed();
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull Integer value) {
        player.setEnchantmentSeed(value);
    }

    @Override
    public boolean shouldApply() {
        return PluginConfig.synchronization$nativeAsyncApply().playerData();
    }

    @Override
    @NotNull
    public NativeApplyResult applyNative(@NotNull UUID player, @NotNull CompoundTag playerData, @NotNull Integer value) {
        // 原版把零值当作“缺失”并在 load 时重新随机, 该边界只能留给 join setter 保真.
        if (value == 0) return NativeApplyResult.NOT_APPLIED;
        playerData.putInt("XpSeed", value);
        return NativeApplyResult.APPLIED_PLAYER_DATA;
    }
}
