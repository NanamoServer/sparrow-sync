package net.momirealms.sparrow.sync.gui;

import io.papermc.paper.adventure.PaperAdventure;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemLore;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.player.PlayerIdentity;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFiles;
import net.momirealms.sparrow.sync.snapshot.page.SnapshotPagination;
import net.momirealms.sparrow.sync.storage.SnapshotQuery;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.SlotSequence;
import net.momirealms.sparrow.ui.state.KeyedSignal;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.Signals;
import net.momirealms.sparrow.ui.window.NormalWindow;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@ApiStatus.Internal
public final class SnapshotListGui {
    private static final DateTimeFormatter SHORT_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter FULL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX");
    private static final int PAGE_SIZE = 36;

    private final SparrowSync plugin;
    private final Player viewer;
    private final Pane pane = Pane.empty(9, 6);
    private Window window;
    private final String playerName; // 命令指定的目标玩家
    private final MutableSignal<PageRequest> request = Signal.of(new PageRequest(false, 0)); // 来源与页码共同确定查询缓存
    private KeyedSignal<PageRequest, LoadedPage> pages; // 异步查询的页缓存
    private Signal<LoadedPage> current; // 当前页的完整查询结果

    public SnapshotListGui(SparrowSync plugin, Player viewer, String playerName) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.playerName = playerName;
    }

    public Window build() {
        this.pages = KeyedSignal.async(new LoadedPage(null, 0, 1, List.of(), List.of(), "loading"), this.plugin.scheduler().async(), this::loadPage);
        this.current = Signals.switching(this.pages, this.request);
        this.pane.fill(Item.simple(this.icon(Material.GRAY_STAINED_GLASS_PANE, "blank")));
        this.pane.projectElements(SlotSequence.range(this.pane.size(), 9, 45), this.current.map(this::pageElements), this.plugin.scheduler().async());
        this.pane.setItem(1, this.buildPlayerInfo());
        this.pane.setItem(2, Item.builder()
                .dependsOn(this.request)
                .setItemProvider(context -> this.icon(Material.CHEST, this.text("button.snapshots"), !this.request.get().exceptions(), List.of()))
                .addClickHandler(click -> this.request.set(new PageRequest(false, 0)))
                .build());
        this.pane.setItem(3, Item.builder()
                .dependsOn(this.request)
                .setItemProvider(context -> this.icon(Material.REPEATER, this.text("button.exceptions"), this.request.get().exceptions(), List.of()))
                .addClickHandler(click -> this.request.set(new PageRequest(true, 0)))
                .build());
        this.pane.setItem(8, Item.simple(this.icon(Material.CLOCK, "info.order")));
        this.pane.setItem(45, Item.builder()
                .setItemProviderConstant(this.icon(Material.ARROW, "button.previous"))
                .addClickHandler(click -> this.turn(-1))
                .build());
        this.pane.setItem(48, this.buildPageInfo());
        this.pane.setItem(49, Item.builder()
                .setItemProviderConstant(this.icon(Material.COMPASS, "button.refresh"))
                .addClickHandler(click -> this.pages.dirty(this.request.get()))
                .build());
        this.pane.setItem(53, Item.builder()
                .setItemProviderConstant(this.icon(Material.ARROW, "button.next"))
                .addClickHandler(click -> this.turn(1))
                .build());
        this.window = NormalWindow.builder()
                .setUpperPane(this.pane)
                .setTitle(this.text("title.list", this.playerName))
                .addOpenHandler(opened -> this.pane.setItem(0, this.buildNavigationButton()))
                .build(this.viewer);
        return this.window;
    }

    /**
     * 把一页查询结果转换为记录区元素, 加载和错误提示占据内容区中央.
     *
     * @param page 当前来源的分页查询结果
     * @return 按记录区槽位顺序排列的元素
     */
    private List<Element> pageElements(LoadedPage page) {
        if (page.status() != null || (page.entries().isEmpty() && page.archives().isEmpty())) {
            List<Element> elements = new ArrayList<>();
            for (int i = 0; i < 13; i++) {
                elements.add(Element.empty());
            }
            elements.add(Element.item(Item.simple(this.icon(Material.BARRIER, page.status() == null ? "empty" : page.status()))));
            return elements;
        }
        if (!page.archives().isEmpty()) {
            return page.archives().stream().map(entry -> {
                SnapshotMeta meta = entry.header().meta();
                List<Component> lore = new ArrayList<>(this.metadata(meta));
                lore.add(this.text("label.category", entry.category()));
                lore.add(this.text("label.archive", entry.path()));
                if (!entry.bodyPresent()) {
                    lore.add(this.text("not_found"));
                }
                return (Element) Element.item(Item.builder().setItemProviderConstant(this.icon(Material.BOOK,
                        this.text("snapshot_entry", this.text("unpin_mark"), time(meta.timestamp(), false), entry.category()), false, lore))
                        .addClickHandler(click -> {
                            if (click.clickType() == ClickType.LEFT) {
                                this.view(null, entry.path());
                            }
                        }).build());
            }).toList();
        }
        return page.entries().stream().map(entry -> (Element) Element.item(this.buildSnapshotButton(entry))).toList();
    }

    /**
     * 创建返回或关闭按钮.
     * 窗口打开后 Session 已建立, hasBack 决定箭矢或橡木门外观.
     *
     * @return 按当前 Session 层级执行导航的按钮
     */
    private Item buildNavigationButton() {
        boolean back = this.window.session().hasBack();
        return Item.builder().setItemProviderConstant(this.icon(back ? Material.ARROW : Material.OAK_DOOR, back ? "button.back" : "button.close")).addClickHandler(click -> this.window.backOrClose()).build();
    }

    /** 返回随查询结果更新的玩家身份图标. */
    private Item buildPlayerInfo() {
        return Item.builder().dependsOn(this.current).setItemProvider(context -> {
            PlayerIdentity player = this.current.get().player();
            return this.icon(Material.PLAYER_HEAD, this.text("info.player", player == null ? this.playerName : player.name()), false,
                    player == null ? List.of() : List.of(this.text("label.player_id", player.uuid())));
        }).build();
    }

    /**
     * 把内部从零开始的页码转换成玩家看到的页数.
     *
     * @return 显示当前页与总页数的说明 Item
     */
    private Item buildPageInfo() {
        return Item.builder().dependsOn(this.current).setItemProvider(context -> {
            LoadedPage page = this.current.get();
            return this.icon(Material.PAPER, this.text("info.page", page.index() + 1, page.count()), false, List.of());
        }).build();
    }

    /**
     * 使用书本展示快照记录, 固定状态以星标与附魔光效表示.
     * 按钮持有完整记录身份, 左键进入详情.
     *
     * @param meta 数据库快照头
     * @return 展示元信息并可进入详情的书本按钮
     */
    private Item buildSnapshotButton(SnapshotMeta meta) {
        Component name = this.text("snapshot_entry", this.text(meta.pinned() ? "pin_mark" : "unpin_mark"), time(meta.timestamp(), false),
                this.text("cause." + meta.cause().name().toLowerCase(Locale.ROOT)));
        return Item.builder().setItemProviderConstant(this.icon(Material.BOOK, name, meta.pinned(), this.metadata(meta))).addClickHandler(click -> {
            if (click.clickType() == ClickType.LEFT) {
                this.view(meta.id(), null);
            }
        }).build();
    }

    // 渲染当前查看者语言中的 gui 消息.
    Component text(String key, Object... values) {
        List<Component> arguments = Arrays.stream(values).map(value -> value instanceof Component component ? component : Component.text(String.valueOf(value))).toList();
        return TranslationManager.instance().render(Component.translatable("gui." + key).arguments(arguments), this.viewer.locale())
                .decoration(TextDecoration.ITALIC, false);
    }

    // 使用消息键创建无附魔光效的菜单图标.
    ItemStack icon(Material material, String key, Component... lore) {
        return this.icon(material, this.text(key), false, Arrays.asList(lore));
    }

    // 通过 NMS 组件构造完整菜单图标.
    ItemStack icon(Material material, Component name, boolean glint, List<Component> lore) {
        // 直接修改 NMS 组件, 最后以 CraftItemStack 镜像交给 SparrowUI.
        var item = new net.minecraft.world.item.ItemStack(CraftMagicNumbers.getItem(material));
        item.set(DataComponents.CUSTOM_NAME, PaperAdventure.asVanilla(name));
        item.set(DataComponents.LORE, new ItemLore(lore.stream().map(PaperAdventure::asVanilla).toList()));
        item.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, glint);
        return CraftItemStack.asCraftMirror(item);
    }

    // 把快照头格式化为 ID、完整时间、原因和来源服.
    private List<Component> metadata(SnapshotMeta meta) {
        return List.of(this.text("label.snapshot_id", meta.id()), this.text("label.time", time(meta.timestamp(), true)),
                this.text("label.cause", this.text("cause." + meta.cause().name().toLowerCase(Locale.ROOT))),
                this.text("label.server", meta.server()));
    }

    static String time(long timestamp, boolean full) {
        return (full ? FULL_TIME : SHORT_TIME).format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()));
    }

    private void turn(int step) {
        LoadedPage page = this.current.get();
        this.request.set(new PageRequest(this.request.get().exceptions(), Math.clamp(page.index() + step, 0, page.count() - 1)));
    }

    /**
     * 在异步 Signal 的装载线程读取玩家身份和一页记录, 返回完整结果供绑定消费.
     *
     * @param request 查询来源与从零开始的页码
     * @return 包含实际页码、玩家身份或错误状态的结果
     */
    private LoadedPage loadPage(PageRequest request) {
        try {
            var found = this.plugin.playerDirectory().resolve(this.playerName).join();
            if (found.isEmpty()) {
                return new LoadedPage(null, 0, 1, List.of(), List.of(), "player_missing");
            }
            PlayerIdentity player = found.get();
            if (request.exceptions()) {
                var page = this.plugin.snapshotService().files().listExceptions(player.uuid(), null, request.index(), PAGE_SIZE);
                return new LoadedPage(player, page.index(), page.count(), List.of(), page.content(), null);
            }
            var page = new SnapshotPagination(this.plugin.storageProvider()).load(SnapshotQuery.of(player.uuid()), request.index(), PAGE_SIZE).join();
            return new LoadedPage(player, page.index(), page.count(), page.content(), List.of(), null);
        } catch (IOException failure) {
            this.failed(failure);
            return new LoadedPage(null, request.index(), 1, List.of(), List.of(), "failed");
        } catch (CompletionException failure) {
            this.failed(failure.getCause());
            return new LoadedPage(null, request.index(), 1, List.of(), List.of(), "failed");
        }
    }

    // 异步构建详情并交给当前 Session 导航, Window 负责执行打开流程.
    private void view(@Nullable UUID snapshotId, @Nullable String archivePath) {
        this.window.navigate(CompletableFuture.supplyAsync(() -> new SnapshotDetailGui(this.plugin, this.viewer,
                this.playerName, snapshotId, archivePath, this.pages::clear).build(), this.plugin.scheduler().async()))
                .whenComplete((opened, failure) -> {
                    if (failure != null) {
                        this.failed(failure);
                    }
                });
    }

    // 记录本次菜单操作异常并向查看者发送失败反馈.
    private void failed(Throwable failure) {
        this.plugin.logger().warn("Snapshot GUI operation failed", failure);
        this.viewer.sendMessage(this.text("feedback", this.text("failed")));
    }

    /**
     * 一次异步分页查询的结果.
     *
     * @param player 查询到的目标玩家, 加载或失败时可为空
     * @param index 查询服务校正后的从零开始页码
     * @param count 查询结果的总页数
     * @param entries 当前页的记录头
     * @param archives 当前页的本服异常档案
     * @param status 加载或失败提示, 正常页为空
     */
    private record LoadedPage(@Nullable PlayerIdentity player, int index, int count, List<SnapshotMeta> entries, List<SnapshotFiles.ExceptionEntry> archives, @Nullable String status) {
    }

    private record PageRequest(boolean exceptions, int index) {
    }
}
