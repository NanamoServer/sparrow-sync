package net.momirealms.sparrow.sync.plugin.logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(lines.get(0).matches("\\[\\d{2}:\\d{2}:\\d{2}] \\[SAVE] \\[Catnies 00000000-0000-0000-0000-000000000042] raw message"), lines.get(0));
        // 透传线的 § 颜色序列被剥净, 文件保持纯文本
        assertTrue(lines.get(1).matches("\\[\\d{2}:\\d{2}:\\d{2}] \\[LIFECYCLE] colored plain"), lines.get(1));
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
        assertTrue(Files.readString(this.todayFile(dayA)).contains("first day"));
        assertTrue(Files.readString(this.todayFile(dayB)).contains("second day"));
    }

    @Test
    void rejectsSubmissionsAfterClose() throws IOException {
        // Arrange
        FileLogWriter writer = new FileLogWriter(this.directory, new QuietLogger());
        long now = System.currentTimeMillis();
        writer.submit(now, LogCategory.SAVE, null, null, "kept", null, null);

        // Act
        writer.close();
        writer.submit(now, LogCategory.SAVE, null, null, "dropped", null, null);

        // Assert
        String content = Files.readString(this.todayFile(now));
        assertTrue(content.contains("kept"));
        assertTrue(!content.contains("dropped"));
    }

    private Path todayFile(long epochMillis) {
        LocalDate day = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate();
        return this.directory.resolve(DAY_FORMAT.format(day) + ".log");
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
}
