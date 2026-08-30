package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.sync.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveOutcome;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveResult;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class SnapshotStash {
    private static final String FILE_SUFFIX = ".snapshot";
    private static final String TMP_SUFFIX = ".tmp";
    private static final Pattern UNSAFE_NAME_CHARS = Pattern.compile("[^A-Za-z0-9_-]");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS").withZone(ZoneId.systemDefault());

    private final Path pendingDirectory;        // 待办队列, 启动时插回数据库并删除.
    private final Path exceptionDirectory;      // 证物档案, 只等管理员处置.
    private final BinarySnapshotCodec codec;
    private final SyncLogger logger;

    public SnapshotStash(@NotNull Path dataFolder, @NotNull BinarySnapshotCodec codec, @NotNull SyncLogger logger) {
        this.pendingDirectory = dataFolder.resolve("pending");
        this.exceptionDirectory = dataFolder.resolve("exception");
        this.codec = codec;
        this.logger = logger;
    }

    /**
     * 把没能落库的快照写到本地.
     * 可重试的失败进 pending 等下次启动插回, 其余进 exception 等管理员处置.
     */
    public void stash(@NotNull Snapshot snapshot, @NotNull String playerName, @NotNull SaveResult reason) {
        boolean pending = reason.retriable();
        Path directory = pending ? this.pendingDirectory : this.exceptionDirectory.resolve(directoryOf(reason));
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(fileNameOf(snapshot.meta(), playerName));
            Path temporary = file.resolveSibling(file.getFileName() + TMP_SUFFIX);
            Files.write(temporary, this.codec.encode(snapshot));
            atomicMove(temporary, file);
            String key = pending ? LogConstants.STASH_PENDING : LogConstants.STASH_EXCEPTION;
            this.logger.warn(LogCategory.STASH, snapshot.meta().player(), playerName, key, playerName, file.toString());
        } catch (Throwable throwable) {
            // 最后的防线也失败, 这份数据已经没有去处, 如实报出
            this.logger.error(LogCategory.STASH, snapshot.meta().player(), playerName, throwable, LogConstants.STASH_WRITE_FAILED, playerName);
        }
    }

    /**
     * 把 pending 目录里的快照插回数据库, 供启动完成存储装配后调用.
     * 插回成功的删除文件; 插回失败则中止本轮, 留给下次启动; 损坏或被拒的文件移进 exception 不再参与.
     * 同一玩家的多份文件按名字排序逐份插回以保持时间线.
     */
    public void restorePending(@NotNull StorageProvider storage) {
        List<Path> files = this.listPendingFiles();
        if (files.isEmpty()) return;
        this.logger.info(LogCategory.STASH, LogConstants.STASH_RESTORE_FOUND, String.valueOf(files.size()));
        int restored = 0;
        int leftover = 0;
        for (int i = 0; i < files.size(); i++) {
            RestoreOutcome outcome = this.restoreOne(files.get(i), storage);
            if (outcome == RestoreOutcome.RESTORED) restored++;
            // 数据库又不行了, 剩下的文件原样留给下次启动
            if (outcome == RestoreOutcome.STORAGE_UNAVAILABLE) {
                leftover = files.size() - i;
                this.logger.warn(LogCategory.STASH, LogConstants.STASH_RESTORE_UNAVAILABLE, String.valueOf(leftover));
                break;
            }
        }
        this.logger.info(LogCategory.STASH, LogConstants.STASH_RESTORE_DONE, String.valueOf(restored), String.valueOf(files.size() - restored - leftover), String.valueOf(leftover));
    }

    // 单份文件的插回结果决定它的去向: 删除, 移进 exception, 或原样保留
    private RestoreOutcome restoreOne(Path file, StorageProvider storage) {
        DecodedSnapshot decoded;
        try {
            decoded = this.codec.decode(Files.readAllBytes(file));
        } catch (IOException exception) {
            this.logger.error(LogCategory.STASH, null, null, exception, LogConstants.STASH_CORRUPTED, file.getFileName().toString());
            this.moveToException(file, "corrupted");
            return RestoreOutcome.DISCARDED;
        }
        if (!(decoded instanceof DecodedSnapshot.Valid valid)) {
            DecodedSnapshot.Invalid invalid = (DecodedSnapshot.Invalid) decoded;
            this.logger.error(LogCategory.STASH, LogConstants.STASH_CORRUPTED, file.getFileName() + " (" + invalid.reason() + ": " + invalid.detail() + ")");
            this.moveToException(file, "corrupted");
            return RestoreOutcome.DISCARDED;
        }
        SaveOutcome saved;
        try {
            saved = storage.saveSnapshotOutcome(valid.snapshot()).join();
        } catch (RuntimeException exception) {
            this.logger.error(LogCategory.STASH, null, null, exception, LogConstants.STASH_RESTORE_FAILED, file.getFileName().toString());
            return RestoreOutcome.STORAGE_UNAVAILABLE;
        }
        SaveResult result = saved.result();
        // 落库结果三分: 已在库中的删掉文件, 数据库不可用的整轮收工, 被拒的移去给管理员
        if (result.stored()) {
            this.delete(file);
            return RestoreOutcome.RESTORED;
        }
        if (result.retriable()) {
            if (saved.failure() != null) {
                this.logger.file(LogCategory.STORAGE, valid.snapshot().meta().player(), null, saved.failure(), LogConstants.STORAGE_WRITE_RETRIABLE, valid.snapshot().meta().player().toString());
            }
            return RestoreOutcome.STORAGE_UNAVAILABLE;
        }
        this.logger.error(LogCategory.STASH, LogConstants.STASH_RESTORE_REJECTED, file.getFileName().toString(), result.name());
        this.moveToException(file, directoryOf(result));
        return RestoreOutcome.DISCARDED;
    }

    // 列出待插回的文件并顺带清掉写到一半的临时文件.
    // 文件名以玩家开头且时间定长, 排序即同玩家按采集时刻升序
    private List<Path> listPendingFiles() {
        if (!Files.isDirectory(this.pendingDirectory)) return List.of();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(this.pendingDirectory)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (name.endsWith(TMP_SUFFIX)) {
                    this.delete(entry);
                } else if (name.endsWith(FILE_SUFFIX)) {
                    files.add(entry);
                }
            }
        } catch (IOException exception) {
            this.logger.error(LogCategory.STASH, null, null, exception, LogConstants.STASH_RESTORE_FAILED, this.pendingDirectory.toString());
            return List.of();
        }
        files.sort(null);
        return files;
    }

    private void moveToException(Path file, String category) {
        try {
            Path directory = this.exceptionDirectory.resolve(category);
            Files.createDirectories(directory);
            Files.move(file, directory.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            this.logger.error(LogCategory.STASH, null, null, exception, LogConstants.STASH_RESTORE_FAILED, file.toString());
        }
    }

    private void delete(Path file) {
        try {
            Files.delete(file);
        } catch (IOException exception) {
            this.logger.error(LogCategory.STASH, null, null, exception, LogConstants.STASH_RESTORE_FAILED, file.toString());
        }
    }

    private static String directoryOf(SaveResult reason) {
        return reason == SaveResult.REJECTED_OVERSIZED ? "oversized" : "malformed";
    }

    // 目标文件已存在等极端情形交给原子移动自己失败, 不支持原子移动的文件系统退回普通移动
    private static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // 文件名只做人工检索的索引, 玩家名清洗掉文件系统不接受的字符
    private static String fileNameOf(SnapshotMeta meta, String playerName) {
        return UNSAFE_NAME_CHARS.matcher(playerName).replaceAll("_")
                + "-" + meta.player()
                + "-" + meta.cause().name()
                + "-" + TIME_FORMAT.format(Instant.ofEpochMilli(meta.timestamp()))
                + FILE_SUFFIX;
    }

    private enum RestoreOutcome {
        RESTORED,
        DISCARDED,
        STORAGE_UNAVAILABLE
    }
}
