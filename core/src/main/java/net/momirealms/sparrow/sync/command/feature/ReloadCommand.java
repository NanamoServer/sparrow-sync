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
import org.incendo.cloud.parser.standard.EnumParser;

import java.util.Optional;

public final class ReloadCommand extends BukkitCommandFeature {
    /**
     * 用于标记 reload all 流程中是否需要触发资源包相关逻辑的全局开关.
     * 该字段会在开始 reload all 时置为 true, 并在异步回调结束时重置为 false.
     *
     * @implNote 该字段为全局可变状态, 若存在并发触发 reload all 的可能, 需要在上层逻辑中进行互斥控制.
     */
    public static boolean RELOAD_PACK_FLAG = false;

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
     * 关键步骤: 1. 注册 silent flag. 2. 注册可选参数 content, 值为 ReloadArgument 枚举. 3. 安装 handler 执行重载逻辑.
     *
     * @param manager Cloud 命令管理器
     * @param builder 基础命令构建器
     * @return 组装完成的命令构建器
     * @throws RuntimeException 当命令构建或 handler 内部逻辑抛出异常时可能抛出
     * @implNote 当前实现仅在参数为 ALL 时执行 reloadPlugin, CONFIG 分支暂未实现具体逻辑.
     */
    @Override
    public Command.Builder<? extends CommandSender> assembleCommand(org.incendo.cloud.CommandManager<CommandSender> manager, Command.Builder<CommandSender> builder) {
        return builder
                .flag(FlagKeys.SILENT_FLAG)
                .optional("content", EnumParser.enumParser(ReloadArgument.class))
                .handler(context -> {
                    if (plugin().isReloading()) {
                        handleFeedback(context, MessageConstants.COMMAND_RELOAD_TOO_FAST);
                        return;
                    }
                    Optional<ReloadArgument> optional = context.optional("content");
                    ReloadArgument argument = ReloadArgument.CONFIG;
                    if (optional.isPresent()) {
                        argument = optional.get();
                    }
                    if (argument == ReloadArgument.ALL) {
                        RELOAD_PACK_FLAG = true;
                        plugin().reloadPlugin(plugin().scheduler().async(), r -> plugin().scheduler().sync().run(r)).thenAcceptAsync(reloadResult -> {
                            if (reloadResult.success()) {
                                handleFeedback(context, MessageConstants.COMMAND_RELOAD_CONFIG_SUCCESS,
                                        Component.text(reloadResult.asyncTime() + reloadResult.syncTime()),
                                        Component.text(reloadResult.asyncTime()),
                                        Component.text(reloadResult.syncTime())
                                );
                                if (reloadResult.issues() != 0 && context.sender() instanceof Player) {
                                    handleFeedback(context, MessageConstants.COMMAND_RELOAD_CONFIG_ISSUES, Component.text(reloadResult.issues()));
                                }
                                RELOAD_PACK_FLAG = false;
                            } else {
                                handleFeedback(context, MessageConstants.COMMAND_RELOAD_CONFIG_FAILURE);
                                RELOAD_PACK_FLAG = false;
                            }
                        }, plugin().scheduler().async());
                    }
                });
    }

    /**
     * 返回该功能在 commands.yml 中对应的配置节点标识.
     *
     * @return 功能标识, 固定为 "reload"
     */
    @Override
    public String getFeatureID() {
        return "reload";
    }

    public enum ReloadArgument {
        CONFIG,
        ALL
    }
}
