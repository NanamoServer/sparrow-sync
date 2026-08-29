package net.momirealms.sparrow.sync.command.feature.debug;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.momirealms.sparrow.sync.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.codec.JsonSnapshotCodec;
import net.momirealms.sparrow.sync.configuration.PluginConfig;
import net.momirealms.sparrow.sync.configuration.ServerConfig;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class SnapshotUtils {
    static final String BINARY_SUFFIX = ".snapshot";
    static final String JSON_SUFFIX = ".json";
    private static final Pattern UNSAFE_NAME_CHARS = Pattern.compile("[^A-Za-z0-9_-]");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS").withZone(ZoneId.systemDefault());

    static Path directory(SparrowSync plugin) {
        return plugin.dataFolderPath().resolve("debug");
    }

    // 与正式装配同一配置来源, dump 出来的就是运行期真正生效的编码形态
    static BinarySnapshotCodec binaryCodec() {
        return new BinarySnapshotCodec(PluginConfig.synchronization$compression());
    }

    static JsonSnapshotCodec jsonCodec() {
        return new JsonSnapshotCodec();
    }

    static SnapshotMeta metaOf(Player player) {
        return SnapshotMeta.builder()
                .player(player.getUniqueId())
                .timestamp(System.currentTimeMillis())
                .cause(SaveCause.COMMAND)
                .server(ServerConfig.serverId())
                .mcDataVersion(VersionHelper.WORLD_VERSION)
                .build();
    }

    static String fileName(Player player, String suffix) {
        return UNSAFE_NAME_CHARS.matcher(player.getName()).replaceAll("_") + "-" + TIME_FORMAT.format(Instant.now()) + suffix;
    }

    // 相对路径解析回 debug 目录内, 逃逸出去的一律拒绝
    @Nullable
    static Path resolveInside(Path directory, String relative) {
        Path resolved = directory.resolve(relative).normalize();
        return resolved.startsWith(directory.normalize()) ? resolved : null;
    }

    // apply 补全用: debug 目录下两种产物的相对路径
    static List<String> listRelativePaths(Path directory) {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> entries = Files.walk(directory, 3)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(BINARY_SUFFIX) || name.endsWith(JSON_SUFFIX);
                    })
                    .map(path -> directory.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    static void send(CommandSender sender, String line, boolean ok) {
        sender.sendMessage(Component.text(line, ok ? NamedTextColor.GREEN : NamedTextColor.RED));
    }
}
