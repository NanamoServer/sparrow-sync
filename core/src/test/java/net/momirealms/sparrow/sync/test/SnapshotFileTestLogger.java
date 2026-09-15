package net.momirealms.sparrow.sync.test;

import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class SnapshotFileTestLogger implements PluginLogger {
    public final List<String> infos = new CopyOnWriteArrayList<>();
    public final List<String> warnings = new CopyOnWriteArrayList<>();

    @Override
    public void info(String message) { this.infos.add(message); }

    @Override
    public void warn(String message) { this.warnings.add(message); }

    @Override
    public void warn(String message, Throwable cause) { this.warnings.add(message + ": " + cause); }

    @Override
    public void error(String message) { this.warnings.add(message); }

    @Override
    public void error(String message, Throwable cause) { this.warnings.add(message + ": " + cause); }
}
