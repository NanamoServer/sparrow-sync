package net.momirealms.sparrow.sync.plugin.dependency.transform;

import org.jetbrains.annotations.NotNull;

public interface DependencyTransformer {

    @NotNull
    String cacheKey();

    @NotNull
    byte[] transform(@NotNull String internalClassName, @NotNull byte[] bytecode);
}