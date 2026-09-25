package net.momirealms.sparrow.sync.plugin.logger;

import net.momirealms.sparrow.sync.locale.TranslationManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

public final class FileLogWriter implements AutoCloseable {
    private static final String DEFAULT_TIME_PATTERN = "HH:mm:ss.SSS";
    private static final String DEFAULT_DAY_PATTERN = "yyyy-MM-dd";
    private static final int MAX_BATCH = 256;
    private static final Entry CLOSE_SIGNAL = new Entry(0L, null, null, null, "", null, null);
    // 透传线的原文是控制台渲染结果, 可能携带 § 颜色序列, 文件保持纯文本
    private static final Pattern SECTION_CODES = Pattern.compile("§x(§[0-9A-Fa-f]){6}|§[0-9A-Za-z]");

    // 一条待写日志. {@code args} 为 null 时 {@code text} 是已成文的原文, 否则是待渲染的翻译键.
    private record Entry(long epochMillis,
                         LogCategory category,
                         @Nullable UUID player,
                         @Nullable String playerName,
                         @NotNull String text,
                         @Nullable String[] args,
                         @Nullable Throwable cause) {
    }

    private final Path directory;
    private final DateTimeFormatter timeFormat;
    private final DateTimeFormatter dayFormat;
    private final int retentionDays;
    private final ZoneId zone = ZoneId.systemDefault();
    private final PluginLogger fallback; // 磁盘写不进去时的告警出口, 必须是纯控制台的实现
    private final LinkedBlockingQueue<Entry> queue = new LinkedBlockingQueue<>();
    private final Thread worker;
    private boolean closed;

    // 以下状态只由写日志的异步线程访问
    private BufferedWriter writer;
    private Path writerFile;
    private boolean failureReported;

    public FileLogWriter(@NotNull Path directory, @NotNull String timePattern, @NotNull String fileDatePattern, int retentionDays, @NotNull PluginLogger fallback) {
        this.directory = directory;
        this.fallback = fallback;
        this.timeFormat = pattern(timePattern, DEFAULT_TIME_PATTERN, fallback);
        this.dayFormat = pattern(fileDatePattern, DEFAULT_DAY_PATTERN, fallback);
        this.retentionDays = retentionDays;
        this.worker = new Thread(this::drainLoop, "sparrow-sync-file-log");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    // 配置里的格式串写坏时回退默认并告警, 日志系统自身不因配置错误而缺席
    private static DateTimeFormatter pattern(String pattern, String fallbackPattern, PluginLogger logger) {
        try {
            return DateTimeFormatter.ofPattern(pattern);
        } catch (IllegalArgumentException exception) {
            logger.warn("Invalid log time format '" + pattern + "', falling back to '" + fallbackPattern + "'", exception);
            return DateTimeFormatter.ofPattern(fallbackPattern);
        }
    }

    public void submit(@NotNull LogCategory category, @Nullable UUID player, @Nullable String playerName,
                       @NotNull String text, @Nullable String[] args, @Nullable Throwable cause) {
        synchronized (this) {
            if (this.closed) return;
            this.queue.offer(new Entry(System.currentTimeMillis(), category, player, playerName, text, args, cause));
        }
    }

    private void drainLoop() {
        List<Entry> batch = new ArrayList<>(MAX_BATCH);
        try {
            // 启动清理、归档与后续写入在同一 worker 上串行
            this.deleteExpiredLogs();
            this.archiveOldLogs();
            while (true) {
                try {
                    batch.add(this.queue.take());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
                this.queue.drainTo(batch, MAX_BATCH - 1);
                boolean closing = this.writeBatch(batch);
                batch.clear();
                if (closing) return;
            }
        } finally {
            synchronized (this) {
                this.closed = true;
                this.queue.clear();
            }
            this.closeWriter();
        }
    }

    // 关闭信号位于最后一批末尾, 返回 true 后 worker 可以收尾
    private boolean writeBatch(List<Entry> batch) {
        boolean closing = batch.get(batch.size() - 1) == CLOSE_SIGNAL;
        int size = closing ? batch.size() - 1 : batch.size();
        try {
            for (int i = 0; i < size; i++) {
                Entry entry = batch.get(i);
                String line;
                try {
                    line = this.format(entry);
                } catch (RuntimeException exception) {
                    this.fallback.warn("Failed to format a local log entry, the entry was dropped", exception);
                    continue;
                }
                this.ensureWriter(Instant.ofEpochMilli(entry.epochMillis()).atZone(this.zone).toLocalDate());
                this.writer.write(line);
            }
            if (this.writer != null) {
                this.writer.flush();
                this.failureReported = false;
            }
        } catch (IOException exception) {
            this.closeWriter();
            // 首次失败告警一次, 恢复前的后续失败静默, 磁盘回来后自愈
            if (!this.failureReported) {
                this.failureReported = true;
                this.fallback.warn("Failed to write the local log file, entries are dropped until the disk recovers", exception);
            }
        }
        return closing;
    }

    private void ensureWriter(LocalDate day) throws IOException {
        Path file = this.logFile(day);
        if (this.writer != null && file.equals(this.writerFile)) return;
        BufferedWriter previous = this.writer;
        this.writer = null;
        this.writerFile = null;
        if (previous != null) {
            previous.close();
            this.archiveOldLogs();
        }
        Files.createDirectories(this.directory);
        try {
            this.writer = openWriter(file);
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
        this.writerFile = file;
    }

    // 按最后修改时间清理日志目录中的过期原文和压缩归档
    private void deleteExpiredLogs() {
        if (this.retentionDays <= 0) return;
        FileTime cutoff = FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(this.retentionDays));
        try {
            Files.createDirectories(this.directory);
            try (DirectoryStream<Path> logs = Files.newDirectoryStream(this.directory, "*.{log,log.gz}")) {
                for (Path log : logs) {
                    if (!Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)) continue;
                    try {
                        if (Files.getLastModifiedTime(log, LinkOption.NOFOLLOW_LINKS).compareTo(cutoff) < 0) {
                            Files.delete(log);
                        }
                    } catch (IOException exception) {
                        this.fallback.warn("Failed to delete expired local log file '" + log.getFileName() + "'", exception);
                    }
                }
            }
        } catch (IOException exception) {
            this.fallback.warn("Failed to scan local log files for cleanup", exception);
        }
    }

    private void archiveOldLogs() {
        try {
            Files.createDirectories(this.directory);
            try (DirectoryStream<Path> logs = Files.newDirectoryStream(this.directory, "*.log")) {
                for (Path log : logs) {
                    if (!Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)) continue;
                    try {
                        compressLog(log);
                    } catch (IOException exception) {
                        this.fallback.warn("Failed to compress old local log file '" + log.getFileName() + "'", exception);
                    }
                }
            }
        } catch (IOException exception) {
            this.fallback.warn("Failed to scan old local log files for compression", exception);
        }
    }

