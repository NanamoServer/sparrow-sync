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

    String SERVER_ID_MISSING = "log.server.id_missing";

    String STORAGE_COMPRESSOR_FAILED = "log.storage.compressor_failed";
    String STORAGE_READY = "log.storage.ready";
    String STORAGE_MYSQL_NOT_IMPLEMENTED = "log.storage.mysql_not_implemented";
    String STORAGE_SETUP_FAILED = "log.storage.setup_failed";
    String STORAGE_OUT_OF_ORDER = "log.storage.out_of_order";
    String STORAGE_STALE_INDEX_DROPPED = "log.storage.stale_index_dropped";
    String STORAGE_SCHEMA_TOO_NEW = "log.storage.schema_too_new";

    String EXECUTOR_UNFINISHED_TASKS = "log.executor.unfinished_tasks";
    String EXECUTOR_DRAINING = "log.executor.draining";
    String EXECUTOR_TASK_FAILED = "log.executor.task_failed";

    String SYNC_SAVED = "log.sync.saved";
    String SYNC_SAVE_FAILED = "log.sync.save_failed";
    String SYNC_SAVE_SKIPPED_UNSYNCED = "log.sync.save_skipped_unsynced";
    String SYNC_ROTATE_FAILED = "log.sync.rotate_failed";
    String SYNC_APPLIED = "log.sync.applied";
    String SYNC_NO_SNAPSHOT = "log.sync.no_snapshot";
    String SYNC_LOAD_FAILED = "log.sync.load_failed";
    String SYNC_USER_FAILED = "log.sync.user_failed";
    String SYNC_SHUTDOWN_SAVED = "log.sync.shutdown_saved";

    String DATA_CAPTURE_SKIPPED = "log.data.capture_skipped";
    String DATA_CAPTURE_FAILED = "log.data.capture_failed";
    String DATA_INVENTORY_NOT_ENCODED = "log.data.inventory_not_encoded";
    String DATA_ENDER_CHEST_NOT_ENCODED = "log.data.ender_chest_not_encoded";
    String DATA_DECODE_SKIPPED = "log.data.decode_skipped";
    String DATA_APPLY_FAILED = "log.data.apply_failed";
    String DATA_APPLY_SKIPPED = "log.data.apply_skipped";
    String DATA_INVENTORY_DROPPED = "log.data.inventory_dropped";
    String DATA_ENDER_CHEST_DROPPED = "log.data.ender_chest_dropped";

    String LOCALE_MISSING_FILE = "log.locale.missing_file";
}
