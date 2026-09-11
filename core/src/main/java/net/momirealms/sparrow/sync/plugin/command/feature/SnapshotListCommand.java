package net.momirealms.sparrow.sync.plugin.command.feature;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.momirealms.sparrow.sync.player.PlayerIdentity;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.command.CommandConfig;
import net.momirealms.sparrow.sync.plugin.command.CommandFeature;
import net.momirealms.sparrow.sync.plugin.command.CommandManager;
import net.momirealms.sparrow.sync.plugin.command.parser.NetworkPlayerParser;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.gui.page.SnapshotPage;
import net.momirealms.sparrow.sync.gui.page.SnapshotPagination;
import net.momirealms.sparrow.sync.storage.SnapshotQuery;
import net.momirealms.sparrow.sync.util.ChatTextUtils;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.Command;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

// 查询指定玩家的数据库快照并输出分页文字列表.
public final class SnapshotListCommand extends AbstractSnapshotCommand {
    private static final DateTimeFormatter FULL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter SHORT_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final int SERVER_NAME_LENGTH = 10; // 来源服显示上限, 包含末尾省略点, 单位为 Unicode 码点

    public SnapshotListCommand(@NotNull CommandManager manager, @NotNull SparrowSync plugin) {
        super(manager, plugin);
    }

    @Override
    @NotNull
    public Command.Builder<? extends CommandSender> assembleCommand(@NotNull org.incendo.cloud.CommandManager<CommandSender> manager, @NotNull Command.Builder<CommandSender> builder) {
        return builder.required("player", NetworkPlayerParser.playerParser(this.plugin().playerDirectory()))
                .optional("page", IntegerParser.integerParser(1))
                .handler(context -> this.withPlayer(context, player -> this.finish(context,
                        new SnapshotPagination(this.plugin().storageProvider()).load(SnapshotQuery.of(player.uuid()), context.<Integer>optional("page").orElse(1) - 1, SnapshotPagination.TEXT_PAGE_SIZE),
                        page -> this.renderPage(context.sender(), player, page))));
    }

    @Override
    @NotNull
    public String getFeatureID() {
        return "snapshot_list";
    }

    // 发送当前查询页的文字列表, 同时提供与发送者权限匹配的操作入口.
    private void renderPage(@NotNull CommandSender sender, @NotNull PlayerIdentity player, @NotNull SnapshotPage page) {
        Component identity = sender instanceof Player
                ? Component.text(player.name()).hoverEvent(Component.text(player.uuid().toString())).clickEvent(ClickEvent.copyToClipboard(player.uuid().toString()))
                : Component.text(player.name() + " (" + player.uuid() + ")");
        Component title = this.tr("snapshot.title", identity);
        Component panel = this.tr("header", title, Component.text(page.index() + 1), Component.text(page.count()), Component.text(page.total()));
        int size = page.content().size();
        // 根据本页短标识、保存原因和来源服的宽度计算补白, 让聊天框中的操作按钮纵向对齐.
        int rowWidth = 0;
        if (sender instanceof Player) {
            int[] widths = new int[size];
            for (int i = 0; i < size; i++) {
                widths[i] = this.rowWidth(page.content().get(i));
            }
            rowWidth = ChatTextUtils.alignedWidth(widths);
        }
        for (int i = 0; i < size; i++) {
            SnapshotMeta meta = page.content().get(i);
            String id = meta.id().toString();
            Component actions = Component.empty();
            if (sender instanceof Player) {
                actions = this.action(sender, "view", "snapshot_view", player.name() + " " + id, false, true);
            }
            actions = actions.append(Component.space()).append(this.action(sender, "delete", "snapshot_delete", id, true, true))
                    .append(Component.space()).append(this.action(sender, "json", "snapshot_export", "json " + id, false, true));
            String pinAction = meta.pinned() ? "unpin" : "pin";
            Component source = Component.text(meta.server());
            Component snapshotId = Component.text(id);
            if (sender instanceof Player) {
                // 显示短标识, 悬浮与复制保留完整信息; 命令参数始终使用完整 UUID.
                snapshotId = Component.text(id.substring(0, 8)).hoverEvent(this.tr("snapshot.hover", Component.text(meta.id().toString()), Component.text(meta.player().toString()),
                        Component.text(FULL_TIME.format(Instant.ofEpochMilli(meta.timestamp()))), Component.text(meta.cause().name()), Component.text(meta.server()),
                        this.tr(meta.pinned() ? "pinned_text" : "unpinned"), Component.text(meta.mcDataVersion()))).clickEvent(ClickEvent.copyToClipboard(id));
                source = Component.text(this.sourceName(meta.server())).hoverEvent(Component.text(meta.server())).clickEvent(ClickEvent.copyToClipboard(meta.server()));
                source = source.append(ChatTextUtils.padding(rowWidth - this.rowWidth(meta)));
            }
            Component row = this.tr("snapshot.row", snapshotId, Component.text((sender instanceof Player ? SHORT_TIME : FULL_TIME).format(Instant.ofEpochMilli(meta.timestamp()))),
                    Component.text(meta.cause().name()), source,
                    this.action(sender, pinAction, "snapshot_" + pinAction, id, false, true), actions);
            panel = panel.append(Component.newline()).append(row);
        }
        if (size == 0) {
            panel = panel.append(Component.newline()).append(this.tr("empty"));
        }
        // 页码使用查询结果中的实际值, 越界输入被夹回末页后链接仍指向相邻页.
        String prefix = player.name() + " ";
        panel = panel.append(Component.newline()).append(this.tr("navigation",
                this.action(sender, "previous", "snapshot_list", prefix + page.index(), false, page.index() > 0),
                Component.text(page.index() + 1), Component.text(page.count()),
                this.action(sender, "next", "snapshot_list", prefix + (page.index() + 2), false, page.index() < page.count() - 1),
                this.action(sender, "refresh", "snapshot_list", prefix + (page.index() + 1), false, true)));
        this.handleFeedback(sender, Component.translatable().key("command.panel.message"), panel);
    }

