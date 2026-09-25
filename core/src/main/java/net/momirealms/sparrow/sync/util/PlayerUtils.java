package net.momirealms.sparrow.sync.util;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.sync.proxy.minecraft.network.protocol.game.ClientboundSystemChatPacketProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.server.level.ServerPlayerProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.server.network.ServerCommonPacketListenerImplProxy;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class PlayerUtils {
    private PlayerUtils() {}

    @NotNull
    public static CompletableFuture<Boolean> teleport(@NotNull Player player, @NotNull Location location) {
        if (VersionHelper.hasPaperPatch) {
            return player.teleportAsync(location);
        }
        return CompletableFuture.completedFuture(player.teleport(location));
    }

    @NotNull
    public static Locale locale(@NotNull Player player) {
        String language = ServerPlayerProxy.INSTANCE.getLanguage(((CraftPlayer) player).getHandle());
        return language == null ? Locale.US : Locale.forLanguageTag(language.replace('_', '-'));
    }

    public static void sendMessage(@NotNull Player player, @NotNull Component message) {
        Object connection = ServerPlayerProxy.INSTANCE.getConnection(((CraftPlayer) player).getHandle());
        Object packet = ClientboundSystemChatPacketProxy.INSTANCE.newInstance(MinecraftComponents.fromAdventure(message), false);
        ServerCommonPacketListenerImplProxy.INSTANCE.send(connection, packet);
    }

    public static void sendMessage(@NotNull CommandSender sender, @NotNull Component message) {
        if (sender instanceof Player player) {
            sendMessage(player, message);
            return;
        }
        sender.sendMessage(AdventureHelper.getLegacy().serialize(message));
    }
}
