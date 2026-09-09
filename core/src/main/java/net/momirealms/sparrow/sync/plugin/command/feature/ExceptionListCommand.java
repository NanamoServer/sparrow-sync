package net.momirealms.sparrow.sync.plugin.command.feature;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.command.CommandConfig;
import net.momirealms.sparrow.sync.plugin.command.CommandFeature;
import net.momirealms.sparrow.sync.plugin.command.CommandManager;
import net.momirealms.sparrow.sync.plugin.command.parser.NetworkPlayerParser;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.exception.ExceptionArchives;
import net.momirealms.sparrow.sync.snapshot.page.SnapshotPagination;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.Command;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

// 查询本服异常档案索引并输出分页文字列表, 支持按玩家筛选.
public final class ExceptionListCommand extends AbstractSnapshotCommand {
    private static final DateTimeFormatter FULL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter SHORT_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final int SERVER_NAME_LENGTH = 10; // 来源服显示上限, 包含末尾省略点, 单位为 Unicode 码点

    public ExceptionListCommand(@NotNull CommandManager manager, @NotNull SparrowSync plugin) {
        super(manager, plugin);
    }

    @Override
    @NotNull
    public Command.Builder<? extends CommandSender> assembleCommand(@NotNull org.incendo.cloud.CommandManager<CommandSender> manager, @NotNull Command.Builder<CommandSender> builder) {
        return builder.optional("player", NetworkPlayerParser.playerParser(this.plugin().playerDirectory()))
                .optional("page", IntegerParser.integerParser(1))
                .handler(this::list);
    }

    @Override
    @NotNull
    public String getFeatureID() {
        return "exception_list";
    }

    // 解析异常档案列表的玩家筛选和页码, 然后发起对应查询.
    private void list(@NotNull CommandContext<CommandSender> context) {
        // 单个数字参数表示页码, 数字玩家名可通过显式追加页码查询.
        String input = context.<String>optional("player").orElse(null);
        int page = context.<Integer>optional("page").orElse(1);
        if (input == null) {
            this.show(context, null, null, page);
        } else if (!context.contains("page") && input.matches("[+-]?[0-9]+")) {
            try {
                page = Integer.parseInt(input);
                if (page < 1) throw new NumberFormatException();
            } catch (NumberFormatException failure) {
                this.handleFeedback(context, Component.translatable().key("command.panel.invalid_page"));
                return;
            }
            this.show(context, null, null, page);
        } else {
            int selectedPage = page;
            this.withPlayer(context, player -> this.show(context, player.uuid(), player.name(), selectedPage));
        }
    }

    // 异步读取本服异常档案的当前页并发送列表.
    private void show(@NotNull CommandContext<CommandSender> context, @Nullable UUID player, @Nullable String name, int page) {
        this.finish(context, this.plugin().snapshotService().exceptions().load(player, null, page - 1, SnapshotPagination.TEXT_PAGE_SIZE),
                result -> this.renderPage(context.sender(), name, result));
    }

    /**
     * 发送当前查询页的文字列表, 同时提供与发送者权限匹配的操作入口.
     *
     * @param sender 接收列表的玩家或控制台
     * @param player 筛选玩家的显示名, 空值表示全部玩家
     * @param page 包含实际页码、总数和当前页记录的查询结果
     */
    private void renderPage(@NotNull CommandSender sender, @Nullable String player, @NotNull ExceptionArchives.Page page) {
        Component title = this.tr("exception.title", player == null ? this.tr("all_players") : Component.text(player));
        Component panel = this.tr("header", title, Component.text(page.index() + 1), Component.text(page.count()), Component.text(page.total()));
        int size = page.content().size();
        for (int i = 0; i < size; i++) {
            panel = panel.append(Component.newline()).append(this.exceptionRow(sender, page.content().get(i)));
        }
        if (size == 0) {
            panel = panel.append(Component.newline()).append(this.tr("empty"));
        }
        // 全玩家列表只携带页码, 指定玩家时保留筛选条件; 使用查询返回的实际页码生成链接.
        String prefix = player == null ? "" : player + " ";
        panel = panel.append(Component.newline()).append(this.tr("navigation",
                this.action(sender, "previous", "exception_list", prefix + page.index(), false, page.index() > 0),
                Component.text(page.index() + 1), Component.text(page.count()),
                this.action(sender, "next", "exception_list", prefix + (page.index() + 2), false, page.index() < page.count() - 1),
                this.action(sender, "refresh", "exception_list", prefix + (page.index() + 1), false, true)));
        this.handleFeedback(sender, Component.translatable().key("command.panel.message"), panel);
    }

