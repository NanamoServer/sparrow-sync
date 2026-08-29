package net.momirealms.sparrow.sync.locale;

public interface LogConstants {
    String PLUGIN_COMPATIBILITY = "plugin.compatibility";
    String PLUGIN_COMPATIBILITY_FAILED = "plugin.compatibility_failed";
    String CONFIG_ERRORS_DETECTED = "config.errors_detected";
    String UPDATE_AVAILABLE = "update.available";
    String UPDATE_IS_LATEST = "update.is_latest";

    String PLUGIN_RELOAD_AT_RUNTIME = "log.plugin.reload_at_runtime";
    String PLUGIN_LOAD_FAILED = "log.plugin.load_failed";
    String PLUGIN_DISABLE_AT_RUNTIME = "log.plugin.disable_at_runtime";
    String PLUGIN_REGISTRY_FROZEN = "log.plugin.registry_frozen";
    String PLUGIN_RELOAD_FAILED = "log.plugin.reload_failed";

    String STORAGE_COMPRESSOR_FAILED = "log.storage.compressor_failed";
    String STORAGE_READY = "log.storage.ready";
    String STORAGE_MYSQL_NOT_IMPLEMENTED = "log.storage.mysql_not_implemented";
    String STORAGE_SETUP_FAILED = "log.storage.setup_failed";
    String STORAGE_SNAPSHOT_ENCODE_FAILED = "log.storage.snapshot_encode_failed";
    String STORAGE_SNAPSHOT_SAVE_FAILED = "log.storage.snapshot_save_failed";
    String STORAGE_OUT_OF_ORDER = "log.storage.out_of_order";

    String EXECUTOR_UNFINISHED_TASKS = "log.executor.unfinished_tasks";
    String EXECUTOR_DRAINING = "log.executor.draining";
    String EXECUTOR_TASK_FAILED = "log.executor.task_failed";

    String DATA_DECODE_SKIPPED = "log.data.decode_skipped";
    String DATA_APPLY_FAILED = "log.data.apply_failed";
    String DATA_APPLY_SKIPPED = "log.data.apply_skipped";
    String DATA_INVENTORY_DROPPED = "log.data.inventory_dropped";
    String DATA_ENDER_CHEST_DROPPED = "log.data.ender_chest_dropped";

    String LOCALE_MISSING_FILE = "log.locale.missing_file";
}
