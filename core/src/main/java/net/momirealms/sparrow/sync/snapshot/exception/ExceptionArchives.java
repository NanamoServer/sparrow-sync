package net.momirealms.sparrow.sync.snapshot.exception;

import net.momirealms.sparrow.sync.snapshot.SnapshotFiles;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

@ApiStatus.Internal
public final class ExceptionArchives {
    private final SnapshotFiles files;
    private final Executor executor;

    public ExceptionArchives(@NotNull SnapshotFiles files, @NotNull Executor executor) {
        this.files = files;
        this.executor = executor;
    }

    // 本地索引由独立头文件组成, 每次刷新扫描头与文件状态, 按逻辑时间及路径倒序分页.
    @NotNull
    public CompletableFuture<Page> load(@Nullable UUID player, @Nullable String category, int index, int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("exception page size must be positive: " + size);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<Entry> entries = this.scan(player, category);
                int count = entries.isEmpty() ? 1 : (entries.size() - 1) / size + 1;
                int actual = Math.clamp(index, 0, count - 1);
                int start = actual * size;
                int end = start + Math.min(size, entries.size() - start);
                return new Page(actual, size, entries.size(), count, List.copyOf(entries.subList(start, end)));
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, this.executor);
    }

    // 未知归属的档案只进入无玩家筛选的列表, 文件名不作为玩家身份依据.
    private List<Entry> scan(UUID player, String category) throws IOException {
        Path directory = this.files.exceptions();
        if (!Files.exists(directory)) return List.of();
        Set<String> paths = new HashSet<>();
        // 头与正文取并集, 只有头的档案也要显示为正文缺失.
        try (Stream<Path> found = Files.walk(directory)) {
            List<Path> foundFiles = found.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)).toList();
            int count = foundFiles.size();
            for (int i = 0; i < count; i++) {
                Path path = foundFiles.get(i);
                String relative = directory.relativize(path).toString().replace('\\', '/');
                if (relative.endsWith(ExceptionHeader.SUFFIX)) {
                    relative = relative.substring(0, relative.length() - ExceptionHeader.SUFFIX.length());
                }
                if (SnapshotFiles.supported(Path.of(relative))) {
                    paths.add(relative);
                }
            }
        }
        List<Entry> entries = new ArrayList<>();
        for (String path : paths) {
            String entryCategory = path.contains("/") ? path.substring(0, path.indexOf('/')) : "";
            if (category != null && !category.equals(entryCategory)) {
                continue;
            }
            Entry entry = this.entry(path);
            if (player != null && (entry.header() == null || entry.header().meta() == null || !player.equals(entry.header().meta().player()))) {
                continue;
            }
            entries.add(entry);
        }
        entries.sort(Comparator.comparingLong(Entry::timestamp).thenComparing(Entry::path).reversed());
        return entries;
    }

    // 缺头或坏头只影响元信息可用性, 正文是否存在单独记录.
    @NotNull
    public Entry entry(@NotNull String relative) throws IOException {
        Path body = this.files.exceptionFile(relative);
        ExceptionHeader header = null;
        HeadStatus status;
        try {
            header = ExceptionHeader.read(body);
            status = HeadStatus.AVAILABLE;
        } catch (NoSuchFileException ignored) {
            status = HeadStatus.MISSING;
        } catch (IOException failure) {
            status = HeadStatus.UNREADABLE;
        }
        String path = this.files.exceptions().relativize(body).toString().replace('\\', '/');
        String category = path.contains("/") ? path.substring(0, path.indexOf('/')) : "";
        return new Entry(path, category, header, status, Files.isRegularFile(body, LinkOption.NOFOLLOW_LINKS));
    }

    public enum HeadStatus { AVAILABLE, MISSING, UNREADABLE }

    public record Entry(@NotNull String path, @NotNull String category, @Nullable ExceptionHeader header, @NotNull HeadStatus headStatus, boolean bodyPresent) {
        public boolean informationAvailable() {
            return this.header != null && this.header.meta() != null;
        }

        public long timestamp() {
            return this.informationAvailable() ? this.header.meta().timestamp() : Long.MIN_VALUE;
        }
    }

    public record Page(int index, int size, int total, int count, @NotNull List<Entry> content) {
    }
}
