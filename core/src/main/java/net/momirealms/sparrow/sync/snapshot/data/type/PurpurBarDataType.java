package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.proxy.purpur.BossBarTaskProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.server.level.ServerPlayerProxy;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class PurpurBarDataType extends CodecDataType<PurpurBarDataType.PurpurBars> implements NativePlayerDataType<PurpurBarDataType.PurpurBars> {
    public static final DataKey PURPUR_BAR = DataKey.sparrow("purpur_bar");

    public PurpurBarDataType() {
        super(PURPUR_BAR, PurpurBars.CODEC);
    }

    @Override
    public boolean supportsAsyncCapture() {
        return true;
    }

    @Override
    @NotNull
    protected PurpurBars captureValue(@NotNull Player player, @NotNull CaptureMode mode) {
        Object handle = ((CraftPlayer) player).getHandle();
        ServerPlayerProxy proxy = ServerPlayerProxy.INSTANCE;
        return new PurpurBars(proxy.tpsBar(handle), proxy.compassBar(handle), proxy.ramBar(handle));
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull PurpurBars value) {
        Object handle = ((CraftPlayer) player).getHandle();
        ServerPlayerProxy proxy = ServerPlayerProxy.INSTANCE;
        proxy.tpsBar(handle, value.tpsBar());
        proxy.compassBar(handle, value.compassBar());
        proxy.ramBar(handle, value.ramBar());
        BossBarTaskProxy.INSTANCE.removeFromAll(player);
        BossBarTaskProxy.INSTANCE.addToAll(handle);
    }

    @Override
    public boolean shouldApply(@NotNull PlayerSession session) {
        return PluginConfig.synchronization$nativeAsyncApply().playerData();
    }

    @Override
    @NotNull
    public NativeApplyResult applyNative(@NotNull PlayerSession session, @NotNull CompoundTag playerData, @NotNull PurpurBars value) {
        playerData.putBoolean("Purpur.TPSBar", value.tpsBar());
        playerData.putBoolean("Purpur.CompassBar", value.compassBar());
        playerData.putBoolean("Purpur.RamBar", value.ramBar());
        return NativeApplyResult.APPLIED_PLAYER_DATA;
    }

    public record PurpurBars(boolean tpsBar, boolean compassBar, boolean ramBar) {
        public static final Codec<PurpurBars> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.fieldOf("tpsBar").forGetter(PurpurBars::tpsBar),
                Codec.BOOL.fieldOf("compassBar").forGetter(PurpurBars::compassBar),
                Codec.BOOL.fieldOf("ramBar").forGetter(PurpurBars::ramBar)
        ).apply(instance, PurpurBars::new));
    }
}
