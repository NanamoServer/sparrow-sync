package net.momirealms.sparrow.sync.gui;

import net.kyori.adventure.text.Component;
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

import java.util.concurrent.CompletableFuture;

public final class InvseeMenu {
    private final Player target;

    public InvseeMenu(@NotNull Player target) {
        this.target = target;
    }

    @NotNull
    public CompletableFuture<Window.OpenResult> open(@NotNull Player viewer) {
        Window window = Window.builder(this.buildPane())
                .setTitle(this.buildTitle())
                .build(viewer);
        return SparrowSync.instance().inventorySeeManager().open(this.target, window);
    }

    @NotNull
    private Component buildTitle() {
        String title = PluginConfig.invSeeTitle();
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
    private Pane buildPane() {
        ReferencingInventory inventory = ReferencingInventory.fromContents(this.target.getInventory());
        return Pane.builder(
                        "AAAA#O###",
                        "SSSSSSSSS",
                        "SSSSSSSSS",
                        "SSSSSSSSS",
                        "HHHHHHHHH"
                )
                .addIngredient('A', (slots, occurrence) -> Element.inventory(inventory, 39 - occurrence))
                .addIngredient('O', Element.inventory(inventory, 40))
                .addIngredient('S', (slots, occurrence) -> Element.inventory(inventory, 9 + occurrence))
                .addIngredient('H', (slots, occurrence) -> Element.inventory(inventory, occurrence))
                .build();
    }
}