    private Path logFile(LocalDate day) {
        return this.directory.resolve(this.dayFormat.format(day) + ".log");
    }

    private static void compressLog(Path log) throws IOException {
        FileTime lastModified = Files.getLastModifiedTime(log);
        Path archive = nextArchive(log);
        Path temporary = Files.createTempFile(log.getParent(), ".sparrow-sync-log-", ".gz.tmp");
        try {
            try (BufferedInputStream input = new BufferedInputStream(Files.newInputStream(log));
                 GZIPOutputStream output = new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)))) {
                input.transferTo(output);
            }
            // 归档沿用原日志的修改时间, 保留期限从最后写入时计算
            Files.setLastModifiedTime(temporary, lastModified);
            Files.move(temporary, archive);
            Files.delete(log);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path nextArchive(Path log) {
        String fileName = log.getFileName().toString();
        String baseName = fileName.substring(0, fileName.length() - ".log".length());
        for (int index = 1; ; index++) {
            Path archive = log.resolveSibling(baseName + "-" + index + ".log.gz");
            if (!Files.exists(archive)) return archive;
        }
    }

    private static BufferedWriter openWriter(Path file) {
        try {
            return Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private String format(Entry entry) {
        StringBuilder line = new StringBuilder(96);
        // ZonedDateTime 同时承载日期与时间字段, 用户把行内格式配成含日期的样式也能渲染
        line.append('[').append(this.timeFormat.format(Instant.ofEpochMilli(entry.epochMillis()).atZone(this.zone))).append("] ");
        line.append('[').append(entry.category()).append("] ");
        if (entry.player() != null || entry.playerName() != null) {
            line.append('[');
            if (entry.playerName() != null) line.append(entry.playerName());
            if (entry.player() != null) {
                if (entry.playerName() != null) line.append(' ');
                line.append(entry.player());
            }
            line.append("] ");
        }
        line.append(entry.args() == null
                ? SECTION_CODES.matcher(entry.text()).replaceAll("")
                : TranslationManager.plain(entry.text(), entry.args()));
        line.append(System.lineSeparator());
        if (entry.cause() != null) {
            StringWriter stack = new StringWriter(256);
            entry.cause().printStackTrace(new PrintWriter(stack));
            line.append(stack);
        }
        return line.toString();
    }

    private void closeWriter() {
        try {
            if (this.writer != null) {
                this.writer.close();
            }
        } catch (IOException ignored) {
        } finally {
            this.writer = null;
            this.writerFile = null;
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (!this.closed) {
                this.closed = true;
                this.queue.offer(CLOSE_SIGNAL);
            }
        }
        try {
            this.worker.join(3000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
