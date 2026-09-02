package net.momirealms.sparrow.sync.locale;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;

public interface MessageConstants {
    TranslatableComponent.Builder EXCEPTION_INVALID_SYNTAX = Component.translatable().key("exception.invalid_syntax");
    TranslatableComponent.Builder EXCEPTION_INVALID_ARGUMENT = Component.translatable().key("exception.invalid_argument");
    TranslatableComponent.Builder EXCEPTION_INVALID_SENDER = Component.translatable().key("exception.invalid_sender");
    TranslatableComponent.Builder EXCEPTION_UNEXPECTED = Component.translatable().key("exception.unexpected");
    TranslatableComponent.Builder EXCEPTION_NO_PERMISSION = Component.translatable().key("exception.no_permission");
    TranslatableComponent.Builder EXCEPTION_NO_SUCH_COMMAND = Component.translatable().key("exception.no_such_command");

    TranslatableComponent.Builder ARGUMENT_ENTITY_NOTFOUND_PLAYER = Component.translatable().key("argument.entity.notfound.player");
    TranslatableComponent.Builder ARGUMENT_ENTITY_NOTFOUND_ENTITY = Component.translatable().key("argument.entity.notfound.entity");

    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_TIME = Component.translatable().key("argument.parse.failure.time");
    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_MATERIAL = Component.translatable().key("argument.parse.failure.material");
    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_ENCHANTMENT = Component.translatable().key("argument.parse.failure.enchantment");
    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_OFFLINEPLAYER = Component.translatable().key("argument.parse.failure.offlineplayer");
    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_PLAYER = Component.translatable().key("argument.parse.failure.player");
    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_WORLD = Component.translatable().key("argument.parse.failure.world");
    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_LOCATION_INVALID_FORMAT = Component.translatable().key("argument.parse.failure.location.invalid_format");
    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_LOCATION_MIXED_LOCAL_ABSOLUTE = Component.translatable().key("argument.parse.failure.location.mixed_local_absolute");
    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_NAMESPACEDKEY_NAMESPACE = Component.translatable().key("argument.parse.failure.namespacedkey.namespace");
    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_NAMESPACEDKEY_KEY = Component.translatable().key("argument.parse.failure.namespacedkey.key");
    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_NAMESPACEDKEY_NEED_NAMESPACE = Component.translatable().key("argument.parse.failure.namespacedkey.need_namespace");
    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_BOOLEAN = Component.translatable().key("argument.parse.failure.boolean");
    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_NUMBER = Component.translatable().key("argument.parse.failure.number");
    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_CHAR = Component.translatable().key("argument.parse.failure.char");
    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_STRING = Component.translatable().key("argument.parse.failure.string");
    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_UUID = Component.translatable().key("argument.parse.failure.uuid");
    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_ENUM = Component.translatable().key("argument.parse.failure.enum");
    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_REGEX = Component.translatable().key("argument.parse.failure.regex");
    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_COLOR = Component.translatable().key("argument.parse.failure.color");
    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_DURATION = Component.translatable().key("argument.parse.failure.duration");
    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_EITHER = Component.translatable().key("argument.parse.failure.either");
    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_NAMEDTEXTCOLOR = Component.translatable().key("argument.parse.failure.namedtextcolor");

    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_FLAG_UNKNOWN = Component.translatable().key("argument.parse.failure.flag.unknown");
    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_FLAG_DUPLICATE_FLAG = Component.translatable().key("argument.parse.failure.flag.duplicate_flag");
    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_FLAG_NO_FLAG_STARTED = Component.translatable().key("argument.parse.failure.flag.no_flag_started");
    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_FLAG_MISSING_ARGUMENT = Component.translatable().key("argument.parse.failure.flag.missing_argument");
    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_FLAG_NO_PERMISSION = Component.translatable().key("argument.parse.failure.flag.no_permission");

    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_AGGREGATE_MISSING = Component.translatable().key("argument.parse.failure.aggregate.missing");
    TranslatableComponent.Builder ARGUMENT_PARSE_FAILURE_AGGREGATE_FAILURE = Component.translatable().key("argument.parse.failure.aggregate.failure");

    TranslatableComponent.Builder COMMAND_RELOAD_TOO_FAST = Component.translatable().key("command.reload.too_fast");
    TranslatableComponent.Builder COMMAND_RELOAD_CONFIG_SUCCESS = Component.translatable().key("command.reload.config.success");
    TranslatableComponent.Builder COMMAND_RELOAD_CONFIG_FAILURE = Component.translatable().key("command.reload.config.failure");

    TranslatableComponent.Builder KICK_SYNC_NOT_READY = Component.translatable().key("sync.kick.not_ready");
    TranslatableComponent.Builder KICK_LOGIN_TOO_FAST = Component.translatable().key("sync.kick.too_fast");
}
