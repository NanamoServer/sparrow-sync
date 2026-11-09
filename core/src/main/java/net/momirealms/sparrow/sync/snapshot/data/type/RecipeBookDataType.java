package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.stats.ServerRecipeBook;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.crafting.Recipe;
import net.momirealms.sparrow.sync.proxy.minecraft.stats.ServerRecipeBookProxy;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class RecipeBookDataType extends CodecDataType<RecipeBookDataType.RecipeBook> {
    public static final DataKey RECIPE_BOOK = DataKey.sparrow("recipe_book");

    public RecipeBookDataType() {
        super(RECIPE_BOOK, RecipeBook.CODEC);
    }

    @NotNull
    @Override
    protected RecipeBook captureValue(@NotNull Player player, @NotNull CaptureMode mode) {
        ServerRecipeBook book = ((CraftPlayer) player).getHandle().getRecipeBook();
        List<Setting> settings = new ArrayList<>();
        for (RecipeBookType type : RecipeBookType.values()) {
            settings.add(new Setting(type.name(), book.isOpen(type), book.isFiltering(type)));
        }
        return new RecipeBook(List.copyOf(ServerRecipeBookProxy.INSTANCE.getKnown(book)), List.copyOf(ServerRecipeBookProxy.INSTANCE.getHighlight(book)), List.copyOf(settings));
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull RecipeBook value) {
        ServerRecipeBook book = ((CraftPlayer) player).getHandle().getRecipeBook();
        Set<ResourceKey<Recipe<?>>> known = ServerRecipeBookProxy.INSTANCE.getKnown(book);
        Set<ResourceKey<Recipe<?>>> highlight = ServerRecipeBookProxy.INSTANCE.getHighlight(book);
        known.clear();
        known.addAll(value.known());
        highlight.clear();
        highlight.addAll(value.highlight());
        for (Setting setting : value.settings()) {
            RecipeBookType type = RecipeBookType.valueOf(setting.type());
            book.setBookSetting(type, setting.open(), setting.filtering());
        }
        book.sendInitialRecipeBook(((CraftPlayer) player).getHandle());
    }

    public record Setting(String type, boolean open, boolean filtering) {
        public static final Codec<Setting> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("type").forGetter(Setting::type),
                Codec.BOOL.fieldOf("open").forGetter(Setting::open),
                Codec.BOOL.fieldOf("filtering").forGetter(Setting::filtering)
        ).apply(instance, Setting::new));
    }

    public record RecipeBook(List<ResourceKey<Recipe<?>>> known, List<ResourceKey<Recipe<?>>> highlight, List<Setting> settings) {
        public static final Codec<RecipeBook> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Recipe.KEY_CODEC.listOf().fieldOf("recipes").forGetter(RecipeBook::known),
                Recipe.KEY_CODEC.listOf().fieldOf("toBeDisplayed").forGetter(RecipeBook::highlight),
                Setting.CODEC.listOf().fieldOf("settings").forGetter(RecipeBook::settings)
        ).apply(instance, RecipeBook::new));
    }
}
