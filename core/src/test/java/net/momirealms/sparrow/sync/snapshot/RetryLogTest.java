package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.sync.plugin.logger.FileLogWriter;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.SnapshotWriter.WriteAttempt;
import net.momirealms.sparrow.sync.snapshot.model.EagerSnapshotData;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetryLogTest {
    @TempDir
    Path directory;

    @Test
    void writesTheFullStackOnlyForTheFirstRetry() throws IOException {
        RecordingLogger console = new RecordingLogger();
        SyncLogger logger = new SyncLogger(console);
        logger.attachFile(new FileLogWriter(this.directory, console));
        WriteAttempt attempt = attempt(-1);

        for (int number = 1; number <= 20; number++) {
            SnapshotWriter.logRetry(logger, attempt, new IllegalStateException("database attempt " + number));
            attempt = attempt.next();
        }
        logger.close();

        assertEquals(3, console.warnings.size());
        assertEquals(0, console.warningCauses.size());
        String content = this.logContent();
        assertEquals(3, content.lines().filter(line -> line.contains("[RETRY]")).count());
        assertEquals(1, occurrences(content, "java.lang.IllegalStateException: database attempt"));
    }

    @Test
    void terminalFailureDoesNotClaimAnotherRetry() throws IOException {
        RecordingLogger console = new RecordingLogger();
        SyncLogger logger = new SyncLogger(console);
        logger.attachFile(new FileLogWriter(this.directory, console));
        WriteAttempt attempt = attempt(0);
        IllegalStateException failure = new IllegalStateException("database unavailable");

        SnapshotWriter.logRetry(logger, attempt, failure);
        SnapshotWriter.logFinalFailure(logger, attempt, SaveResult.RETRY_LATER, failure);
        logger.close();

        assertEquals(0, console.warnings.size());
        assertEquals(1, console.errors.size());
        assertEquals(0, console.errorCauses.size());
        String content = this.logContent();
        assertEquals(1, content.lines().filter(line -> line.contains("[RETRY]")).count());
        assertEquals(1, occurrences(content, "java.lang.IllegalStateException: database unavailable"));
    }

    private String logContent() throws IOException {
        try (Stream<Path> files = Files.list(this.directory)) {
            return Files.readString(files.findFirst().orElseThrow());
        }
    }

    private static int occurrences(String text, String fragment) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(fragment, from)) >= 0) {
            count++;
            from += fragment.length();
        }
        return count;
    }

    private static WriteAttempt attempt(int maxRetries) {
        SnapshotMeta meta = SnapshotMeta.builder()
                .player(UUID.randomUUID())
                .timestamp(1_756_300_000_000L)
                .cause(SaveCause.DISCONNECT)
                .server("test")
                .build();
        SaveRequest request = new SaveRequest(meta, "TestPlayer", EagerSnapshotData.EMPTY, null);
        request.updateSnapshot(new Snapshot(meta, Map.of()));
        return WriteAttempt.first(request, maxRetries);
    }

    private static final class RecordingLogger implements PluginLogger {
        private final List<String> warnings = new ArrayList<>();
        private final List<Throwable> warningCauses = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private final List<Throwable> errorCauses = new ArrayList<>();

        @Override
        public void info(String s) {
        }

        @Override
        public void warn(String s) {
            this.warnings.add(s);
        }

        @Override
        public void warn(String s, Throwable t) {
            this.warnings.add(s);
            this.warningCauses.add(t);
        }

        @Override
        public void error(String s) {
            this.errors.add(s);
        }

        @Override
        public void error(String s, Throwable t) {
            this.errors.add(s);
            this.errorCauses.add(t);
        }
    }
}
