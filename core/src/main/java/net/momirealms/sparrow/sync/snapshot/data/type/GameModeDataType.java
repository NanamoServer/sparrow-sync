package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.nbt.CompoundTag;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;


public final class GameModeDataType extends CodecDataType<GameMode> implements NativePlayerDataType<GameMode> {
    public static final DataKey GAME_MODE = DataKey.sparrow("game_mode");
    private static final Codec<GameMode> CODEC = Codec.STRING.comapFlatMap(GameModeDataType::parseGameMode, GameMode::name);

    public GameModeDataType() {
        super(GAME_MODE, StorageFormat.STRUCTURED, CODEC);
    }

    @Override
    @NotNull
    protected GameMode captureValue(@NotNull Player player) {
        return player.getGameMode();
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull GameMode value) {
        if (player.getGameMode() != value) {
            player.setGameMode(value);
        }
    }

    @Override
    public boolean shouldApply(@NotNull PlayerSession session) {
        return PluginConfig.synchronization$nativeAsyncApply().playerData();
    }

    @Override
    @NotNull
    public NativeApplyResult applyNative(@NotNull PlayerSession session, @NotNull CompoundTag playerData, @NotNull GameMode value) {
        int id = switch (value) {
            case SURVIVAL -> 0;
            case CREATIVE -> 1;
            case ADVENTURE -> 2;
            case SPECTATOR -> 3;
        };
        playerData.putInt("playerGameType", id);
        return NativeApplyResult.APPLIED_PLAYER_DATA;
    }

    private static DataResult<GameMode> parseGameMode(String name) {
        for (GameMode mode : GameMode.values()) {
            if (mode.name().equals(name)) return DataResult.success(mode);
        }
        return DataResult.error(() -> "unknown game mode: " + name);
    }
}
