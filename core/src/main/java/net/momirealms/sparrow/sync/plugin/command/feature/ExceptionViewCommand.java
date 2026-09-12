package net.momirealms.sparrow.sync.plugin.command.feature;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.sync.gui.SnapshotDetailGui;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.command.CommandConfig;
import net.momirealms.sparrow.sync.plugin.command.CommandFeature;
import net.momirealms.sparrow.sync.plugin.command.CommandManager;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotDetailResult;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFiles;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.Command;
import org.incendo.cloud.parser.standard.StringParser;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

// 查看本服异常快照, 玩家进入快照菜单, 控制台接收文字详情.
public final class ExceptionViewCommand extends AbstractSnapshotCommand {
    private static final DateTimeFormatter FULL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX").withZone(ZoneId.systemDefault());

    public ExceptionViewCommand(@NotNull CommandManager manager, @NotNull SparrowSync plugin) {
        super(manager, plugin);
    }

    @Override
    @NotNull
    public Command.Builder<? extends CommandSender> assembleCommand(@NotNull org.incendo.cloud.CommandManager<CommandSender> manager, @NotNull Command.Builder<CommandSender> builder) {
        return builder.required("file", StringParser.greedyStringParser()).handler(context -> {
            if (context.sender() instanceof Player viewer) {
                this.finish(context, CompletableFuture.supplyAsync(() -> new SnapshotDetailGui(this.plugin(), viewer, "", null, context.get("file"), null).build(), this.plugin().scheduler().async())
                        .thenCompose(Window::open), result -> {});
                return;
            }
            // 控制台输出头文件中的身份和类型清单, 正文保留未检查状态.
            this.finish(context, this.plugin().snapshotService().details().loadException(context.get("file")), archive -> {
                if (archive.result() instanceof SnapshotDetailResult.Failed(Throwable failure)) {
                    this.plugin().logger().warn(TranslationManager.console("log.command.snapshot_failed", this.getFeatureID()), failure);
                }
                this.renderArchive(context.sender(), archive);
            });
        });
    }

    @Override
    @NotNull
    public String getFeatureID() {
        return "exception_view";
    }

    /**
     * 向控制台发送异常快照的头文件概览与正文存在状态, 所有标识均保留完整文本.
     *
     * @param sender 通过命令入口进入文字详情的非玩家发送者
     * @param archive 异常快照条目及概览状态
     */
    private void renderArchive(@NotNull CommandSender sender, @NotNull SnapshotDetailResult.Archive archive) {
        SnapshotFiles.ExceptionEntry entry = archive.entry();
        SnapshotMeta meta = entry.informationAvailable() ? entry.header().meta() : null;
        Component time = meta == null ? this.tr("unknown_time") : Component.text(FULL_TIME.format(Instant.ofEpochMilli(meta.timestamp())));
        Component player = this.tr("unknown_player");
        if (meta != null) {
            String name = entry.header().playerName() == null ? meta.player().toString() : entry.header().playerName();
            player = Component.text(name + " (" + meta.player() + ")");
        }
        // 分别显示异常快照头文件与异常快照数据的状态, 头文件损坏时仍提供路径和删除入口.
        Component status = this.tr("exception.head_" + entry.headStatus().name().toLowerCase(Locale.ROOT));
        if (!entry.informationAvailable() && entry.headStatus() == SnapshotFiles.HeadStatus.AVAILABLE) {
            status = this.tr("exception.information_missing");
        }
        status = status.append(Component.space()).append(this.tr(entry.bodyPresent() ? "exception.body_unchecked" : "exception.body_missing"));
        Component actions = this.action(sender, "view", "exception_view", entry.path())
                .append(Component.space()).append(this.action(sender, "delete", "exception_delete", entry.path()));
        Component panel = this.tr("exception.detail", Component.text(entry.path())).append(Component.newline())
                .append(this.tr("exception.row", Component.text(entry.path()), time, player, Component.text(entry.category()),
                        meta == null ? this.tr("unknown_server") : Component.text(meta.server()), status, actions));
        // 类型体量来自头文件摘要, 正文状态与头文件是否可读分别显示.
        if (entry.summary() != null) {
            panel = panel.append(Component.newline()).append(this.tr("exception.type_count", Component.text(entry.summary().size())));
            for (var type : entry.summary().entrySet()) {
                panel = panel.append(Component.newline()).append(this.tr(type.getValue() < 0 ? "exception.type_unknown" : "exception.type",
                        Component.text(type.getKey().asString()), Component.text(type.getValue())));
            }
        } else {
            panel = panel.append(Component.newline()).append(this.tr("exception.summary_unavailable"));
        }
        Component result = switch (archive.result()) {
            case SnapshotDetailResult.Overview ignored -> this.tr(entry.bodyPresent() ? "exception.body_unchecked" : "exception.source_only");
            case SnapshotDetailResult.Ready ready -> {
                SnapshotMeta contents = ready.snapshot().meta();
                yield this.tr("exception.readable", this.tr("snapshot.hover", Component.text(contents.id().toString()), Component.text(contents.player().toString()),
                        Component.text(FULL_TIME.format(Instant.ofEpochMilli(contents.timestamp()))), Component.text(contents.cause().name()), Component.text(contents.server()),
                        this.tr(contents.pinned() ? "pinned_text" : "unpinned"), Component.text(contents.mcDataVersion())));
            }
            case SnapshotDetailResult.NotFound ignored -> this.tr("exception.body_missing");
            case SnapshotDetailResult.Invalid invalid -> this.tr("exception.invalid", Component.text(invalid.reason().name()), Component.text(invalid.detail()));
            case SnapshotDetailResult.Failed ignored -> this.tr("exception.failed");
        };
        this.handleFeedback(sender, Component.translatable().key("command.panel.message"), panel.append(Component.newline()).append(result));
    }

