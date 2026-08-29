package net.momirealms.sparrow.sync.locale;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;

public interface MessageConstants {
    TranslatableComponent.Builder COMMAND_RELOAD_TOO_FAST = Component.translatable().key("command.reload.too_fast");
    TranslatableComponent.Builder COMMAND_RELOAD_CONFIG_SUCCESS = Component.translatable().key("command.reload.config.success");
    TranslatableComponent.Builder COMMAND_RELOAD_CONFIG_ISSUES = Component.translatable().key("command.reload.config.issues");
    TranslatableComponent.Builder COMMAND_RELOAD_CONFIG_FAILURE = Component.translatable().key("command.reload.config.failure");

    String PLUGIN_COMPATIBILITY = "plugin.compatibility";
    String PLUGIN_COMPATIBILITY_FAILED = "plugin.compatibility_failed";
    String CONFIG_ERRORS_DETECTED = "config.errors_detected";
    String UPDATE_AVAILABLE = "update.available";
    String UPDATE_IS_LATEST = "update.is_latest";

    String LOG_PLUGIN_RELOAD_AT_RUNTIME = "log.plugin.reload_at_runtime";
    String LOG_PLUGIN_LOAD_FAILED = "log.plugin.load_failed";
    String LOG_PLUGIN_DISABLE_AT_RUNTIME = "log.plugin.disable_at_runtime";
    String LOG_PLUGIN_REGISTRY_FROZEN = "log.plugin.registry_frozen";
    String LOG_PLUGIN_RELOAD_FAILED = "log.plugin.reload_failed";

    String LOG_STORAGE_COMPRESSOR_FAILED = "log.storage.compressor_failed";
    String LOG_STORAGE_READY = "log.storage.ready";
    String LOG_STORAGE_MYSQL_NOT_IMPLEMENTED = "log.storage.mysql_not_implemented";
    String LOG_STORAGE_SETUP_FAILED = "log.storage.setup_failed";
    String LOG_STORAGE_SNAPSHOT_ENCODE_FAILED = "log.storage.snapshot_encode_failed";
    String LOG_STORAGE_SNAPSHOT_SAVE_FAILED = "log.storage.snapshot_save_failed";
    String LOG_STORAGE_OUT_OF_ORDER = "log.storage.out_of_order";

    String LOG_EXECUTOR_UNFINISHED_TASKS = "log.executor.unfinished_tasks";
    String LOG_EXECUTOR_DRAINING = "log.executor.draining";
    String LOG_EXECUTOR_TASK_FAILED = "log.executor.task_failed";

    String LOG_DATA_DECODE_SKIPPED = "log.data.decode_skipped";
    String LOG_DATA_APPLY_FAILED = "log.data.apply_failed";
    String LOG_DATA_APPLY_SKIPPED = "log.data.apply_skipped";
    String LOG_DATA_INVENTORY_DROPPED = "log.data.inventory_dropped";
    String LOG_DATA_ENDER_CHEST_DROPPED = "log.data.ender_chest_dropped";
    String LOG_LOCALE_MISSING_FILE = "log.locale.missing_file";
}
