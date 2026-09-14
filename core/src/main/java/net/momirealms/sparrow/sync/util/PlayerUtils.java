package net.momirealms.sparrow.sync.util;

import net.momirealms.sparrow.sync.proxy.minecraft.server.level.ServerPlayerProxy;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

public final class PlayerUtils {
    private PlayerUtils() {}

    @NotNull
    public static Locale locale(@NotNull Player player) {
        String language = ServerPlayerProxy.INSTANCE.getLanguage(((CraftPlayer) player).getHandle());
        return language == null ? Locale.US : Locale.forLanguageTag(language.replace('_', '-'));
    }
}
