package net.momirealms.sparrow.sync.snapshot.data;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import java.util.List;

@FunctionalInterface
public interface DataDisplay<T> {

    @NotNull
    List<Component> describe(@NotNull T value);
}
