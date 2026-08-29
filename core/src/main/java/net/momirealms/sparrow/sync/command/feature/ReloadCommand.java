package net.momirealms.sparrow.sync.command.feature;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.sync.command.BukkitCommandFeature;
import net.momirealms.sparrow.sync.command.CommandManager;
import net.momirealms.sparrow.sync.command.FlagKeys;
import net.momirealms.sparrow.sync.locale.MessageConstants;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.Command;

public final class ReloadCommand extends BukkitCommandFeature {

    /**
     * 创建 reload 命令功能实例.
     *
     * @param commandManager 命令管理器
     * @param plugin 插件实例
     */
    public ReloadCommand(CommandManager commandManager, SparrowSync plugin) {
        super(commandManager, plugin);
    }

    /**
     * 组装 reload 命令结构.
     *
     * @param manager Cloud 命令管理器
     * @param builder 基础命令构建器
     * @return 组装完成的命令构建器
     */
    @Override
    public Command.Builder<? extends CommandSender> assembleCommand(org.incendo.cloud.CommandManager<CommandSender> manager, Command.Builder<CommandSender> builder) {
        return builder
                .flag(FlagKeys.SILENT_FLAG)
                .handler(context -> {
                    if (plugin().isReloading()) {
                        handleFeedback(context, MessageConstants.COMMAND_RELOAD_TOO_FAST);
                        return;
                    }
                    plugin().reloadPlugin(plugin().scheduler().async(), r -> plugin().scheduler().sync().run(r)).thenAcceptAsync(reloadResult -> {
                        if (!reloadResult.success()) {
                            handleFeedback(context, MessageConstants.COMMAND_RELOAD_CONFIG_FAILURE);
                            return;
                        }
                        handleFeedback(context, MessageConstants.COMMAND_RELOAD_CONFIG_SUCCESS,
                                Component.text(reloadResult.asyncTime() + reloadResult.syncTime()),
                                Component.text(reloadResult.asyncTime()),
                                Component.text(reloadResult.syncTime())
                        );
                        if (reloadResult.issues() != 0 && context.sender() instanceof Player) {
                            handleFeedback(context, MessageConstants.COMMAND_RELOAD_CONFIG_ISSUES, Component.text(reloadResult.issues()));
                        }
                    }, plugin().scheduler().async());
                });
    }

    /**
     * 返回内置命令配置使用的 Feature 标识.
     *
     * @return 功能标识, 固定为 "reload"
     */
    @Override
    public String getFeatureID() {
        return "reload";
    }
}
