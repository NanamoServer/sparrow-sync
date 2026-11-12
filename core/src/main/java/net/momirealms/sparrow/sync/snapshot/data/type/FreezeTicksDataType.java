package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.player.component.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class FreezeTicksDataType extends CodecDataType<Integer> implements NativePlayerDataType<Integer> {
    public static final DataKey KEY = DataKey.sparrow("freeze_ticks");

    public FreezeTicksDataType() {
        super(KEY, Codec.INT);
    }

    @Override
    public boolean shouldApply(@NotNull PlayerSession session) {
        return PluginConfig.synchronization$nativeAsyncApply().playerData();
    }

    @NotNull
    @Override
    public NativeApplyResult applyNative(@NotNull PlayerSession session, @NotNull CompoundTag playerData, @NotNull Integer value) {
        playerData.putInt("TicksFrozen", value);
        return NativeApplyResult.APPLIED_PLAYER_DATA;
    }

    @NotNull
    @Override
    protected Integer captureValue(@NotNull Player player, @NotNull CaptureMode mode) {
        return player.getFreezeTicks();
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull Integer value) {
        player.setFreezeTicks(value);
    }
}
