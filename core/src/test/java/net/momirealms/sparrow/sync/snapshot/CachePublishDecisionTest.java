package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.SnapshotWriter;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachePublishDecisionTest {

    @Test
    void onlyConfirmedEndOfSessionSavesReachTheFastPath() {
        // 会话收尾保存且确认落在库内最新, 只有这四种组合会投递
        assertTrue(SnapshotWriter.shouldPublish(SaveResult.SAVED, SaveCause.DISCONNECT));
        assertTrue(SnapshotWriter.shouldPublish(SaveResult.SAVED, SaveCause.SHUTDOWN));
        assertTrue(SnapshotWriter.shouldPublish(SaveResult.DUPLICATE, SaveCause.DISCONNECT));
        assertTrue(SnapshotWriter.shouldPublish(SaveResult.DUPLICATE, SaveCause.SHUTDOWN));
        // 落库了, 但存储层确认它落在历史中段
        assertFalse(SnapshotWriter.shouldPublish(SaveResult.SAVED_OUT_OF_ORDER, SaveCause.DISCONNECT));
        assertFalse(SnapshotWriter.shouldPublish(SaveResult.SAVED_OUT_OF_ORDER, SaveCause.SHUTDOWN));
        // 压根没落库
        assertFalse(SnapshotWriter.shouldPublish(SaveResult.RETRY_LATER, SaveCause.SHUTDOWN));
        assertFalse(SnapshotWriter.shouldPublish(SaveResult.REJECTED_OVERSIZED, SaveCause.DISCONNECT));
        assertFalse(SnapshotWriter.shouldPublish(SaveResult.REJECTED_MALFORMED, SaveCause.DISCONNECT));
        // 不是会话收尾的保存, 接手服读到它没有意义
        assertFalse(SnapshotWriter.shouldPublish(SaveResult.SAVED, SaveCause.WORLD_SAVE));
        assertFalse(SnapshotWriter.shouldPublish(SaveResult.SAVED, SaveCause.WORLD_CHANGE));
        assertFalse(SnapshotWriter.shouldPublish(SaveResult.SAVED, SaveCause.GAME_MODE_CHANGE));
        assertFalse(SnapshotWriter.shouldPublish(SaveResult.SAVED, SaveCause.PRE_DEATH));
        assertFalse(SnapshotWriter.shouldPublish(SaveResult.SAVED, SaveCause.DEATH));
        assertFalse(SnapshotWriter.shouldPublish(SaveResult.SAVED, SaveCause.RESTORE));
        assertFalse(SnapshotWriter.shouldPublish(SaveResult.SAVED, SaveCause.COMMAND));
        assertFalse(SnapshotWriter.shouldPublish(SaveResult.SAVED, SaveCause.API));
        assertFalse(SnapshotWriter.shouldPublish(SaveResult.SAVED, SaveCause.MIGRATION));
        assertFalse(SnapshotWriter.shouldPublish(SaveResult.SAVED, SaveCause.EDIT));
        assertFalse(SnapshotWriter.shouldPublish(SaveResult.SAVED, SaveCause.UNKNOWN));
    }
}