    /**
     * 构建异常档案记录, 同时展示档案头状态、正文是否存在和可用操作.
     *
     * @param sender 接收记录的玩家或控制台
     * @param entry 本服异常档案索引记录
     * @return 包含标识、元数据、状态和操作的记录行
     */
    @NotNull
    private Component exceptionRow(@NotNull CommandSender sender, @NotNull ExceptionArchives.Entry entry) {
        SnapshotMeta meta = entry.informationAvailable() ? entry.header().meta() : null;
        Component time = meta == null ? this.tr("unknown_time") : Component.text((sender instanceof Player ? SHORT_TIME : FULL_TIME).format(Instant.ofEpochMilli(meta.timestamp())));
        Component player = this.tr("unknown_player");
        Component source = this.tr("unknown_server");
        // 可读的档案头提供玩家身份与来源服, 缺失的信息仍以占位文本展示.
        if (meta != null) {
            String name = entry.header().playerName() == null ? meta.player().toString() : entry.header().playerName();
            player = Component.text(name + " (" + meta.player() + ")");
            source = Component.text(meta.server());
            if (sender instanceof Player) {
                player = Component.text(name).hoverEvent(Component.text(meta.player().toString())).clickEvent(ClickEvent.copyToClipboard(meta.player().toString()));
                String server = meta.server();
                String shortName = server.codePointCount(0, server.length()) <= SERVER_NAME_LENGTH
                        ? server : server.substring(0, server.offsetByCodePoints(0, SERVER_NAME_LENGTH - 3)) + "...";
                source = Component.text(shortName).hoverEvent(Component.text(server)).clickEvent(ClickEvent.copyToClipboard(server));
            }
        }
        // 列表只读取档案头, 正文存在时标记为待检查, 具体读取结果由查看命令提供.
        Component status = this.tr("exception.head_" + entry.headStatus().name().toLowerCase(Locale.ROOT));
        if (!entry.informationAvailable() && entry.headStatus() == ExceptionArchives.HeadStatus.AVAILABLE) {
            status = this.tr("exception.information_missing");
        }
        status = status.append(Component.space()).append(this.tr(entry.bodyPresent() ? "exception.body_unchecked" : "exception.body_missing"));
        // 玩家点击标识可复制完整相对路径, 所有查看与删除入口也使用该路径.
        String label = sender instanceof Player ? (meta == null ? entry.path().substring(entry.path().lastIndexOf('/') + 1) : meta.id().toString().substring(0, 8)) : entry.path();
        Component path = Component.text(label);
        if (sender instanceof Player) {
            Component hover = Component.text(entry.path());
            if (meta != null) {
                hover = hover.append(Component.newline()).append(this.tr("snapshot.hover", Component.text(meta.id().toString()), Component.text(meta.player().toString()),
                        Component.text(FULL_TIME.format(Instant.ofEpochMilli(meta.timestamp()))), Component.text(meta.cause().name()), Component.text(meta.server()),
                        this.tr(meta.pinned() ? "pinned_text" : "unpinned"), Component.text(meta.mcDataVersion())));
            }
            path = path.hoverEvent(hover).clickEvent(ClickEvent.copyToClipboard(entry.path()));
        }
        Component actions = this.action(sender, "view", "exception_view", entry.path(), false, true)
                .append(Component.space()).append(this.action(sender, "delete", "exception_delete", entry.path(), true, true));
        return this.tr("exception.row", path, time, player, Component.text(entry.category()),
                source, status, actions);
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
        Component hover = suggest ? this.tr("confirm", Component.text(command)) : Component.text(command);
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
