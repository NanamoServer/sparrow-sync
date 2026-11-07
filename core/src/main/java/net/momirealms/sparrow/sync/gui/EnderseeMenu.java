package net.momirealms.sparrow.sync.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.proxy.craftbukkit.inventory.CraftItemStackProxy;
import net.momirealms.sparrow.sync.util.ItemUtils;
import net.momirealms.sparrow.sync.util.MinecraftComponents;
import net.momirealms.sparrow.sync.util.PlayerUtils;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.pane.page.Page;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.util.AdventureHelper;
import net.momirealms.sparrow.ui.inventory.ReferencingInventory;
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class EnderseeMenu {
    private static final int PAGE_SIZE = 45;
    private static final Item FILLER;

    static {
        ItemStack glass = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        ItemUtils.hideTooltips(glass);
        FILLER = Item.simple(CraftItemStackProxy.INSTANCE.asCraftMirror(glass));
    }

    private final Player target;
    private Page<Element> page;

    public EnderseeMenu(@NotNull Player target) {
        this.target = target;
    }

    @NotNull
    public CompletableFuture<Window.OpenResult> open(@NotNull Player viewer) {
        Window window = Window.builder(this.buildPane(viewer))
                .setTitle(this.buildTitle())
                .build(viewer);
        return SparrowSync.instance().inventorySeeManager().open(this.target, window);
    }

    @NotNull
    private Component buildTitle() {
        String title = PluginConfig.enderSeeTitle();
        TagResolver arguments = TagResolver.resolver(
                "arg",
                (queue, context) -> {
                    String argument = queue.popOr("Missing argument name").value();
                    if (!argument.equals("user_name")) throw context.newException("Unknown argument: " + argument);
                    return Tag.selfClosingInserting(Component.text(this.target.getName()));
                }
        );
        return AdventureHelper.miniMessage().deserialize(title, arguments);
    }

    @NotNull
    private Pane buildPane(@NotNull Player viewer) {
        ReferencingInventory inventory = ReferencingInventory.fromContents(this.target.getEnderChest());
        int size = inventory.size();
        if (size > 54) return this.buildPagedPane(viewer, inventory);
        Pane pane = Pane.empty(9, (size + 8) / 9);
        for (int slot = 0; slot < size; slot++) {
            pane.setElement(slot, Element.inventory(inventory, slot));
        }
        return pane;
    }

    @NotNull
    private Pane buildPagedPane(@NotNull Player viewer, @NotNull ReferencingInventory inventory) {
        int size = inventory.size();
        List<Element> slots = new ArrayList<>(size);
        for (int slot = 0; slot < size; slot++) {
            slots.add(Element.inventory(inventory, slot));
        }
        this.page = Page.of(slots, PAGE_SIZE);
        return Pane.builder(
                        "SSSSSSSSS",
                        "SSSSSSSSS",
                        "SSSSSSSSS",
                        "SSSSSSSSS",
                        "SSSSSSSSS",
                        "#######PN"
                )
                .addIngredient('S', this.page, element -> element)
                .addIngredient('#', FILLER)
                .addIngredient('P', this.buildPageButton(viewer, "gui.button.previous", -1))
                .addIngredient('N', this.buildPageButton(viewer, "gui.button.next", 1))
                .build();
    }

    @NotNull
    private Item buildPageButton(@NotNull Player viewer, @NotNull String key, int step) {
        Component name = TranslationManager.instance().render(Component.translatable(key), PlayerUtils.locale(viewer))
                .decoration(TextDecoration.ITALIC, false);
        ItemStack arrow = new ItemStack(Items.ARROW);
        arrow.set(DataComponents.CUSTOM_NAME, MinecraftComponents.fromAdventure(name));
        return Item.builder()
                .setItemProviderConstant(CraftItemStackProxy.INSTANCE.asCraftMirror(arrow))
                .addClickHandler(click -> this.page.advance(step))
                .build();
    }
}