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
    String PLUGIN_STARTED = "log.plugin.started";
    String PLUGIN_STOPPED = "log.plugin.stopped";
    String PLUGIN_REGISTRY_FROZEN = "log.plugin.registry_frozen_types";
    String PLUGIN_RELOAD_FAILED = "log.plugin.reload_failed";
    String PLAYER_DATA_STORAGE_INJECT_FAILED = "log.plugin.player_data_storage_install_failed";

    String SERVER_ID_MISSING = "log.server.id_missing";
    String SERVER_ID_DUPLICATE = "log.server.id_duplicate";
    String SERVER_ID_SEIZED = "log.server.id_seized";

    String STORAGE_COMPRESSOR_FAILED = "log.storage.compressor_failed";
    String STORAGE_READY = "log.storage.ready";
    String STORAGE_MYSQL_NOT_IMPLEMENTED = "log.storage.mysql_not_implemented";
    String STORAGE_SETUP_FAILED = "log.storage.setup_failed";
    String STORAGE_OUT_OF_ORDER = "log.storage.out_of_order";
    String STORAGE_ORDER_CHECK_FAILED = "log.storage.order_check_failed";
    String STORAGE_ENCODE_FAILED = "log.storage.encode_failed";
    String STORAGE_OVERSIZED = "log.storage.oversized";
    String STORAGE_CONSTRAINT_CONFLICT = "log.storage.constraint_conflict";
    String STORAGE_WRITE_RETRIABLE = "log.storage.write_retriable";
    String STORAGE_WRITE_REJECTED = "log.storage.write_rejected";
    String STORAGE_STALE_INDEX_DROPPED = "log.storage.stale_index_dropped";
    String STORAGE_SCHEMA_TOO_NEW = "log.storage.schema_too_new";

    String REDIS_READY = "log.redis.ready";
    String REDIS_SETUP_FAILED = "log.redis.setup_failed";

    String LOCK_ACQUIRED = "log.lock.acquired";
    String LOCK_WAITING = "log.lock.waiting";
    String LOCK_HANDOFF = "log.lock.handoff";
    String LOCK_RELEASED = "log.lock.released";
    String LOCK_SWEPT = "log.lock.swept";
    String LOCK_SELF_CONFLICT = "log.lock.self_conflict";

    String EXECUTOR_UNFINISHED_TASKS = "log.executor.unfinished_tasks";
    String EXECUTOR_DRAINING = "log.executor.draining";
    String EXECUTOR_TASK_FAILED = "log.executor.task_failed";

    String SESSION_JOIN = "log.session.join";
    String SESSION_QUIT = "log.session.quit";

    String GATE_HELD = "log.gate.held";
    String GATE_RELEASED = "log.gate.released";
    String GATE_KICKED = "log.gate.kicked";

    String SYNC_LOAD_READY = "log.sync.load_ready";
    String SYNC_LOAD_EMPTY = "log.sync.load_empty";
    String SYNC_LOCAL_DATA_READY = "log.sync.local_data_ready";
    String SYNC_LOCAL_DATA_EMPTY = "log.sync.local_data_empty";
    String SYNC_LOCAL_DATA_FALLBACK = "log.sync.local_data_fallback";
    String SYNC_LOGIN_COMPLETE = "log.sync.login_complete";
    String SYNC_APPLY_STARTED = "log.sync.apply_started";
    String SYNC_SAVE_STARTED = "log.sync.save_started";
    String SYNC_SAVED = "log.sync.saved";
    String SYNC_DISCONNECT_SAVED = "log.sync.disconnect_saved";
    String SYNC_SHUTDOWN_PLAYER_SAVED = "log.sync.shutdown_player_saved";
    String SYNC_SAVE_FAILED = "log.sync.save_failed";
    String SYNC_SAVE_PENDING_RETRY = "log.sync.save_pending_retry";
    String SYNC_SAVE_RETRIES_EXHAUSTED = "log.sync.save_retries_exhausted";
    String SYNC_SAVE_NEEDS_ATTENTION = "log.sync.save_needs_attention";
    String SYNC_SAVE_CANCELLED_BY_EVENT = "log.sync.save_cancelled_by_event";
    String SYNC_SAVE_SKIPPED_UNSYNCED = "log.sync.save_skipped_unsynced";
    String SYNC_ROTATE_FAILED = "log.sync.rotate_failed";
    String SYNC_APPLIED = "log.sync.applied";
    String SYNC_LOAD_FAILED = "log.sync.load_failed";
    String SYNC_USER_FAILED = "log.sync.user_failed";
    String SYNC_SHUTDOWN_SAVED = "log.sync.shutdown_saved";

    String STASH_PENDING = "log.stash.pending";
    String STASH_EXCEPTION = "log.stash.exception";
    String STASH_WRITE_FAILED = "log.stash.write_failed";
    String STASH_RESTORE_FOUND = "log.stash.restore_found";
    String STASH_RESTORE_DONE = "log.stash.restore_done";
    String STASH_RESTORE_UNAVAILABLE = "log.stash.restore_unavailable";
    String STASH_RESTORE_REJECTED = "log.stash.restore_rejected";
    String STASH_RESTORE_FAILED = "log.stash.restore_failed";
    String STASH_CORRUPTED = "log.stash.corrupted";

    String DATA_CAPTURE_SKIPPED = "log.data.capture_skipped";
    String DATA_CAPTURE_FAILED = "log.data.capture_failed";
    String DATA_ENCODE_SKIPPED = "log.data.encode_skipped";
    String DATA_ENCODE_FAILED = "log.data.encode_failed";
    String DATA_DECODE_SKIPPED = "log.data.decode_skipped";
    String DATA_MAP_COMPILE_FAILED = "log.data.map_compile_failed";
    String DATA_MAP_DECODE_FAILED = "log.data.map_decode_failed";
    String DATA_MAP_SOURCE_MISSING = "log.data.map_source_missing";
    String DATA_MAP_CACHE_FAILED = "log.data.map_cache_failed";
    String DATA_MAP_CACHE_TOUCH_FAILED = "log.data.map_cache_touch_failed";
    String DATA_MAP_REFRESH_FAILED = "log.data.map_refresh_failed";
    String DATA_MAP_PUBLISH_UNFINISHED = "log.data.map_publish_unfinished";
    String DATA_ADVANCEMENT_TRACKER_INSTALL_FAILED = "log.data.advancement_tracker_install_failed";
    String DATA_NATIVE_APPLY_FALLBACK = "log.data.native_apply_fallback";
    String DATA_APPLY_FAILED = "log.data.apply_failed";
    String DATA_APPLY_SKIPPED = "log.data.apply_skipped";
    String DATA_INVENTORY_DROPPED = "log.data.inventory_dropped";
    String DATA_ENDER_CHEST_DROPPED = "log.data.ender_chest_dropped";

    String LOCALE_MISSING_FILE = "log.locale.missing_file";
}
