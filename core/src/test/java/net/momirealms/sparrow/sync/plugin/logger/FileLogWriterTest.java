package net.momirealms.sparrow.sync.plugin.logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileLogWriterTest {
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @TempDir
    Path directory;

    @Test
    void writesFormattedLinesAndFlushesOnClose() throws IOException {
        // Arrange
        FileLogWriter writer = new FileLogWriter(this.directory, new QuietLogger());
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000042");
        long now = System.currentTimeMillis();

        // Act
        writer.submit(now, LogCategory.SAVE, player, "Catnies", "raw message", null, null);
        writer.submit(now, LogCategory.LIFECYCLE, null, null, "§x§0§0§f§b§9§acolored§r plain", null, null);
        writer.close();

        // Assert
        List<String> lines = Files.readAllLines(this.todayFile(now));
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).matches("\\[\\d{2}:\\d{2}:\\d{2}\\.\\d{3}] \\[SAVE] \\[Catnies 00000000-0000-0000-0000-000000000042] raw message"), lines.get(0));
        // 透传线的 § 颜色序列被剥净, 文件保持纯文本
        assertTrue(lines.get(1).matches("\\[\\d{2}:\\d{2}:\\d{2}\\.\\d{3}] \\[LIFECYCLE] colored plain"), lines.get(1));
    }

    @Test
    void appendsStackTraceOfTheCause() throws IOException {
        // Arrange
        FileLogWriter writer = new FileLogWriter(this.directory, new QuietLogger());
        long now = System.currentTimeMillis();

        // Act
        writer.submit(now, LogCategory.STORAGE, null, null, "boom", null, new IllegalStateException("db gone"));
        writer.close();

        // Assert
        String content = Files.readString(this.todayFile(now));
        assertTrue(content.contains("boom"));
        assertTrue(content.contains("java.lang.IllegalStateException: db gone"));
    }

    @Test
    void rollsOverToANewFilePerDay() throws IOException {
        // Arrange
        FileLogWriter writer = new FileLogWriter(this.directory, new QuietLogger());
        long dayA = System.currentTimeMillis();
        long dayB = dayA + 24 * 60 * 60 * 1000L;

        // Act
        writer.submit(dayA, LogCategory.JOIN, null, null, "first day", null, null);
        writer.submit(dayB, LogCategory.QUIT, null, null, "second day", null, null);
        writer.close();

        // Assert
        assertFalse(Files.exists(this.todayFile(dayA)));
        assertTrue(readGzip(this.archiveFile(this.todayFile(dayA), 1)).contains("first day"));
        assertTrue(Files.readString(this.todayFile(dayB)).contains("second day"));
    }

    @Test
    void compressesExistingLogsAtStartupAndStartsTheCurrentPeriod() throws IOException {
        LocalDate today = LocalDate.now();
        Path oldLog = this.logFile(today.minusDays(1));
        Path currentLog = this.logFile(today);
        Files.writeString(oldLog, "old day", StandardCharsets.UTF_8);
        Files.writeString(currentLog, "previous session", StandardCharsets.UTF_8);

        FileLogWriter writer = new FileLogWriter(this.directory, new QuietLogger());
        writer.submit(System.currentTimeMillis(), LogCategory.LIFECYCLE, null, null, "new session", null, null);
        writer.close();

        assertFalse(Files.exists(oldLog));
        assertEquals("old day", readGzip(this.archiveFile(oldLog, 1)));
        assertEquals("previous session", readGzip(this.archiveFile(currentLog, 1)));
        String current = Files.readString(currentLog);
        assertTrue(current.contains("new session"));
        assertFalse(current.contains("previous session"));
    }

    @Test
    void keepsAnExistingArchiveAndUsesANumberedName() throws IOException {
        Path oldLog = this.logFile(LocalDate.now().minusDays(1));
        Path existingArchive = this.archiveFile(oldLog, 1);
        Files.writeString(oldLog, "new log", StandardCharsets.UTF_8);
        Files.writeString(existingArchive, "existing archive", StandardCharsets.UTF_8);

        FileLogWriter writer = new FileLogWriter(this.directory, new QuietLogger());
        writer.close();

        assertEquals("existing archive", Files.readString(existingArchive));
        assertEquals("new log", readGzip(this.archiveFile(oldLog, 2)));
    }

    @Test
    void deletesExpiredLogsAndArchivesAtStartup() throws IOException {
        FileTime expired = FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8));
        Path oldLog = Files.writeString(this.directory.resolve("old.log"), "expired log");
        Path oldArchive = Files.writeString(this.directory.resolve("old-1.log.gz"), "expired archive");
        Path recentArchive = Files.writeString(this.directory.resolve("recent-1.log.gz"), "recent archive");
        Path unrelated = Files.writeString(this.directory.resolve("notes.txt"), "keep");
        Path nested = Files.createDirectory(this.directory.resolve("nested.log"));
        Path nestedLog = Files.writeString(nested.resolve("old.log"), "keep nested");
        Path[] expiredFiles = {oldLog, oldArchive, unrelated, nestedLog};
        for (int i = 0; i < expiredFiles.length; i++) {
            Files.setLastModifiedTime(expiredFiles[i], expired);
        }
        RecordingLogger fallback = new RecordingLogger();

        FileLogWriter writer = new FileLogWriter(this.directory, "HH:mm:ss", "yyyy-MM-dd", 7, fallback);
        writer.submit(LogCategory.LIFECYCLE, null, null, "new session", null, null);
        writer.close();

        assertFalse(Files.exists(oldLog));
        assertFalse(Files.exists(oldArchive));
        assertFalse(Files.exists(this.archiveFile(oldLog, 2)));
        assertEquals("recent archive", Files.readString(recentArchive));
        assertEquals("keep", Files.readString(unrelated));
        assertEquals("keep nested", Files.readString(nestedLog));
        assertTrue(Files.readString(this.todayFile(System.currentTimeMillis())).contains("new session"));
        assertTrue(fallback.warnings.isEmpty(), fallback.warnings.toString());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void nonPositiveRetentionKeepsExpiredLogs(int retentionDays) throws IOException {
        FileTime expired = FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(365));
        Path oldLog = Files.writeString(this.directory.resolve("old.log"), "old log");
        Path oldArchive = Files.writeString(this.directory.resolve("old-1.log.gz"), "old archive");
        Files.setLastModifiedTime(oldLog, expired);
        Files.setLastModifiedTime(oldArchive, expired);

        FileLogWriter writer = new FileLogWriter(this.directory, "HH:mm:ss", "yyyy-MM-dd", retentionDays, new QuietLogger());
        writer.close();

        assertEquals("old archive", Files.readString(oldArchive));
        assertEquals("old log", readGzip(this.archiveFile(oldLog, 2)));
    }

    @Test
    void compressionPreservesLogAgeForCleanupOnNextStartup() throws IOException {
        Path log = Files.writeString(this.directory.resolve("custom-name.log"), "retained log");
        Files.setLastModifiedTime(log, FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)));
        FileTime lastModified = Files.getLastModifiedTime(log);

        FileLogWriter first = new FileLogWriter(this.directory, "HH:mm:ss", "yyyy-MM", 7, new QuietLogger());
        first.close();

        Path archive = this.archiveFile(log, 1);
        assertEquals("retained log", readGzip(archive));
        assertEquals(lastModified, Files.getLastModifiedTime(archive));

        FileLogWriter second = new FileLogWriter(this.directory, "HH:mm:ss", "yyyy-MM", 1, new QuietLogger());
        second.close();

        assertFalse(Files.exists(archive));
    }

    @Test
    void customDatePatternKeepsOneFileWithinTheSamePeriod() throws IOException {
        LocalDate first = LocalDate.now().withDayOfMonth(1);
        LocalDate second = first.plusDays(1);
        ZoneId zone = ZoneId.systemDefault();
        FileLogWriter writer = new FileLogWriter(this.directory, "HH:mm:ss", "yyyy-MM", new QuietLogger());

        writer.submit(first.atStartOfDay(zone).toInstant().toEpochMilli(), LogCategory.SAVE, null, null, "first", null, null);
        writer.submit(second.atStartOfDay(zone).toInstant().toEpochMilli(), LogCategory.SAVE, null, null, "second", null, null);
        writer.close();

        Path log = this.directory.resolve(DateTimeFormatter.ofPattern("yyyy-MM").format(first) + ".log");
        String content = Files.readString(log);
        assertTrue(content.contains("first"));
        assertTrue(content.contains("second"));
        assertFalse(Files.exists(this.archiveFile(log, 1)));
    }

    @Test
    void rejectsSubmissionsAfterClose() throws IOException {
        // Arrange
        FileLogWriter writer = new FileLogWriter(this.directory, new QuietLogger());
        long now = System.currentTimeMillis();
        assertTrue(writer.submit(now, LogCategory.SAVE, null, null, "kept", null, null));

        // Act
        writer.close();
        assertFalse(writer.submit(now, LogCategory.SAVE, null, null, "dropped", null, null));

        // Assert
        String content = Files.readString(this.todayFile(now));
        assertTrue(content.contains("kept"));
        assertFalse(content.contains("dropped"));
    }

    @Test
    void continuesAfterOneEntryFailsToFormat() throws IOException {
        RecordingLogger fallback = new RecordingLogger();
        FileLogWriter writer = new FileLogWriter(this.directory, fallback);
        long now = System.currentTimeMillis();

        writer.submit(now, LogCategory.STORAGE, null, null, "broken", null, new BrokenStackTraceException());
        writer.submit(now, LogCategory.SAVE, null, null, "still written", null, null);
        writer.close();

        String content = Files.readString(this.todayFile(now));
        assertEquals(1, fallback.warnings.size());
        assertTrue(fallback.warnings.getFirst().contains("Failed to format a local log entry"));
        assertFalse(content.contains("broken"));
        assertTrue(content.contains("still written"));
    }

    @Test
    void reopensWriterAfterIOExceptionEvenWhenCloseAlsoFails() throws Exception {
        RecordingLogger fallback = new RecordingLogger();
        AtomicInteger opened = new AtomicInteger();
        Function<Path, BufferedWriter> factory = file -> {
            if (opened.incrementAndGet() == 1) return failingWriter();
            try {
                return Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        };
        FileLogWriter writer = new FileLogWriter(this.directory, "HH:mm:ss", "yyyy-MM-dd", 0, fallback, factory);
        long now = System.currentTimeMillis();

        writer.submit(now, LogCategory.STORAGE, null, null, "failed write", null, null);
        assertTrue(fallback.warning.await(5, TimeUnit.SECONDS));
        writer.submit(now, LogCategory.STORAGE, null, null, "recovered write", null, null);
        writer.close();

        assertEquals(2, opened.get());
        assertTrue(Files.readString(this.todayFile(now)).contains("recovered write"));
    }

    private Path todayFile(long epochMillis) {
        LocalDate day = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate();
        return this.logFile(day);
    }

    private Path logFile(LocalDate day) {
        return this.directory.resolve(DAY_FORMAT.format(day) + ".log");
    }

    private Path archiveFile(Path log, int index) {
        String name = log.getFileName().toString();
        return log.resolveSibling(name.substring(0, name.length() - ".log".length()) + "-" + index + ".log.gz");
    }

    private static String readGzip(Path file) throws IOException {
        try (GZIPInputStream input = new GZIPInputStream(Files.newInputStream(file))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static BufferedWriter failingWriter() {
        return new BufferedWriter(Writer.nullWriter()) {

            @Override
            public void write(String string, int offset, int length) throws IOException {
                throw new IOException("write failed");
            }

            @Override
            public void close() throws IOException {
                throw new IOException("close failed");
            }
        };
    }

    private static final class QuietLogger implements PluginLogger {
        @Override
        public void info(String s) {
        }

        @Override
        public void warn(String s) {
        }

        @Override
        public void warn(String s, Throwable t) {
        }

        @Override
        public void error(String s) {
        }

        @Override
        public void error(String s, Throwable t) {
        }
    }

    private static final class RecordingLogger implements PluginLogger {
        private final List<String> warnings = new ArrayList<>();
        private final CountDownLatch warning = new CountDownLatch(1);

        @Override
        public void info(String s) {
        }

        @Override
        public void warn(String s) {
            this.warnings.add(s);
            this.warning.countDown();
        }

        @Override
        public void warn(String s, Throwable t) {
            this.warn(s);
        }

        @Override
        public void error(String s) {
        }

        @Override
        public void error(String s, Throwable t) {
        }
    }

    private static final class BrokenStackTraceException extends RuntimeException {

        @Override
        public void printStackTrace(PrintWriter writer) {
            throw new IllegalStateException("broken stack trace");
        }
    }
}
