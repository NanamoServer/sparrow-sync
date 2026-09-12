package net.momirealms.sparrow.sync.compatibility.economy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class VaultDataType extends CodecDataType<VaultDataType.Money> {
    public static final DataKey VAULT = DataKey.sparrow("vault");
    private static final double EQUAL_EPSILON = 1e-9; // 余额已一致的判定阈值, 余额一致时不产生交易

    private final VaultEconomyService economy;

    public VaultDataType(@NotNull VaultEconomyService economy) {
        super(VAULT, Money.CODEC);
        this.economy = economy;
    }

    @Override
    @NotNull
    protected Money captureValue(@NotNull Player player, @NotNull CaptureMode mode) {
        return new Money(this.economy.provider().getBalance(player));
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull Money value) {
        Economy provider = this.economy.provider();
        double target = value.amount();
        if (!Double.isFinite(target)) throw new IllegalStateException("snapshot balance is not finite: " + target);
        // 经济插件按差额记账, 余额一致时不产生任何交易
        double delta = target - provider.getBalance(player);
        if (Math.abs(delta) < EQUAL_EPSILON) return;
        EconomyResponse response = delta > 0
                ? provider.depositPlayer(player, delta)
                : provider.withdrawPlayer(player, -delta);
        if (!response.transactionSuccess()) {
            throw new IllegalStateException("economy rejected the balance adjustment: " + response.errorMessage);
        }
    }

    public record Money(double amount) {
        public static final Codec<Money> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.DOUBLE.fieldOf("amount").forGetter(Money::amount)
        ).apply(instance, Money::new));
    }
}
