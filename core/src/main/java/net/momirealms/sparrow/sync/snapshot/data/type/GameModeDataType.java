package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class GameModeDataType extends CodecDataType<GameMode> {
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

    private static DataResult<GameMode> parseGameMode(String name) {
        for (GameMode mode : GameMode.values()) {
            if (mode.name().equals(name)) return DataResult.success(mode);
        }
        return DataResult.error(() -> "unknown game mode: " + name);
    }
}