    // 计算影响操作按钮对齐的可变文本宽度, 单位为聊天字体像素.
    private int rowWidth(@NotNull SnapshotMeta meta) {
        return ChatTextUtils.width(meta.id().toString().substring(0, 8)) + ChatTextUtils.width(meta.cause().name()) + ChatTextUtils.width(this.sourceName(meta.server()));
    }

    // 将玩家可见的来源服名称限制为十个 Unicode 码点, 超出部分用三个点表示.
    @NotNull
    private String sourceName(@NotNull String server) {
        if (server.codePointCount(0, server.length()) <= SERVER_NAME_LENGTH) {
            return server;
        }
        return server.substring(0, server.offsetByCodePoints(0, SERVER_NAME_LENGTH - 3)) + "...";
    }

    /**
     * 根据命令当前配置和发送者权限构建操作文本.
     *
     * @param sender 操作文本的接收者
     * @param label 操作名称, 用于选择翻译模板
     * @param featureId 操作对应的已注册命令功能标识
     * @param arguments 附加在命令入口后的完整参数
     * @param suggest 是否仅填入聊天输入框, 等待玩家确认发送
     * @param available 当前记录或页码是否允许执行此操作
     * @return 可点击的玩家操作或包含完整命令的控制台文本; 不可用时显示禁用状态
     */
    @NotNull
    private Component action(@NotNull CommandSender sender, @NotNull String label, @NotNull String featureId, @NotNull String arguments, boolean suggest, boolean available) {
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
        if (!available || usage == null || !permitted) {
            Component reason = this.tr(!permitted ? "no_permission" : switch (label) {
                case "previous" -> !available ? "first_page" : "unavailable";
                case "next" -> !available ? "last_page" : "unavailable";
                case "view" -> "gui_pending";
                default -> "unavailable";
            });
            return sender instanceof Player ? this.tr("disabled", caption).hoverEvent(reason) : this.tr("console.disabled", caption, reason);
        }
        String command = usage + " " + arguments;
        if (!(sender instanceof Player)) {
            return this.tr("console.action", caption, Component.text(command));
        }
        // 删除链接只填入聊天输入框, 玩家确认发送后才执行删除.
        Component hover = switch (label) {
            case "pin", "unpin" -> this.tr("hint." + label, Component.text(command));
            default -> suggest ? this.tr("confirm", Component.text(command)) : Component.text(command);
        };
        return this.tr("action." + label, caption)
                .hoverEvent(hover)
                .clickEvent(suggest ? ClickEvent.suggestCommand(command) : ClickEvent.runCommand(command));
    }

    // 创建面板翻译片段, 供后续拼接记录和操作文本.
    @NotNull
    private Component tr(@NotNull String key, @NotNull Component... arguments) {
        // 翻译节点只承载模板参数, 后续行与按钮挂在外层, 随模板替换后仍保留.
        return Component.empty().append(Component.translatable("command.panel." + key).arguments(arguments));
    }
}