    /**
     * 根据命令当前配置和发送者权限构建操作文本.
     *
     * @param sender 操作文本的接收者
     * @param label 操作名称, 用于选择翻译模板
     * @param featureId 操作对应的已注册命令功能标识
     * @param arguments 附加在命令入口后的完整参数
     * @return 包含完整命令的控制台文本, 或操作不可用的说明
     */
    @NotNull
    private Component action(@NotNull CommandSender sender, @NotNull String label, @NotNull String featureId, @NotNull String arguments) {
        // 入口与权限来自当前注册的命令配置, 自定义别名和权限会同步体现在链接中.
        CommandFeature feature = this.commandManager.features().value(featureId);
        CommandConfig config = feature == null ? null : feature.commandConfig();
        Component caption = this.tr("label." + label);
        String usage = null;
        if (config != null && config.isEnable()) {
            List<String> usages = config.getUsages();
            int size = usages.size();
            for (int i = 0; i < size; i++) {
                String candidate = usages.get(i);
                if (candidate.startsWith("/")) {
                    usage = candidate.trim();
                    break;
                }
            }
        }
        // 每次渲染按发送者的当前权限生成链接, 命令执行时仍由命令框架检查权限.
        String permission = config == null ? null : config.getPermission();
        boolean permitted = permission == null || permission.isEmpty() || sender.hasPermission(permission);
        if (usage == null || !permitted) {
            Component reason = this.tr(!permitted ? "no_permission" : label.equals("view") ? "gui_pending" : "unavailable");
            return this.tr("console.disabled", caption, reason);
        }
        String command = usage + " " + arguments;
        return this.tr("console.action", caption, Component.text(command));
    }

    /**
     * 创建面板翻译片段, 供后续拼接记录和操作文本.
     *
     * @param key 相对于 command.panel 的翻译键
     * @param arguments 模板参数, 动态文本应使用普通文本组件传入
     * @return 包含翻译节点的外层组件
     */
    @NotNull
    private Component tr(@NotNull String key, @NotNull Component... arguments) {
        // 翻译节点只承载模板参数, 后续行与按钮挂在外层, 随模板替换后仍保留.
        return Component.empty().append(Component.translatable("command.panel." + key).arguments(arguments));
    }
}
