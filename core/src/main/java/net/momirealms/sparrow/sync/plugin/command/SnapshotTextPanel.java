package net.momirealms.sparrow.sync.plugin.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.momirealms.sparrow.sync.player.PlayerIdentity;
import net.momirealms.sparrow.sync.session.operation.SnapshotDetailResult;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.exception.ExceptionArchives;
import net.momirealms.sparrow.sync.snapshot.page.SnapshotPage;
import net.momirealms.sparrow.sync.util.ChatTextUtils;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.List;
import java.util.UUID;

@ApiStatus.Internal
public final class SnapshotTextPanel {
    private static final DateTimeFormatter FULL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter SHORT_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final int SERVER_NAME_LENGTH = 10;

    private final CommandManager manager;

    public SnapshotTextPanel(@NotNull CommandManager manager) {
        this.manager = manager;
    }

    // 每次展示使用本次查询返回的实际页码, 越界输入夹回后按钮随之更新.
    public void snapshots(@NotNull CommandSender sender, @NotNull PlayerIdentity player, @NotNull SnapshotPage page) {
        Component title = this.tr("snapshot.title", this.identity(sender, player.name(), player.uuid()));
        Component panel = this.header(title, page.index(), page.count(), page.total());
        int size = page.content().size();
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
            Component source = this.source(sender, meta.server());
            if (sender instanceof Player) {
                // 将短 ID、原因与来源服的宽度差补在来源服之后, 同页按钮落在相同位置.
                source = source.append(ChatTextUtils.padding(rowWidth - this.rowWidth(meta)));
            }
            Component row = this.tr("snapshot.row", this.snapshotId(sender, meta), this.time(meta.timestamp(), sender instanceof Player),
                    Component.text(meta.cause().name()), source,
                    this.action(sender, pinAction, "snapshot_" + pinAction, id, false, true), actions);
            panel = panel.append(Component.newline()).append(row);
        }
        if (size == 0) {
            panel = panel.append(Component.newline()).append(this.tr("empty"));
        }
        panel = panel.append(Component.newline()).append(this.navigation(sender, "snapshot_list", player.name(), page.index(), page.count()));
        this.send(sender, panel);
    }

    public void exceptions(@NotNull CommandSender sender, @Nullable String player, @NotNull ExceptionArchives.Page page) {
        Component title = this.tr("exception.title", player == null ? this.tr("all_players") : Component.text(player));
        Component panel = this.header(title, page.index(), page.count(), page.total());
        int size = page.content().size();
        for (int i = 0; i < size; i++) {
            panel = panel.append(Component.newline()).append(this.exceptionRow(sender, page.content().get(i)));
        }
        if (size == 0) {
            panel = panel.append(Component.newline()).append(this.tr("empty"));
        }
        panel = panel.append(Component.newline()).append(this.navigation(sender, "exception_list", player == null ? "" : player, page.index(), page.count()));
        this.send(sender, panel);
    }

    // 控制台详情输出头与正文读取结果, 具体数据预览交给玩家菜单.
    public void archive(@NotNull CommandSender sender, @NotNull SnapshotDetailResult.Archive archive) {
        Component panel = this.tr("exception.detail", Component.text(archive.entry().path()))
                .append(Component.newline()).append(this.exceptionRow(sender, archive.entry()));
        Component result = switch (archive.result()) {
            case SnapshotDetailResult.Ready ready -> this.tr("exception.readable", this.snapshotHover(ready.snapshot().meta()));
            case SnapshotDetailResult.NotFound ignored -> this.tr("exception.body_missing");
            case SnapshotDetailResult.Invalid invalid -> this.tr("exception.invalid", Component.text(invalid.reason().name()), Component.text(invalid.detail()));
            case SnapshotDetailResult.Failed ignored -> this.tr("exception.failed");
        };
        this.send(sender, panel.append(Component.newline()).append(result));
    }

    private Component header(Component title, int index, int count, long total) {
        return this.tr("header", title, Component.text(index + 1), Component.text(count), Component.text(total));
    }

    // 玩家短标识的悬浮信息保留完整路径, 所有档案操作始终使用完整相对路径.
    private Component exceptionRow(CommandSender sender, ExceptionArchives.Entry entry) {
        SnapshotMeta meta = entry.informationAvailable() ? entry.header().meta() : null;
        Component time = meta == null ? this.tr("unknown_time") : this.time(meta.timestamp(), sender instanceof Player);
        Component player = meta == null ? this.tr("unknown_player") : this.identity(sender,
                entry.header().playerName() == null ? meta.player().toString() : entry.header().playerName(), meta.player());
        Component status = this.tr("exception.head_" + entry.headStatus().name().toLowerCase(Locale.ROOT));
        if (!entry.informationAvailable() && entry.headStatus() == ExceptionArchives.HeadStatus.AVAILABLE) {
            status = this.tr("exception.information_missing");
        }
        status = status.append(Component.space()).append(this.tr(entry.bodyPresent() ? "exception.body_unchecked" : "exception.body_missing"));
        String label = sender instanceof Player ? (meta == null ? entry.path().substring(entry.path().lastIndexOf('/') + 1) : meta.id().toString().substring(0, 8)) : entry.path();
        Component path = Component.text(label);
        if (sender instanceof Player) {
            Component hover = Component.text(entry.path());
            if (meta != null) {
                hover = hover.append(Component.newline()).append(this.snapshotHover(meta));
            }
            path = path.hoverEvent(hover).clickEvent(ClickEvent.copyToClipboard(entry.path()));
        }
        Component actions = this.action(sender, "view", "exception_view", entry.path(), false, !(sender instanceof Player))
                .append(Component.space()).append(this.action(sender, "delete", "exception_delete", entry.path(), true, true));
        return this.tr("exception.row", path, time, player, Component.text(entry.category()),
                meta == null ? this.tr("unknown_server") : this.source(sender, meta.server()), status, actions);
    }

    private int rowWidth(SnapshotMeta meta) {
        return ChatTextUtils.width(meta.id().toString().substring(0, 8)) + ChatTextUtils.width(meta.cause().name()) + ChatTextUtils.width(this.sourceName(meta.server()));
    }

    private Component source(CommandSender sender, String server) {
        if (!(sender instanceof Player)) return Component.text(server);
        return Component.text(this.sourceName(server)).hoverEvent(Component.text(server)).clickEvent(ClickEvent.copyToClipboard(server));
    }

    private String sourceName(String server) {
        if (server.codePointCount(0, server.length()) <= SERVER_NAME_LENGTH) return server;
        return server.substring(0, server.offsetByCodePoints(0, SERVER_NAME_LENGTH - 3)) + "...";
    }

    private Component navigation(CommandSender sender, String feature, String arguments, int index, int count) {
        String prefix = arguments.isEmpty() ? "" : arguments + " ";
        return this.tr("navigation",
                this.action(sender, "previous", feature, prefix + index, false, index > 0),
                Component.text(index + 1), Component.text(count),
                this.action(sender, "next", feature, prefix + (index + 2), false, index < count - 1),
                this.action(sender, "refresh", feature, prefix + (index + 1), false, true));
    }

    // 链接跟随已注册 Feature 的入口与权限, 删除通过输入框保留最后一次人工确认.
    private Component action(CommandSender sender, String label, String featureId, String arguments, boolean suggest, boolean available) {
        CommandFeature feature = this.manager.features().value(featureId);
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
        String permission = config == null ? null : config.getPermission();
        boolean permitted = permission == null || permission.isEmpty() || sender.hasPermission(permission);
        if (!available || usage == null || !permitted) {
            Component reason = this.tr(!permitted ? "no_permission" : label.equals("view") ? "gui_pending" : "unavailable");
            return sender instanceof Player ? this.tr("disabled", caption).hoverEvent(reason) : this.tr("console.disabled", caption, reason);
        }
        String command = usage + " " + arguments;
        if (!(sender instanceof Player)) return this.tr("console.action", caption, Component.text(command));
        Component hover = switch (label) {
            case "pin", "unpin" -> this.tr("hint." + label, Component.text(command));
            default -> suggest ? this.tr("confirm", Component.text(command)) : Component.text(command);
        };
        return this.tr("action." + label, caption)
                .hoverEvent(hover)
                .clickEvent(suggest ? ClickEvent.suggestCommand(command) : ClickEvent.runCommand(command));
    }

    private Component snapshotId(CommandSender sender, SnapshotMeta meta) {
        String id = meta.id().toString();
        if (!(sender instanceof Player)) return Component.text(id);
        return Component.text(id.substring(0, 8)).hoverEvent(this.snapshotHover(meta)).clickEvent(ClickEvent.copyToClipboard(id));
    }

    private Component snapshotHover(SnapshotMeta meta) {
        return this.tr("snapshot.hover", Component.text(meta.id().toString()), Component.text(meta.player().toString()),
                this.time(meta.timestamp(), false), Component.text(meta.cause().name()), Component.text(meta.server()),
                this.tr(meta.pinned() ? "pinned_text" : "unpinned"), Component.text(meta.mcDataVersion()));
    }

    private Component identity(CommandSender sender, String name, UUID id) {
        Component result = Component.text(name);
        return sender instanceof Player ? result.hoverEvent(Component.text(id.toString())).clickEvent(ClickEvent.copyToClipboard(id.toString()))
                : Component.text(name + " (" + id + ")");
    }

    private Component time(long timestamp, boolean compact) {
        return Component.text((compact ? SHORT_TIME : FULL_TIME).format(Instant.ofEpochMilli(timestamp)));
    }

    private Component tr(String key, Component... arguments) {
        // 翻译节点只承载模板参数, 后续行与按钮挂在外层, 随模板替换后仍保留.
        return Component.empty().append(Component.translatable("command.panel." + key).arguments(arguments));
    }

    private void send(CommandSender sender, Component panel) {
        this.manager.handleCommandFeedback(sender, Component.translatable().key("command.panel.message"), panel);
    }
}
