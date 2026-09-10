package net.momirealms.sparrow.sync.locale;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;

public interface MessageConstants {
    TranslatableComponent.Builder COMMAND_QUERY_FAILED = Component.translatable().key("command.query_failed");
    TranslatableComponent.Builder COMMAND_PLAYER_NOT_FOUND = Component.translatable().key("command.player_not_found");
    TranslatableComponent.Builder COMMAND_GUI_PLAYER_NOT_FOUND = Component.translatable().key("command.gui.player_not_found");
    TranslatableComponent.Builder COMMAND_STATUS_SYSTEM = Component.translatable().key("command.status.system");
    TranslatableComponent.Builder COMMAND_CONNECTED = Component.translatable().key("command.connected");
    TranslatableComponent.Builder COMMAND_DISCONNECTED = Component.translatable().key("command.disconnected");
    TranslatableComponent.Builder COMMAND_STATUS_NO_LOCK = Component.translatable().key("command.status.no_lock");
    TranslatableComponent.Builder COMMAND_STATUS_NO_SNAPSHOT = Component.translatable().key("command.status.no_snapshot");
    TranslatableComponent.Builder COMMAND_STATUS_SNAPSHOT = Component.translatable().key("command.status.snapshot");
    TranslatableComponent.Builder COMMAND_PINNED = Component.translatable().key("command.pinned");
    TranslatableComponent.Builder COMMAND_UNPINNED = Component.translatable().key("command.unpinned");
    TranslatableComponent.Builder COMMAND_STATUS_NO_SESSION = Component.translatable().key("command.status.no_session");
    TranslatableComponent.Builder COMMAND_STATUS_PLAYER = Component.translatable().key("command.status.player");
    TranslatableComponent.Builder COMMAND_STATUS_UNKNOWN = Component.translatable().key("command.status.unknown");

    TranslatableComponent.Builder COMMAND_RELOAD_TOO_FAST = Component.translatable().key("command.reload.too_fast");
    TranslatableComponent.Builder COMMAND_RELOAD_CONFIG_SUCCESS = Component.translatable().key("command.reload.config.success");
    TranslatableComponent.Builder COMMAND_RELOAD_CONFIG_FAILURE = Component.translatable().key("command.reload.config.failure");

    TranslatableComponent.Builder KICK_SYNC_NOT_READY = Component.translatable().key("sync.kick.not_ready");
    TranslatableComponent.Builder KICK_LOGIN_TOO_FAST = Component.translatable().key("sync.kick.too_fast");
}
