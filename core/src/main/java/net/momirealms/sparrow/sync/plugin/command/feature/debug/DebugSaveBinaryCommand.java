package net.momirealms.sparrow.sync.plugin.command.feature.debug;

import net.momirealms.sparrow.sync.plugin.command.BukkitCommandFeature;
import net.momirealms.sparrow.sync.plugin.command.CommandManager;
import net.momirealms.sparrow.sync.snapshot.data.SnapshotApplier;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.Command;

import java.nio.file.Files;
import java.nio.file.Path;

public final class DebugSaveBinaryCommand extends BukkitCommandFeature {

    public DebugSaveBinaryCommand(CommandManager commandManager, SparrowSync plugin) {
        super(commandManager, plugin);
    }

    @Override
    public Command.Builder<? extends CommandSender> assembleCommand(org.incendo.cloud.CommandManager<CommandSender> manager, Command.Builder<CommandSender> builder) {
        return builder
                .senderType(Player.class)
                .handler(context -> {
                    Player player = context.sender();
                    // 采集必须在玩家的拥有线程上执行
                    player.getScheduler().run(plugin().javaPlugin(), task -> this.dump(player), null);
                });
    }

    @Override
    public String getFeatureID() {
        return "debug_save_binary";
    }

    private void dump(Player player) {
        SnapshotApplier applier = plugin().snapshotApplier();
        if (applier == null) {
            SnapshotUtils.send(player, "[FAIL] data registry is not assembled yet", false);
            return;
        }
        if (!(applier.capture(player) instanceof SnapshotApplier.CaptureResult.Ready ready)) {
            SnapshotUtils.send(player, "[FAIL] critical data could not be captured", false);
            return;
        }
        if (!(applier.encode(ready) instanceof SnapshotApplier.EncodeResult.Ready encoded)) {
            SnapshotUtils.send(player, "[FAIL] critical data could not be encoded", false);
            return;
        }
        Snapshot snapshot = new Snapshot(SnapshotUtils.metaOf(player), encoded.data());
        plugin().scheduler().async().execute(() -> {
            try {
                byte[] bytes = SnapshotUtils.binaryCodec().encode(snapshot);
                Path file = SnapshotUtils.directory(plugin()).resolve(SnapshotUtils.fileName(player, SnapshotUtils.BINARY_SUFFIX));
                Files.createDirectories(file.getParent());
                Files.write(file, bytes);
                SnapshotUtils.send(player, "[PASS] " + snapshot.data().size() + " type(s) -> debug/" + file.getFileName() + " (" + bytes.length + " bytes)", true);
                plugin().logger().info("Debug binary dump: " + snapshot.data().size() + " type(s) of " + player.getName() + " (" + player.getUniqueId() + ") -> debug/" + file.getFileName() + " (" + bytes.length + " bytes)");
            } catch (Exception exception) {
                SnapshotUtils.send(player, "[FAIL] dump: " + exception, false);
                plugin().logger().warn("Debug binary dump failed for " + player.getName(), exception);
            }
        });
    }
}
