package net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage;

import net.minecraft.server.MinecraftServer;
import net.momirealms.sparrow.sync.proxy.minecraft.server.MinecraftServerProxy;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

@ApiStatus.Internal
public final class PlayerJsonStorage {
    private PlayerJsonStorage() {}

    /**
     * 把登录使用的 JSON 原子安装到原版玩家目录.
     *
     * @return 当前文件系统是否完成了原子替换
     * @throws IOException 当目录、临时文件或写入操作失败时
     */
    public static boolean materialize(@NotNull UUID player, @NotNull PlayerJsonFile file, byte @NotNull [] json) throws IOException {
        MinecraftServer server = (MinecraftServer) MinecraftServerProxy.INSTANCE.getServer();
        Path directory = server.getWorldPath(file.directory());
        return materialize(directory.resolve(player + ".json"), json);
    }

    static boolean materialize(@NotNull Path target, byte @NotNull [] json) throws IOException {
        Path directory = target.getParent();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, target.getFileName().toString() + "-", ".tmp");
        try {
            Files.write(temporary, json, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                return false;
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return true;
    }
}
