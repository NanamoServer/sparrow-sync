package net.momirealms.sparrow.sync.command.feature.debug;

import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.command.BukkitCommandFeature;
import net.momirealms.sparrow.sync.command.CommandManager;
import net.momirealms.sparrow.sync.data.SnapshotApplier;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.Command;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.Suggestion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public final class DebugApplyCommand extends BukkitCommandFeature {

    public DebugApplyCommand(CommandManager commandManager, SparrowSync plugin) {
        super(commandManager, plugin);
    }

    @Override
    public Command.Builder<? extends CommandSender> assembleCommand(org.incendo.cloud.CommandManager<CommandSender> manager, Command.Builder<CommandSender> builder) {
        return builder
                .senderType(Player.class)
                .required("file", StringParser.stringComponent(StringParser.StringMode.GREEDY).suggestionProvider((context, input) ->
                        CompletableFuture.completedFuture(SnapshotUtils.listRelativePaths(SnapshotUtils.directory(plugin())).stream().map(Suggestion::suggestion).toList()))
                )
                .handler(context -> {
                    Player player = context.sender();
                    String relative = context.get("file");
                    // 读文件与预解码转异步, 应用段再回玩家线程
                    plugin().scheduler().async().execute(() -> this.loadAndApply(player, relative));
                });
    }

    @Override
    public String getFeatureID() {
        return "debug_apply";
    }

    private void loadAndApply(Player player, String relative) {
        SnapshotApplier applier = plugin().snapshotApplier();
        if (applier == null) {
            SnapshotUtils.send(player, "[FAIL] data registry is not assembled yet", false);
            return;
        }
        Path file = SnapshotUtils.resolveInside(SnapshotUtils.directory(plugin()), relative);
        if (file == null) {
            SnapshotUtils.send(player, "[FAIL] path escapes the debug directory: " + relative, false);
            return;
        }
        if (!Files.isRegularFile(file)) {
            SnapshotUtils.send(player, "[FAIL] no such file: debug/" + relative, false);
            return;
        }
        Snapshot snapshot;
        try {
            snapshot = this.readSnapshot(file);
        } catch (Exception exception) {
            SnapshotUtils.send(player, "[FAIL] read: " + exception, false);
            plugin().logger().warn("Debug apply failed to read " + relative, exception);
            return;
        }
        // 预解码在异步线程完成, 关键数据解不开则不动玩家
        SnapshotApplier.PreparedSnapshot prepared = applier.prepare(snapshot);
        if (!(prepared instanceof SnapshotApplier.PreparedSnapshot.Ready ready)) {
            SnapshotUtils.send(player, "[FAIL] prepare: " + prepared, false);
            plugin().logger().warn("Debug apply failed to prepare debug/" + relative + " for " + player.getName() + ": " + prepared);
            return;
        }
        player.getScheduler().run(plugin().javaPlugin(), task -> {
            switch (applier.apply(player, ready)) {
                case SnapshotApplier.ApplyResult.Success success -> {
                    SnapshotUtils.send(player, "[PASS] applied " + success.applied().size() + " type(s), " + success.skipped().size() + " skipped, from debug/" + relative, true);
                    plugin().logger().info("Debug apply: " + success.applied().size() + " type(s) to " + player.getName() + " (" + player.getUniqueId() + ") from debug/" + relative);
                }
                case SnapshotApplier.ApplyResult.Failure failure -> {
                    SnapshotUtils.send(player, "[FAIL] apply " + failure.failedKey().asString() + ": " + failure.detail(), false);
                    plugin().logger().warn("Debug apply of " + failure.failedKey().asString() + " failed for " + player.getName() + ": " + failure.detail());
                }
            }
        }, null);
    }

    // 扩展名决定形态: JSON 走 SNBT 调试格式, 其余按二进制帧读
    private Snapshot readSnapshot(Path file) throws Exception {
        DecodedSnapshot decoded = file.getFileName().toString().endsWith(SnapshotUtils.JSON_SUFFIX)
                ? SnapshotUtils.jsonCodec().decode(Files.readString(file))
                : SnapshotUtils.binaryCodec().decode(Files.readAllBytes(file));
        if (decoded instanceof DecodedSnapshot.Valid valid) return valid.snapshot();
        DecodedSnapshot.Invalid invalid = (DecodedSnapshot.Invalid) decoded;
        throw new IOException(invalid.reason() + ": " + invalid.detail());
    }
}
