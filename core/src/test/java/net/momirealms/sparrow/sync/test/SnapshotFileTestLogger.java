package net.momirealms.sparrow.sync.test;

import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 收集本地文件操作的日志, 供测试核对修复成功与写入失败的反馈. */
public final class SnapshotFileTestLogger implements PluginLogger {
    public final List<String> infos = new CopyOnWriteArrayList<>(); // 已完成操作的记录
    public final List<String> warnings = new CopyOnWriteArrayList<>(); // 操作失败与原因

    /** {@inheritDoc} */
    @Override
    public void info(String message) { this.infos.add(message); }

    /** {@inheritDoc} */
    @Override
    public void warn(String message) { this.warnings.add(message); }

    /** {@inheritDoc} */
    @Override
    public void warn(String message, Throwable cause) { this.warnings.add(message + ": " + cause); }

    /** {@inheritDoc} */
    @Override
    public void error(String message) { this.warnings.add(message); }

    /** {@inheritDoc} */
    @Override
    public void error(String message, Throwable cause) { this.warnings.add(message + ": " + cause); }
}
