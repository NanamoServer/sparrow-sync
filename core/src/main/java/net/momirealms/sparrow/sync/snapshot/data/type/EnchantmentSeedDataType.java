package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class EnchantmentSeedDataType extends CodecDataType<Integer> {
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
}
