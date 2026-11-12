package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerPlayer;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.player.component.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.Set;

/** 玩家以死亡状态离开时保存死亡事件给出的经验结算, 目标服复活时按它结算. */
public final class DeathSettlementDataType extends CodecDataType<Optional<DeathSettlementDataType.Settlement>> implements NativePlayerDataType<Optional<DeathSettlementDataType.Settlement>> {
    public static final DataKey DEATH_SETTLEMENT = DataKey.sparrow("death_settlement"); // 空数据块表示离开时玩家还活着

    public DeathSettlementDataType() {
        super(DEATH_SETTLEMENT, Settlement.CODEC.optionalFieldOf("settlement").codec());
    }

    // keepLevel 结算读取同步过来的等级与总经验
    @Override
    @NotNull
    public Set<DataKey> dependencies() {
        return Set.of(ExperienceDataType.EXPERIENCE);
    }

    @Override
    public boolean supportsAsyncCapture() {
        return true;
    }

    @NotNull
    @Override
    protected Optional<Settlement> captureValue(@NotNull Player player, @NotNull CaptureMode mode) {
        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        if (!handle.isDeadOrDying()) return Optional.empty();
        return Optional.of(new Settlement(handle.newExp, handle.newLevel, handle.newTotalExp, handle.keepLevel));
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull Optional<Settlement> value) {
        if (value.isEmpty()) return;
        Settlement settlement = value.get();
        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        handle.newExp = settlement.newExp();
        handle.newLevel = settlement.newLevel();
        handle.newTotalExp = settlement.newTotalExp();
        handle.keepLevel = settlement.keepLevel();
    }

    @Override
    public boolean shouldApply(@NotNull PlayerSession session) {
        return PluginConfig.synchronization$nativeAsyncApply().playerData();
    }

    /** 写入 CraftPlayer 在原版加载时读取的 bukkit 标签. */
    @NotNull
    @Override
    public NativeApplyResult applyNative(@NotNull PlayerSession session, @NotNull CompoundTag playerData, @NotNull Optional<Settlement> value) {
        if (value.isEmpty()) return NativeApplyResult.APPLIED_PLAYER_DATA;
        Settlement settlement = value.get();
        CompoundTag bukkit = playerData.getCompound("bukkit");
        if (bukkit == null) {
            bukkit = new CompoundTag();
            playerData.put("bukkit", bukkit);
        }
        bukkit.putInt("newExp", settlement.newExp());
        bukkit.putInt("newLevel", settlement.newLevel());
        bukkit.putInt("newTotalExp", settlement.newTotalExp());
        bukkit.putBoolean("keepLevel", settlement.keepLevel());
        return NativeApplyResult.APPLIED_PLAYER_DATA;
    }

    public record Settlement(int newExp, int newLevel, int newTotalExp, boolean keepLevel) {
        public static final Codec<Settlement> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("new_exp").forGetter(Settlement::newExp),
                Codec.INT.fieldOf("new_level").forGetter(Settlement::newLevel),
                Codec.INT.fieldOf("new_total_exp").forGetter(Settlement::newTotalExp),
                Codec.BOOL.fieldOf("keep_level").forGetter(Settlement::keepLevel)
        ).apply(instance, Settlement::new));
    }
}