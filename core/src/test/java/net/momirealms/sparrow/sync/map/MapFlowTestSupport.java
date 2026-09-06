package net.momirealms.sparrow.sync.map;

import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

final class MapFlowTestSupport {
    static final MapSource SOURCE = new MapSource("A-world", 1);
    static final MapIdentity IDENTITY = new MapIdentity("cluster", SOURCE, -1);

    static StoredMap map(int pixel) {
        return new StoredMap(IDENTITY, new MapData(4440, MapDataTest.content(pixel)));
    }

    static SyncLogger logger(List<String> warnings) {
        return new SyncLogger(new PluginLogger() {
            @Override
            public void info(String s) { }
            @Override
            public void warn(String s) { warnings.add(s); }
            @Override
            public void warn(String s, Throwable t) { warnings.add(s); }
            @Override
            public void error(String s) { throw new AssertionError(s); }
            @Override
            public void error(String s, Throwable t) { throw new AssertionError(s, t); }
        });
    }

    static final class Tasks implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public synchronized void execute(@NotNull Runnable task) {
            this.tasks.add(task);
        }

        synchronized void runAll() {
            while (!this.tasks.isEmpty()) this.tasks.remove().run();
        }
    }

    static class Storage implements MapStorage {
        StoredMap current;
        int registrations;
        int reads;
        final List<Integer> writes = new ArrayList<>();

        @Override
        @NotNull
        public CompletableFuture<Optional<StoredMap>> find(@NotNull MapSource source) {
            this.reads++;
            return CompletableFuture.completedFuture(Optional.ofNullable(this.current));
        }

        @Override
        @NotNull
        public CompletableFuture<Optional<StoredMap>> find(int globalId) {
            this.reads++;
            return CompletableFuture.completedFuture(Optional.ofNullable(this.current));
        }

        @Override
        @NotNull
        public CompletableFuture<StoredMap> register(@NotNull MapSource source, @NotNull MapData initial) {
            this.registrations++;
            if (this.current == null) this.current = new StoredMap(IDENTITY, initial);
            return CompletableFuture.completedFuture(this.current);
        }

        @Override
        @NotNull
        public CompletableFuture<Void> update(@NotNull MapIdentity identity, @NotNull MapData data) {
            this.current = new StoredMap(identity, data);
            this.writes.add(data.getTag().getByteArray("colors")[0] & 255);
            return CompletableFuture.completedFuture(null);
        }
    }

    static class Shared implements MapCache {
        final Map<Integer, StoredMap> contents = new HashMap<>();
        final List<Integer> writes = new ArrayList<>();
        int reads;
        int touches;

        @Override
        @NotNull
        public CompletableFuture<Optional<StoredMap>> find(int globalId) {
            this.reads++;
            return CompletableFuture.completedFuture(Optional.ofNullable(this.contents.get(globalId)));
        }

        @Override
        @NotNull
        public CompletableFuture<Void> publish(@NotNull StoredMap map) {
            this.contents.put(map.identity().globalId(), map);
            this.writes.add(map.data().getTag().getByteArray("colors")[0] & 255);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        @NotNull
        public CompletableFuture<Boolean> touch(int globalId) {
            this.touches++;
            return CompletableFuture.completedFuture(this.contents.containsKey(globalId));
        }
    }
}
