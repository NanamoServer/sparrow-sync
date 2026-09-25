package net.momirealms.sparrow.sync.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemLore;
import net.momirealms.sparrow.sync.compatibility.economy.VaultDataType;
import net.momirealms.sparrow.sync.proxy.craftbukkit.inventory.CraftItemStackProxy;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.util.PlayerUtils;
import net.momirealms.sparrow.sync.player.PlayerIdentity;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.snapshot.data.type.EnchantmentSeedDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.ExperienceDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.GameModeDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.HealthDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.HungerDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.InventoryDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.EnderChestDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.LocationDataType;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFiles;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotDeleteResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotDetailResult;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotExportResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotPinResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotRestoreResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotUnpinResult;
import net.momirealms.sparrow.sync.util.ItemUtils;
import net.momirealms.sparrow.sync.util.MinecraftComponents;
import net.momirealms.sparrow.ui.inventory.VirtualInventory;
import net.momirealms.sparrow.ui.inventory.event.PlayerUpdateReason;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.SlotSequence;
import net.momirealms.sparrow.ui.pane.page.Tab;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.Signals;
import net.momirealms.sparrow.ui.window.NormalWindow;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

@ApiStatus.Internal
public final class SnapshotDetailGui {
    private static final DateTimeFormatter FULL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX");
    private static final String EDIT = "sparrow_sync.ui.edit";

    private final SparrowSync plugin;
    private final Player viewer;
    private final Pane pane = Pane.empty(9, 6);
    private Window window;

    private final String playerName;
    private final @Nullable UUID snapshotId;
    private final @Nullable String archivePath;   // 本服异常快照相对路径, 数据库来源为 null
    private final @Nullable Runnable refreshParent; // 管理完成后的上级刷新动作, 根窗口为 null
    private final Pane inventoryPane = Pane.empty(9, 5);
    private final Pane enderPane = Pane.empty(9, 5);
    private int enderPage;
    private int archivePage; // 异常类型清单的当前页, 每页显示 36 个类型
    private final Tab<Boolean> tabs = Tab.of(Map.of(false, this.inventoryPane, true, this.enderPane), false); // false 为背包, true 为末影箱
    private final Pane statusPane = Pane.empty(9, 5); // 加载与失败状态的内容区
    private final MutableSignal<Boolean> showingContents = Signal.of(false); // 是否展示已加载的容器
    private SnapshotDetailResult.Ready ready;     // 完成解码的原快照与各类预览状态
    private SnapshotContents contents;            // 本次读取的原始物品, 用于容器初始化和完整打包
    private final MutableSignal<SnapshotMeta> meta = Signal.of(null); // 固定按钮与元信息依赖此状态
    private PlayerIdentity player;
    private SnapshotFiles.ExceptionEntry archive;      // 异常快照头文件信息及路径, 异常快照数据损坏时仍可展示
    private VirtualInventory inventory;
    private VirtualInventory enderChest;

