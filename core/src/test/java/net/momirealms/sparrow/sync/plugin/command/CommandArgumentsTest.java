package net.momirealms.sparrow.sync.plugin.command;

import net.momirealms.sparrow.sync.storage.StorageProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CommandArgumentsTest {
    @Test
    void resolvesUuidWithoutDatabaseAndPreservesLookupFailure() {
        UUID uuid = UUID.randomUUID();
        AtomicInteger queries = new AtomicInteger();
        StorageProvider storage = (StorageProvider) Proxy.newProxyInstance(StorageProvider.class.getClassLoader(), new Class<?>[]{StorageProvider.class}, (proxy, method, args) -> {
            assertEquals("lookupUser", method.getName());
            queries.incrementAndGet();
            return switch ((String) args[0]) {
                case "Steve" -> CompletableFuture.completedFuture(Optional.of(uuid));
                case "Missing" -> CompletableFuture.completedFuture(Optional.empty());
                default -> CompletableFuture.failedFuture(new IllegalStateException("offline"));
            };
        });
        assertEquals(Optional.of(uuid), CommandArguments.player(storage, uuid.toString()).join());
        assertEquals(0, queries.get());
        assertEquals(Optional.of(uuid), CommandArguments.player(storage, "Steve").join());
        assertEquals(Optional.empty(), CommandArguments.player(storage, "Missing").join());
        assertThrows(CompletionException.class, () -> CommandArguments.player(storage, "Failure").join());
    }

}
