package net.momirealms.sparrow.sync.plugin.logger;

import net.momirealms.sparrow.sync.locale.TranslationManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Pattern;

public final class FileLogWriter implements AutoCloseable {
    private static final String DEFAULT_TIME_PATTERN = "HH:mm:ss";
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
    private final ZoneId zone = ZoneId.systemDefault();
    private final PluginLogger fallback; // 磁盘写不进去时的告警出口, 必须是纯控制台的实现
    private final LinkedBlockingQueue<Entry> queue = new LinkedBlockingQueue<>();
    private final Thread worker;
    private volatile boolean closed;

    // 以下状态只被 worker 线程触碰
    private BufferedWriter writer;
    private LocalDate writerDay;
    private boolean failureReported;

    public FileLogWriter(@NotNull Path directory, @NotNull PluginLogger fallback) {
        this(directory, DEFAULT_TIME_PATTERN, DEFAULT_DAY_PATTERN, fallback);
    }

    public FileLogWriter(@NotNull Path directory, @NotNull String timePattern, @NotNull String fileDatePattern, @NotNull PluginLogger fallback) {
        this.directory = directory;
        this.fallback = fallback;
        this.timeFormat = pattern(timePattern, DEFAULT_TIME_PATTERN, fallback);
        this.dayFormat = pattern(fileDatePattern, DEFAULT_DAY_PATTERN, fallback);
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
        this.submit(System.currentTimeMillis(), category, player, playerName, text, args, cause);
    }

    // 时间由参数给出, 供测试驱动跨天滚动
    void submit(long epochMillis, @NotNull LogCategory category, @Nullable UUID player, @Nullable String playerName,
                @NotNull String text, @Nullable String[] args, @Nullable Throwable cause) {
        if (this.closed) return;
        this.queue.offer(new Entry(epochMillis, category, player, playerName, text, args, cause));
    }

    private void drainLoop() {
        List<Entry> batch = new ArrayList<>(MAX_BATCH);
        while (true) {
            try {
                batch.add(this.queue.take());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                this.closeWriter();
                return;
            }
            this.queue.drainTo(batch, MAX_BATCH - 1);
            boolean closing = this.writeBatch(batch);
            batch.clear();
            if (closing) {
                this.closeWriter();
                return;
            }
        }
    }

    // 返回 true 表示批内遇到了关闭信号, 信号之前的条目已经写出
    private boolean writeBatch(List<Entry> batch) {
        try {
            for (int i = 0; i < batch.size(); i++) {
                Entry entry = batch.get(i);
                if (entry == CLOSE_SIGNAL) {
                    if (this.writer != null) this.writer.flush();
                    return true;
                }
                this.ensureWriter(Instant.ofEpochMilli(entry.epochMillis()).atZone(this.zone).toLocalDate());
                this.writer.write(this.format(entry));
            }
            if (this.writer != null) this.writer.flush();
            this.failureReported = false;
        } catch (IOException e) {
            // 首次失败告警一次, 恢复前的后续失败静默, 磁盘回来后自愈
            if (!this.failureReported) {
                this.failureReported = true;
                this.fallback.warn("Failed to write the local log file, entries are dropped until the disk recovers", e);
            }
        }
        return false;
    }

    private void ensureWriter(LocalDate day) throws IOException {
        if (this.writer != null && day.equals(this.writerDay)) return;
        if (this.writer != null) this.writer.close();
        Files.createDirectories(this.directory);
        this.writer = Files.newBufferedWriter(this.directory.resolve(this.dayFormat.format(day) + ".log"),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        this.writerDay = day;
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
                this.writer = null;
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        if (this.closed) return;
        this.closed = true;
        this.queue.offer(CLOSE_SIGNAL);
        try {
            this.worker.join(3000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