    public SnapshotDetailGui(SparrowSync plugin, Player viewer, String playerName, @Nullable UUID snapshotId, @Nullable String archivePath, @Nullable Runnable refreshParent) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.playerName = playerName;
        this.snapshotId = snapshotId;
        this.archivePath = archivePath;
        this.refreshParent = refreshParent;
    }

    public Window build() {
        this.pane.fill(Item.simple(this.icon(Material.GRAY_STAINED_GLASS_PANE, "blank")));
        // 内容区绑定所选 Pane, Tab 切换由投影更新显示路径, 两个容器保持原有实例.
        Signal<Pane> body = Signals.combine(this.showingContents, this.tabs.pane(), (showing, selected) -> showing ? selected : this.statusPane);
        this.pane.projectElements(SlotSequence.range(this.pane.size(), 9, 51), body.map(selected -> {
            List<Element> elements = new ArrayList<>();
            for (int slot = 0; slot < 42; slot++) {
                elements.add(Element.pane(selected, slot));
            }
            return elements;
        }), this.plugin.scheduler().async());
        this.window = NormalWindow.builder()
                .setUpperPane(this.pane)
                .setTitle(this.text(this.archivePath == null ? "title.snapshot" : "title.archive", this.playerName))
                .addOpenHandler(opened -> {
                    this.pane.setItem(0, this.buildNavigationButton());
                    this.load();
                }).build(this.viewer);
        return this.window;
    }

    private void controls() {
        this.pane.setItem(1, this.buildPlayerInfo());
        this.pane.setItem(2, this.buildInventoryTabButton());
        this.pane.setItem(3, this.buildEnderChestTabButton());
        this.pane.setItem(4, this.buildSummaryInfo());
        this.pane.setItem(51, this.buildLocationButton());
        if (!this.viewer.hasPermission(EDIT)) {
            return;
        }
        this.pane.setItem(8, this.buildDeleteButton());
        if (this.archivePath == null) {
            this.pane.setItem(5, this.buildPinButton());
            this.pane.setItem(6, this.buildJsonExportButton());
            this.pane.setItem(7, this.buildBinaryExportButton());
        }
        this.pane.setItem(52, this.buildClaimButton());
        if (this.archivePath == null) {
            this.pane.setItem(53, this.buildRestoreButton());
        }
    }

    private void buildContent(VirtualInventory inventory, boolean ender) {
        Pane body = ender ? this.enderPane : this.inventoryPane;
        body.fill(Item.empty());
        // 主背包和快捷栏按原版顺序映射, 坐骑等额外槽位不参与展示.
        List<Integer> mapping = SnapshotContents.slots(inventory.size(), ender, ender ? this.enderPage : 0);
        for (int i = 0; i < mapping.size(); i++) {
            if (mapping.get(i) >= 0) {
                body.setElement(i, Element.inventory(inventory, mapping.get(i)));
            }
        }
        if (!(ender ? this.contents.enderAvailable() : this.contents.inventoryAvailable())) {
            body.setItem(13, this.buildContentUnavailableInfo());
        }
        if (!ender) {
            int[] equipment = {39, 38, 37, 36, 40};
            for (int i = 0; i < equipment.length; i++) {
                if (equipment[i] < inventory.size()) {
                    body.setElement(36 + i, Element.inventory(inventory, equipment[i]));
                }
            }
        }
        if (ender) {
            body.setItem(36, this.buildCapacityInfo());
            int pages = Math.max(1, (inventory.size() + 35) / 36);
            if (pages > 1) {
                body.setItem(37, Item.builder().setItemProviderConstant(this.icon(Material.ARROW, "button.previous")).addClickHandler(click -> {
                    this.enderPage = Math.max(0, this.enderPage - 1);
                    this.buildContent(this.enderChest, true);
                }).build());
                body.setItem(38, Item.simple(this.icon(Material.PAPER, this.text("info.page", this.enderPage + 1, pages), false, List.of())));
                body.setItem(39, Item.builder().setItemProviderConstant(this.icon(Material.ARROW, "button.next")).addClickHandler(click -> {
                    this.enderPage = Math.min(pages - 1, this.enderPage + 1);
                    this.buildContent(this.enderChest, true);
                }).build());
            }
        }
    }

    private Item buildNavigationButton() {
        boolean back = this.window.session().hasBack();
        return Item.builder().setItemProviderConstant(this.icon(back ? Material.ARROW : Material.OAK_DOOR, back ? "button.back" : "button.close")).addClickHandler(click -> this.window.backOrClose()).build();
    }

    private Item buildPlayerInfo() {
        return Item.builder().dependsOn(this.meta).setItemProvider(context -> {
            SnapshotMeta meta = this.meta.get();
            List<Component> lore = new ArrayList<>();
            lore.add(this.text("label.player_id", meta.player()));
            lore.addAll(this.metadata(meta));
            if (this.archivePath != null) {
                lore.add(Component.empty());
                lore.add(this.text("label.archive", this.archivePath));
                lore.add(this.text("label.category", this.archive.category()));
            }
            return this.icon(Material.PLAYER_HEAD, this.text("info.player", this.player.name()), false, lore);
        }).build();
    }

    private Item buildInventoryTabButton() {
        return Item.builder().dependsOn(this.tabs.selected())
                .setItemProvider(context -> this.icon(Material.CHEST, this.text("button.inventory"), !this.tabs.selected().get(), List.of()))
                .addClickHandler(click -> {
                    if (this.archivePath == null) {
                        this.tabs.select(false);
                    } else {
                        this.loadArchivePreview(InventoryDataType.INVENTORY);
                    }
                }).build();
    }

    private Item buildEnderChestTabButton() {
        return Item.builder().dependsOn(this.tabs.selected())
                .setItemProvider(context -> this.icon(Material.ENDER_CHEST, this.text("button.ender_chest"), this.tabs.selected().get(), List.of()))
                .addClickHandler(click -> {
                    if (this.archivePath == null) {
                        this.tabs.select(true);
                    } else {
                        this.loadArchivePreview(EnderChestDataType.ENDER_CHEST);
                    }
                }).build();
    }

    private Item buildSummaryInfo() {
        return Item.simple(this.icon(Material.EXPERIENCE_BOTTLE, this.text("additional.title"), false, this.summary()));
    }

    private Item buildPinButton() {
        return Item.builder().dependsOn(this.meta).setItemProvider(context -> {
            boolean pinned = this.meta.get().pinned();
            return this.icon(Material.NETHER_STAR, this.text(pinned ? "button.unpin" : "button.pin"), pinned, List.of());
        }).addClickHandler(click -> this.pin()).build();
    }

    private Item buildJsonExportButton() {
        return Item.builder().setItemProviderConstant(this.icon(Material.PAPER, "button.export_json")).addClickHandler(click -> this.export(SnapshotFiles.Format.JSON)).build();
    }

    private Item buildBinaryExportButton() {
        return Item.builder().setItemProviderConstant(this.icon(Material.WRITABLE_BOOK, "button.export_binary")).addClickHandler(click -> this.export(SnapshotFiles.Format.BINARY)).build();
    }

    private Item buildDeleteButton() {
        return Item.builder().setItemProviderConstant(this.icon(Material.BARRIER, "button.delete")).addClickHandler(click -> this.delete()).build();
    }

    private Item buildLocationButton() {
        if (!(this.ready.previews().get(LocationDataType.LOCATION) instanceof SnapshotDetailResult.Preview.Ready(var value)
                && value instanceof LocationDataType.PlayerLocation location)) {
            return Item.simple(this.icon(Material.COMPASS, "location.title", this.text("location.missing")));
        }
        List<Component> lore = new ArrayList<>();
        lore.add(this.text("location.world", location.world()));
        lore.add(this.text("location.coordinates", location.x(), location.y(), location.z()));
        if (Bukkit.getWorld(location.world()) == null) {
            lore.add(this.text("location.unavailable"));
        }
        return Item.builder().setItemProviderConstant(this.icon(Material.COMPASS, this.text("location.title"), false, lore)).addClickHandler(click -> {
            if (click.clickType() == ClickType.LEFT) {
                this.teleport(click.player(), location);
            }
        }).build();
    }

    private Item buildRestoreButton() {
        return Item.builder().setItemProviderConstant(this.icon(Material.CLOCK, "button.restore", this.text("restore_target", this.player.name()))).addClickHandler(click -> {
            if (click.clickType() == ClickType.LEFT) {
                this.restore();
            }
        }).build();
    }

    private Item buildClaimButton() {
        return Item.builder().setItemProviderConstant(this.icon(Material.SHULKER_BOX, "claim")).addClickHandler(click -> {
            if (click.clickType() == ClickType.LEFT) {
                this.pack(click.player());
            }
        }).build();
    }

    private Item buildRetryButton() {
        return Item.builder().setItemProviderConstant(this.icon(Material.BARRIER, "retry")).addClickHandler(click -> this.load()).build();
    }

    private Item buildContentUnavailableInfo() {
        return Item.simple(this.icon(Material.BARRIER, "container_unavailable"));
    }

    private Item buildCapacityInfo() {
        return Item.simple(this.icon(Material.ENDER_CHEST, "info.capacity", this.text("slots", this.enderChest.size())));
    }

    private Item buildArchiveInfo() {
        List<Component> lore = new ArrayList<>();
        lore.add(this.text("label.archive", this.archive.path()));
        lore.add(this.text("label.category", this.archive.category()));
        Component name = this.text("info.player", this.playerName.isEmpty() ? this.text("unknown") : Component.text(this.playerName));
        if (this.archive.informationAvailable()) {
            SnapshotMeta meta = this.archive.header().meta();
            String playerName = this.archive.header().playerName();
            name = this.text("info.player", playerName == null ? meta.player().toString() : playerName);
            lore.add(this.text("label.player_id", meta.player()));
            lore.addAll(this.metadata(meta));
        }
        return Item.simple(this.icon(Material.PLAYER_HEAD, name, false, lore));
    }

    private Item buildInvalidInfo(SnapshotDetailResult.Invalid invalid) {
        return Item.simple(this.icon(Material.BARRIER, "invalid", this.text("value", invalid.reason()), this.text("value", invalid.detail())));
    }

    // 渲染当前查看者语言中的 gui 消息
    Component text(String key, Object... values) {
        List<Component> arguments = Arrays.stream(values).map(value -> value instanceof Component component ? component : Component.text(String.valueOf(value))).toList();
        return TranslationManager.instance().render(Component.translatable("gui." + key).arguments(arguments), PlayerUtils.locale(this.viewer))
                .decoration(TextDecoration.ITALIC, false);
    }

    // 使用消息键创建无附魔光效的菜单图标.
    ItemStack icon(Material material, String key, Component... lore) {
        return this.icon(material, this.text(key), false, Arrays.asList(lore));
    }

    /**
     * 通过 NMS 组件构造完整菜单图标.
     * 名称、Lore 和固定光效都保存在原生 ItemStack 上.
     *
     * @param material 图标材质
     * @param name 渲染后的物品名称
     * @param glint 是否显示附魔光效
     * @param lore 渲染后的说明行
     * @return 与新建原生物品关联的 Bukkit 镜像
     */
    ItemStack icon(Material material, Component name, boolean glint, List<Component> lore) {
        // 直接修改 NMS 组件, 最后以 CraftItemStack 镜像交给 SparrowUI.
        var item = new net.minecraft.world.item.ItemStack(CraftMagicNumbers.getItem(material));
        item.set(DataComponents.CUSTOM_NAME, MinecraftComponents.fromAdventure(name));
        item.set(DataComponents.LORE, new ItemLore(lore.stream().map(MinecraftComponents::fromAdventure).toList()));
        item.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, glint);
        return CraftItemStackProxy.INSTANCE.asCraftMirror(item);
    }

    // 将快照元信息格式化为 ID、完整时间、原因、来源服和固定状态.
    private List<Component> metadata(SnapshotMeta meta) {
        return List.of(this.text("label.snapshot_id", meta.id()), this.text("label.time", FULL_TIME.format(Instant.ofEpochMilli(meta.timestamp()).atZone(ZoneId.systemDefault()))),
                this.text("label.cause", this.text("cause." + meta.cause().name().toLowerCase(Locale.ROOT))),
                this.text("label.server", meta.server()), this.text(meta.pinned() ? "state.pinned" : "state.unpinned"));
    }

    /**
     * 重新加载当前详情, 数据库快照准备已适配内容, 异常记录先显示头文件概览.
     * 本次窗口缓存随刷新清空, 后续点击类型时再读取异常正文.
     */
    private void load() {
        this.ready = null;
        this.load(null, false);
    }

    /**
     * 展开异常快照中被点击的类型, 已读取的其他类型留在当前窗口中供再次查看和领取.
     *
     * @param key 所选类型, null 表示只读取正文索引
     */
    private void loadArchivePreview(@Nullable DataKey key) {
        this.load(key, true);
    }

    /**
     * 按来源加载数据库详情或异常记录, 异常正文只由明确的预览操作读取.
     *
     * @param key 本次选择的异常类型
     * @param readBody 是否由用户要求读取异常正文
     */
    private void load(@Nullable DataKey key, boolean readBody) {
        this.status("loading");
        // 解码与名字解析异步完成, 容器副本可直接在完成回调中构建.
        CompletableFuture<Loaded> future;
        if (this.archivePath != null) {
            var details = this.plugin.snapshotService().details();
            CompletableFuture<SnapshotDetailResult.Archive> archive;
            if (!readBody) {
                archive = details.loadException(this.archivePath);
            } else if (this.ready != null) {
                archive = CompletableFuture.completedFuture(new SnapshotDetailResult.Archive(this.archive, this.ready));
            } else {
                archive = details.loadExceptionBody(this.archivePath);
            }
            future = archive.thenCompose(result -> key != null && result.result() instanceof SnapshotDetailResult.Ready ready
                            ? details.preview(ready, key).thenApply(preview -> new SnapshotDetailResult.Archive(result.entry(), preview))
                            : CompletableFuture.completedFuture(result))
                    .thenApplyAsync(result -> prepare(result.result(), result.entry()), this.plugin.scheduler().async());
        } else {
            future = this.plugin.snapshotService().details().load(this.snapshotId)
                    .thenApplyAsync(result -> prepare(result, null), this.plugin.scheduler().async());
        }
        future.thenCombine(this.playerName.isEmpty() ? CompletableFuture.completedFuture(Optional.<PlayerIdentity>empty())
                : this.plugin.playerDirectory().resolve(this.playerName), (detail, identity) -> new Resolved(detail, identity.orElse(null)))
                .whenComplete((resolved, failure) -> {
                    if (failure != null) {
                        this.failedContent(failure);
                        return;
                    }
                    // 异常快照数据损坏时仍保留异常快照头文件中的元信息, 用于显示错误详情和删除操作.
                    Loaded detail = resolved.detail();
                    this.archive = detail.archive();
                    if (detail.result() instanceof SnapshotDetailResult.Overview) {
                        this.showArchiveIndex();
                    } else if (detail.result() instanceof SnapshotDetailResult.Ready value) {
                        if (!this.playerName.isEmpty() && (resolved.player() == null || !resolved.player().uuid().equals(value.snapshot().meta().player()))) {
                            this.status("wrong_player");
                            return;
                        }
                        // 只在对应类型首次准备好时创建容器, 切换其他类型保留当前窗口中的物品变动.
                        boolean inventoryChanged = this.ready == null || this.ready.previews().get(InventoryDataType.INVENTORY) != value.previews().get(InventoryDataType.INVENTORY);
                        boolean enderChanged = this.ready == null || this.ready.previews().get(EnderChestDataType.ENDER_CHEST) != value.previews().get(EnderChestDataType.ENDER_CHEST);
                        this.ready = value;
                        this.contents = detail.contents();
                        this.meta.set(value.snapshot().meta());
                        this.player = resolved.player() == null ? new PlayerIdentity(this.meta.get().player(), this.meta.get().player().toString()) : resolved.player();
                        if (inventoryChanged) {
                            this.inventory = this.createInventory(Arrays.copyOf(this.contents.inventory(), Math.min(this.contents.inventory().length, 41)));
                        }
                        if (enderChanged) {
                            this.enderChest = this.createInventory(this.contents.enderChest().clone());
                            this.enderPage = 0;
                        }
                        this.buildContent(this.inventory, false);
                        this.buildContent(this.enderChest, true);
                        this.tabs.select(false);
                        this.controls();
                        this.showingContents.set(true);
                        if (this.archivePath != null) {
                            this.pane.setItem(1, Item.builder().setItemProviderConstant(this.icon(Material.BOOK, "archive.types"))
                                    .addClickHandler(click -> this.showArchiveIndex()).build());
                            if (key == null) {
                                this.showArchiveIndex();
                            } else if (key.equals(EnderChestDataType.ENDER_CHEST)) {
                                this.tabs.select(true);
                            } else if (!key.equals(InventoryDataType.INVENTORY)) {
                                this.statusPane.fill(Item.empty());
                                this.statusPane.setItem(13, this.buildSummaryInfo());
                                this.showingContents.set(false);
                            }
                        }
                    } else {
                        this.unreadable(detail.result());
                    }
                });
    }

    /**
     * 展示异常记录的类型名与原始字节数, 初次进入时信息全部来自头文件.
     * 清单不可得时提供读取正文的按钮, 每个类型由自己的点击操作准备预览.
     */
    private void showArchiveIndex() {
        this.showingContents.set(false);
        this.statusPane.fill(Item.empty());
        for (int slot = 2; slot <= 8; slot++) {
            this.pane.setItem(slot, Item.empty());
        }
        for (int slot = 51; slot < 54; slot++) {
            this.pane.setItem(slot, Item.empty());
        }
        this.pane.setItem(1, this.buildArchiveInfo());
        if (this.viewer.hasPermission(EDIT)) {
            this.pane.setItem(8, this.buildDeleteButton());
        }
        List<DataKey> keys = this.ready != null ? new ArrayList<>(this.ready.snapshot().keys())
                : this.archive.summary() == null ? List.of() : new ArrayList<>(this.archive.summary().keySet());
        if (keys.isEmpty()) {
            String state = this.archive.summary() != null || this.ready != null ? "empty"
                    : this.archive.bodyPresent() ? "archive.index_unavailable" : "archive.source_only";
            this.statusPane.setItem(13, Item.simple(this.icon(Material.PAPER, state)));
            if (this.archive.bodyPresent() && this.ready == null) {
                this.statusPane.setItem(22, Item.builder().setItemProviderConstant(this.icon(Material.BOOK, "archive.read_index"))
                        .addClickHandler(click -> this.loadArchivePreview(null)).build());
            }
            return;
        }
        int pages = (keys.size() + 35) / 36;
        this.archivePage = Math.clamp(this.archivePage, 0, pages - 1);
        for (int i = this.archivePage * 36; i < Math.min(keys.size(), (this.archivePage + 1) * 36); i++) {
            DataKey key = keys.get(i);
            int rawLength;
            try {
                rawLength = this.ready != null ? this.ready.snapshot().content().rawLength(key) : this.archive.summary().get(key);
            } catch (UncheckedIOException failure) {
                rawLength = -1;
            }
            List<Component> lore = new ArrayList<>();
            lore.add(this.text(rawLength < 0 ? "archive.size_unknown" : "archive.type_size", rawLength));
            if (this.plugin.dataRegistry().type(key) == null) {
                lore.add(this.text(this.plugin.dataRegistry().shouldDropUnknown(key) ? "additional.unknown_drop" : "additional.unknown_keep", key.asString()));
            }
            lore.add(this.text(this.archive.bodyPresent() ? "archive.preview" : "archive.source_only"));
            this.statusPane.setItem(i % 36, Item.builder().setItemProviderConstant(this.icon(Material.PAPER, Component.text(key.asString()), false, lore))
                    .addClickHandler(click -> {
                        if (this.archive.bodyPresent()) {
                            this.loadArchivePreview(key);
                        }
                    }).build());
        }
        if (pages > 1) {
            this.statusPane.setItem(36, Item.builder().setItemProviderConstant(this.icon(Material.ARROW, "button.previous"))
                    .addClickHandler(click -> { this.archivePage--; this.showArchiveIndex(); }).build());
            this.statusPane.setItem(37, Item.simple(this.icon(Material.PAPER, this.text("info.page", this.archivePage + 1, pages), false, List.of())));
            this.statusPane.setItem(38, Item.builder().setItemProviderConstant(this.icon(Material.ARROW, "button.next"))
                    .addClickHandler(click -> { this.archivePage++; this.showArchiveIndex(); }).build());
        }
    }

    /**
     * 将解码成功的预览转为独立物品源, 同时保留异常快照头文件中的元信息.
     *
     * @param result 底层详情加载器返回的结果
     * @param archive 异常快照索引项, 数据库来源为 null
     * @return 用于装配菜单的快照预览与物品副本
     */
    private static Loaded prepare(SnapshotDetailResult result, @Nullable SnapshotFiles.ExceptionEntry archive) {
        return new Loaded(result, archive, result instanceof SnapshotDetailResult.Ready ready ? SnapshotContents.prepare(ready) : null);
    }

    /**
     * 根据本次读取的快照建立可编辑副本, 点击与物品事务均检查当前编辑权限.
     * 权限监听随容器存活.
     *
     * @param items 本次展示的独立物品数组
     * @return 拥有独立内容的临时容器
     */
    private VirtualInventory createInventory(ItemStack[] items) {
        VirtualInventory inventory = new VirtualInventory(items);
        // 创造克隆在点击阶段拦截, 拖拽与跨容器移动在事务提交前检查.
        inventory.subscribeClick(event -> {
            if (!event.player().hasPermission(EDIT)) {
                event.cancel();
            }
        });
        inventory.subscribePreUpdate(event -> {
            if (event.reason() instanceof PlayerUpdateReason reason
                    && !reason.player().hasPermission(EDIT)) {
                event.setCancelled(true);
            }
        });
        return inventory;
    }

    /**
     * 生成详情页摘要图标的说明文字, 包括已读取的经验, 生命值等玩家状态.
     * 末尾列出无法预览的类型; 已知 NBT 字节数时一并显示, 长度未知时只显示类型名.
     *
     * @return 按显示顺序排列的物品说明行
     */
    private List<Component> summary() {
        List<Component> lines = new ArrayList<>();
        var previews = this.ready.previews();
        if (previews.get(InventoryDataType.INVENTORY) instanceof SnapshotDetailResult.Preview.Ready(var value) && value instanceof InventoryDataType.Inventory inventory) {
            lines.add(this.text("additional.held_slot", inventory.heldSlot() + 1));
        }
        if (previews.get(HungerDataType.HUNGER) instanceof SnapshotDetailResult.Preview.Ready(var value) && value instanceof HungerDataType.Hunger hunger) {
            lines.add(this.text("additional.hunger", hunger.food(), hunger.saturation(), hunger.exhaustion()));
        }
        if (previews.get(GameModeDataType.GAME_MODE) instanceof SnapshotDetailResult.Preview.Ready(var value) && value instanceof GameMode gameMode) {
            lines.add(this.text("additional.game_mode", gameMode.name()));
        }
        if (previews.get(ExperienceDataType.EXPERIENCE) instanceof SnapshotDetailResult.Preview.Ready(var value) && value instanceof ExperienceDataType.Experience experience) {
            lines.add(this.text("additional.experience", experience.level(), experience.total(), experience.progress()));
        }
        if (previews.get(HealthDataType.HEALTH) instanceof SnapshotDetailResult.Preview.Ready(var value) && value instanceof HealthDataType.Health health) {
            lines.add(this.text("additional.health", health.health()));
        }
        if (previews.get(EnchantmentSeedDataType.ENCHANTMENT_SEED) instanceof SnapshotDetailResult.Preview.Ready(var value) && value instanceof Integer seed) {
            lines.add(this.text("additional.enchantment_seed", seed));
        }
        if (previews.get(VaultDataType.VAULT) instanceof SnapshotDetailResult.Preview.Ready(var value) && value instanceof VaultDataType.Money money) {
            lines.add(this.text("additional.vault", BigDecimal.valueOf(money.amount()).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()));
        }
        // 预览状态中保存已读取的块头大小, 待加载类型与读取失败的类型分别展示.
        for (var entry : previews.entrySet()) {
            if (entry.getValue() instanceof SnapshotDetailResult.Preview.Unloaded unloaded) {
                lines.add(this.text(unloaded.rawLength() < 0 ? "archive.type_unloaded_unknown" : "archive.type_unloaded", entry.getKey().asString(), unloaded.rawLength()));
            } else if (entry.getValue() instanceof SnapshotDetailResult.Preview.Failed failed) {
                lines.add(this.text("archive.type_failed", entry.getKey().asString(), failed.detail()));
            } else if (entry.getValue() instanceof SnapshotDetailResult.Preview.Unsupported unsupported) {
                String state = unsupported.registered()
                        ? "additional.unsupported"
                        : unsupported.discardUnknown() ? "additional.unknown_drop" : "additional.unknown_keep";
                lines.add(unsupported.rawLength() < 0
                        ? this.text(state, entry.getKey().asString())
                        : this.text(state + "_size", entry.getKey().asString(), unsupported.rawLength()));
            }
        }
        return lines;
    }

    /**
     * 切换数据库快照固定状态, 成功后更新详情中的元信息与上级列表.
     * 业务结果报告记录不存在时返回上一级.
     */
    private void pin() {
        boolean pin = !this.meta.get().pinned();
        this.operation(() -> pin
                ? this.plugin.snapshotService().pin(this.meta.get().id()).thenApply(result -> !(result instanceof SnapshotPinResult.NotFound))
                : this.plugin.snapshotService().unpin(this.meta.get().id()).thenApply(result -> !(result instanceof SnapshotUnpinResult.NotFound)),
                found -> {
                    this.invalidateParent();
                    if (!found) {
                        this.removed();
                        return;
                    }
                    this.meta.set(this.meta.get().withPinned(pin));
                    this.message(pin ? "pinned_feedback" : "unpinned_feedback");
                });
    }

    /**
     * 按指定格式导出原快照, 沿用服务的覆盖规则.
     * 临时展示 Inventory 的拿取和放入不会改变导出内容.
     *
     * @param format JSON 或二进制输出格式
     */
    private void export(SnapshotFiles.Format format) {
        this.operation(() -> this.plugin.snapshotService().export(this.meta.get().id(), format), result -> {
            switch (result) {
                case SnapshotExportResult.Exported exported -> this.message("exported", exported.path());
                case SnapshotExportResult.NotFound ignored -> this.removed();
            }
        });
    }

    /**
     * 将原快照恢复到所属玩家, 并反馈现有恢复业务的最终结果.
     * 操作使用完整快照 ID, 在线应用和离线下次登录语义由原服务负责.
     */
    private void restore() {
        this.operation(() -> {
            // 本服在线、远端在线和离线目标沿用命令 restore 的三条业务路径.
            Player local = Bukkit.getPlayer(this.player.uuid());
            if (local != null) {
                return this.plugin.snapshotService().restore(local, this.meta.get().id());
            }
            return this.plugin.playerDirectory().server(this.player.name())
                    .map(server -> this.plugin.remoteSnapshotManager().restore(server, this.player.uuid(), this.meta.get().id()))
                    .orElseGet(() -> this.plugin.snapshotService().restoreOffline(this.player, this.meta.get().id()));
        }, result -> {
            this.invalidateParent();
            String key = switch (result) {
                case SnapshotRestoreResult.Restored ignored -> "restored";
                case SnapshotRestoreResult.RestoredOffline ignored -> "restored_offline";
                case SnapshotRestoreResult.NotFound ignored -> "not_found";
                case SnapshotRestoreResult.WrongPlayer ignored -> "wrong_player";
                case SnapshotRestoreResult.Offline ignored -> "offline";
                case SnapshotRestoreResult.Cancelled ignored -> "cancelled";
                case SnapshotRestoreResult.Unavailable ignored -> "unavailable";
                case SnapshotRestoreResult.Failed ignored -> "failed";
            };
            this.message(key);
        });
    }

    // 按保存的世界名在本服定位, 传送保留坐标与朝向.
    private void teleport(Player recipient, LocationDataType.PlayerLocation location) {
        if (!this.editable()) {
            return;
        }
        var world = recipient.getServer().getWorld(location.world());
        if (world == null) {
            this.message("location.unavailable");
            return;
        }
        PlayerUtils.teleport(recipient, new Location(world, location.x(), location.y(), location.z(), location.yaw(), location.pitch())).whenComplete((moved, failure) -> {
            if (failure != null) {
                this.failed(failure);
            } else {
                this.message(moved ? "location.teleported" : "location.rejected");
            }
        });
    }

    /** 在 UI 点击回调中装箱并发放给点击玩家, 权限与容量在本次操作中检查. */
    private void pack(Player recipient) {
        if (!this.editable()) {
            return;
        }
        if (!this.contents.complete()) {
            this.message("incomplete");
            return;
        }
        List<ItemStack> items = ItemUtils.pack(this.contents.allItems(), MinecraftComponents.fromAdventure(this.text("package")));
        if (items.isEmpty()) {
            this.message("nothing_to_pack");
            return;
        }
        var inventory = recipient.getInventory();
        ItemStack[] storage = ItemUtils.fit(inventory.getStorageContents(), items, inventory.getMaxStackSize());
        if (storage == null) {
            this.message("no_space");
            return;
        }
        inventory.setStorageContents(storage);
        this.message("packed", items.size());
    }

    /**
     * 删除当前数据库快照或本服异常快照.
     * 完成后刷新上级并返回, 根详情直接关闭.
     */
    private void delete() {
        // 数据库走快照服务, 本服异常文件删除放在异步执行器中完成.
        this.operation(() -> this.archivePath == null ? this.plugin.snapshotService().delete(this.meta.get().id()).thenApply(result -> result instanceof SnapshotDeleteResult.Deleted)
                : CompletableFuture.supplyAsync(() -> {
                    try {
                        return this.plugin.snapshotService().files().deleteException(this.archivePath);
                    } catch (IOException failure) {
                        throw new CompletionException(failure);
                    }
                }, this.plugin.scheduler().async()), deleted -> {
            this.invalidateParent();
            this.message(deleted ? "deleted_feedback" : "not_found");
            this.removed();
        });
    }

    /**
     * 执行打开详情时传入的上级刷新动作.
     * 回调由列表提供, 直接命令打开的根窗口不需要上级刷新.
     */
    private void invalidateParent() {
        if (this.refreshParent != null) {
            this.refreshParent.run();
        }
    }

    /**
     * 显示读取失败与重试按钮, 并记录底层异常.
     *
     * @param failure 详情加载或预览准备期间的异常
     */
    private void failedContent(Throwable failure) {
        this.status("failed");
        this.statusPane.setItem(13, this.buildRetryButton());
        this.failed(failure);
    }

    /**
     * 展示快照数据不可用的具体状态, 异常来源仍保留异常快照头文件与删除入口.
     *
     * @param result 失败、无效或不存在的详情结果
     */
    private void unreadable(SnapshotDetailResult result) {
        switch (result) {
            case SnapshotDetailResult.Failed failed -> this.failedContent(failed.failure());
            case SnapshotDetailResult.Invalid invalid -> {
                this.status("invalid");
                this.statusPane.setItem(13, this.buildInvalidInfo(invalid));
            }
            default -> this.status("not_found");
        }
        if (this.archive != null) {
            this.pane.setItem(1, this.buildArchiveInfo());
            if (this.viewer.hasPermission(EDIT)) {
                this.pane.setItem(8, this.buildDeleteButton());
            }
        }
    }

    /** 更新状态 Pane, 内容区的投影负责切换显示. */
    private void status(String key) {
        this.statusPane.setItem(13, Item.simple(this.icon(Material.BARRIER, key)));
        this.showingContents.set(false);
    }

    /**
     * 在管理操作执行前检查 ui.edit 权限.
     * 缺少编辑权限时向查看者发送拒绝反馈.
     *
     * @return 查看者拥有编辑权限时为 true
     */
    private boolean editable() {
        if (this.viewer.hasPermission(EDIT)) {
            return true;
        }
        this.message("no_permission");
        return false;
    }

    /**
     * 执行需要 ui.edit 的异步管理操作, 完成后发布数据变化并反馈结果.
     *
     * @param <T> 现有业务操作的结果类型
     * @param action 通过现有服务启动业务并返回完成 Future
     * @param completed 业务成功完成后的菜单更新与反馈
     */
    private <T> void operation(Supplier<CompletableFuture<T>> action, Consumer<T> completed) {
        if (!this.editable()) {
            return;
        }
        action.get().whenComplete((result, failure) -> {
            if (failure != null) {
                this.failed(failure);
            } else {
                completed.accept(result);
            }
        });
    }

    /**
     * 按查看者语言发送带项目统一前缀的操作反馈.
     *
     * @param key gui 命名空间内的消息键
     * @param values 依次填入消息模板的参数
     */
    private void message(String key, Object... values) {
        PlayerUtils.sendMessage(this.viewer, this.text("feedback", this.text(key, values)));
    }

    /**
     * 记录本次菜单操作异常并向查看者发送失败反馈.
     *
     * @param failure 查询、构建或业务链传回的异常
     */
    private void failed(Throwable failure) {
        this.plugin.logger().warn("Snapshot GUI operation failed", failure);
        this.message("failed");
    }

    /** 返回上级或关闭根详情, 导航异常时静默关闭. */
    private void removed() {
        this.window.backOrClose().exceptionally(failure -> {
            this.window.close();
            return null;
        });
    }

    /**
     * 单份详情加载后已准备好的内容.
     *
     * @param result 快照数据加载与类型预览结果
     * @param archive 异常快照的列表信息, 数据库来源为 null
     * @param contents 原始物品源, 快照数据不可用时为 null
     */
    private record Loaded(SnapshotDetailResult result, @Nullable SnapshotFiles.ExceptionEntry archive, @Nullable SnapshotContents contents) {
    }

    /**
     * 汇合内容准备与玩家名字解析的结果.
     *
     * @param detail 完成准备的详情数据
     * @param player 名字解析结果, 直接异常入口或未找到玩家时为 null
     */
    private record Resolved(Loaded detail, @Nullable PlayerIdentity player) {
    }
}
