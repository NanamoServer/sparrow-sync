package net.momirealms.sparrow.sync.plugin.command;

import net.kyori.adventure.util.Index;
import net.momirealms.sparrow.sync.plugin.command.feature.StatusCommand;
import net.momirealms.sparrow.sync.plugin.command.feature.ReloadCommand;
import net.momirealms.sparrow.sync.plugin.command.feature.TestCommand;
import net.momirealms.sparrow.sync.plugin.command.feature.SnapshotCaptureCommand;
import net.momirealms.sparrow.sync.plugin.command.feature.SnapshotRestoreCommand;
import net.momirealms.sparrow.sync.plugin.command.feature.SnapshotPinCommand;
import net.momirealms.sparrow.sync.plugin.command.feature.SnapshotUnpinCommand;
import net.momirealms.sparrow.sync.plugin.command.feature.SnapshotDeleteCommand;
import net.momirealms.sparrow.sync.plugin.command.feature.SnapshotExportCommand;
import net.momirealms.sparrow.sync.plugin.command.feature.SnapshotImportCommand;
import net.momirealms.sparrow.sync.plugin.command.feature.DumpAllCommand;
import net.momirealms.sparrow.sync.plugin.command.feature.ImportAllCommand;
import net.momirealms.sparrow.sync.plugin.command.feature.MigrateCommand;
import net.momirealms.sparrow.sync.plugin.command.feature.ExceptionDeleteCommand;
import net.momirealms.sparrow.sync.plugin.command.feature.ExceptionListCommand;
import net.momirealms.sparrow.sync.plugin.command.feature.ExceptionViewCommand;
import net.momirealms.sparrow.sync.plugin.command.feature.SnapshotListCommand;
import net.momirealms.sparrow.sync.plugin.command.feature.GuiCommand;
import net.momirealms.sparrow.sync.plugin.command.feature.SnapshotViewCommand;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.bukkit.CloudBukkitCapabilities;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.LegacyPaperCommandManager;
import org.incendo.cloud.setting.ManagerSetting;

import java.util.List;
import java.util.Locale;

public final class BukkitCommandManager extends AbstractCommandManager {
    public final SparrowSync plugin;
    private final Index<String, CommandFeature> index;

    public BukkitCommandManager(SparrowSync plugin) {
        // 构建 LegacyPaperCommandManager 并交给父类
        super(plugin, new LegacyPaperCommandManager<>(
                plugin.javaPlugin(),
                ExecutionCoordinator.simpleCoordinator(),
                SenderMapper.identity()
        ));
        // 初始化命令索引
        this.plugin = plugin;
        this.index = Index.create(CommandFeature::getFeatureID, List.of(
                new StatusCommand(this, plugin),
                new ReloadCommand(this, plugin),
                new TestCommand(this, plugin),
                new SnapshotCaptureCommand(this, plugin),
                new SnapshotRestoreCommand(this, plugin),
                new SnapshotPinCommand(this, plugin),
                new SnapshotUnpinCommand(this, plugin),
                new SnapshotDeleteCommand(this, plugin),
                new SnapshotExportCommand(this, plugin),
                new SnapshotImportCommand(this, plugin),
                new DumpAllCommand(this, plugin),
                new ImportAllCommand(this, plugin),
                new MigrateCommand(this, plugin),
                new ExceptionDeleteCommand(this, plugin),
                new SnapshotListCommand(this, plugin),
                new GuiCommand(this, plugin),
                new SnapshotViewCommand(this, plugin),
                new ExceptionListCommand(this, plugin),
                new ExceptionViewCommand(this, plugin)
        ));
        final LegacyPaperCommandManager<CommandSender> manager = (LegacyPaperCommandManager<CommandSender>) getCommandManager();
        // 开启 ALLOW_UNSAFE_REGISTRATION, 以允许在部分运行环境中完成命令注册.
        manager.settings().set(ManagerSetting.ALLOW_UNSAFE_REGISTRATION, true);
        // 能力注册 brigadier 或异步补全.
        if (manager.hasCapability(CloudBukkitCapabilities.NATIVE_BRIGADIER)) {
            manager.registerBrigadier();
            manager.brigadierManager().setNativeNumberSuggestions(true);
        } else if (manager.hasCapability(CloudBukkitCapabilities.ASYNCHRONOUS_COMPLETION)) {
            manager.registerAsynchronousCompletions();
        }
    }

    @Override
    protected Locale getLocale(CommandSender sender) {
        if (sender instanceof Player player) {
            return player.locale();
        }
        return null;
    }

    @Override
    public Index<String, CommandFeature> features() {
        return this.index;
    }
}
