package net.momirealms.sparrow.sync.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.locale.TranslationManagerImpl;
import net.momirealms.sparrow.sync.locale.tag.IndexedArgumentTag;
import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.route.Route;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotGuiTextTest {
    @Test
    void bothLanguagesHaveTheSameGuiKeysAndValidArgumentTemplates() throws Exception {
        Set<String> previous = null;
        for (String locale : List.of("en", "zh_cn")) {
            String resource = new String(this.getClass().getResourceAsStream("/translations/" + locale + ".yml").readAllBytes(), StandardCharsets.UTF_8);
            Set<String> keys = resource.lines().filter(line -> line.startsWith("gui.")).map(line -> line.substring(0, line.indexOf(':'))).collect(Collectors.toSet());
            if (previous != null) {
                assertEquals(previous, keys);
            }
            previous = keys;
            var document = SparrowYaml.builder().build().load(resource);
            for (String key : keys) {
                String template = document.getString(Route.from(key));
                assertNotNull(template, key);
                Component rendered = MiniMessage.miniMessage().deserialize(template, new IndexedArgumentTag(List.of(Component.text("A"), Component.text("B"), Component.text("C"))));
                assertFalse(textOf(rendered).contains("<arg:"), key);
            }
        }
    }

    @Test
    void nestedFeedbackKeepsProjectPrefixAndFormatsArguments() throws Exception {
        var document = SparrowYaml.builder().build().load(new String(this.getClass().getResourceAsStream("/translations/zh_cn.yml").readAllBytes(), StandardCharsets.UTF_8));
        Field field = TranslationManagerImpl.class.getDeclaredField("instance");
        field.setAccessible(true);
        Object previous = field.get(null);
        TranslationManager translator = (TranslationManager) Proxy.newProxyInstance(TranslationManager.class.getClassLoader(), new Class[]{TranslationManager.class}, (instance, method, args) -> {
            TranslatableComponent component = (TranslatableComponent) args[0];
            return MiniMessage.miniMessage().deserialize(document.getString(Route.from(component.key())), new IndexedArgumentTag(component.arguments()));
        });
        field.set(null, translator);
        try {
            org.bukkit.entity.Player viewer = (org.bukkit.entity.Player) Proxy.newProxyInstance(org.bukkit.entity.Player.class.getClassLoader(), new Class[]{org.bukkit.entity.Player.class}, (instance, method, args) -> Locale.SIMPLIFIED_CHINESE);
            SnapshotListGui text = new SnapshotListGui(null, viewer, "Test");
            assertEquals(">> SparrowSync · 已发放 3 个物品包", textOf(text.text("feedback", text.text("packed", 3))));
            assertEquals("#FFE08A", text.text("state.pinned").color().asHexString());
        } finally {
            field.set(null, previous);
        }
    }

    private static String textOf(Component component) {
        return (component instanceof TextComponent text ? text.content() : "") + String.join("", component.children().stream().map(SnapshotGuiTextTest::textOf).toList());
    }
}
