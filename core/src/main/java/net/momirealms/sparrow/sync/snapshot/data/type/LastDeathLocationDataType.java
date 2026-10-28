package net.momirealms.sparrow.sync.snapshot.data.type;

import net.minecraft.core.GlobalPos;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/** 通过原版登录加载恢复最后死亡地点, 应用需要开启 native 玩家数据提前加载. */
public final class LastDeathLocationDataType extends CodecDataType<Optional<GlobalPos>> implements NativePlayerDataType<Optional<GlobalPos>> {
    public static final DataKey LAST_DEATH_LOCATION = DataKey.sparrow("last_death_location"); // 空数据块表示明确清除死亡地点

    public LastDeathLocationDataType() {
        super(LAST_DEATH_LOCATION, GlobalPos.CODEC.optionalFieldOf("location").codec());
    }

    @Override
    public boolean supportsAsyncCapture() {
        return true;
    }

    @NotNull
    @Override
    protected Optional<GlobalPos> captureValue(@NotNull Player player, @NotNull CaptureMode mode) {
        return ((CraftPlayer) player).getHandle().getLastDeathLocation();
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull Optional<GlobalPos> value) {
        // Join 和在线恢复保持玩家原值, 空操作由现有流水线计为已完成.
    }

    @Override
    public boolean shouldApply(@NotNull PlayerSession session) {
        return PluginConfig.synchronization$nativeAsyncApply().playerData();
    }

    /** 在登录前写入死亡地点, 空值会清除目标服的原有记录. */
    @NotNull
    @Override
    public NativeApplyResult applyNative(@NotNull PlayerSession session, @NotNull CompoundTag playerData, @NotNull Optional<GlobalPos> value) {
        if (value.isPresent()) {
            playerData.put("LastDeathLocation", GlobalPos.CODEC.encodeStart(NBTOps.INSTANCE, value.get()).getOrThrow());
        } else {
            playerData.remove("LastDeathLocation");
        }
        return NativeApplyResult.APPLIED_PLAYER_DATA;
    }
}