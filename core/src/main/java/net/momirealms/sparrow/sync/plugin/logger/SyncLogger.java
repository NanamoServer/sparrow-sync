package net.momirealms.sparrow.sync.plugin.logger;

import net.momirealms.sparrow.sync.locale.TranslationManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public final class SyncLogger implements PluginLogger {
    public final PluginLogger console;
    private volatile FileLogWriter fileWriter;

    public SyncLogger(@NotNull PluginLogger console) {
        this.console = console;
    }

    public void attachFile(@NotNull FileLogWriter writer) {
        this.fileWriter = writer;
    }

    // ===== PluginLogger 透传: 控制台 + 以 LIFECYCLE 原文记入文件, 不经翻译 =====

    @Override
    public void info(String s) {
        this.console.info(s);
        this.submit(LogCategory.LIFECYCLE, null, null, s, null, null);
    }

    @Override
    public void warn(String s) {
        this.console.warn(s);
        this.submit(LogCategory.LIFECYCLE, null, null, s, null, null);
    }

    @Override
    public void warn(String s, Throwable t) {
        this.console.warn(s, t);
        this.submit(LogCategory.LIFECYCLE, null, null, s, null, t);
    }

    @Override
    public void error(String s) {
        this.console.error(s);
        this.submit(LogCategory.LIFECYCLE, null, null, s, null, null);
    }

    @Override
    public void error(String s, Throwable t) {
        this.console.error(s, t);
        this.submit(LogCategory.LIFECYCLE, null, null, s, null, t);
    }

    // ===== 业务通道: 控制台 + 文件, 带类别与玩家身份, 消息经翻译键渲染 =====

    public void info(@NotNull LogCategory category, @NotNull String key, @NotNull String... args) {
        this.info(category, null, null, key, args);
    }

    public void info(@NotNull LogCategory category, @Nullable UUID player, @Nullable String playerName, @NotNull String key, @NotNull String... args) {
        this.console.info(TranslationManager.console(key, args));
        this.submit(category, player, playerName, key, args, null);
    }

    public void warn(@NotNull LogCategory category, @NotNull String key, @NotNull String... args) {
        this.warn(category, null, null, key, args);
    }

    public void warn(@NotNull LogCategory category, @Nullable UUID player, @Nullable String playerName, @NotNull String key, @NotNull String... args) {
        this.console.warn(TranslationManager.console(key, args));
        this.submit(category, player, playerName, key, args, null);
    }

    public void warn(@NotNull LogCategory category, @Nullable UUID player, @Nullable String playerName, @NotNull Throwable cause, @NotNull String key, @NotNull String... args) {
        this.console.warn(TranslationManager.console(key, args), cause);
        this.submit(category, player, playerName, key, args, cause);
    }

    public void warnWithFileCause(@NotNull LogCategory category, @Nullable UUID player, @Nullable String playerName, @NotNull Throwable cause, @NotNull String key, @NotNull String... args) {
        this.console.warn(TranslationManager.console(key, args));
        this.submit(category, player, playerName, key, args, cause);
    }

    public void error(@NotNull LogCategory category, @NotNull String key, @NotNull String... args) {
        this.error(category, null, null, key, args);
    }

    public void error(@NotNull LogCategory category, @Nullable UUID player, @Nullable String playerName, @NotNull String key, @NotNull String... args) {
        this.console.error(TranslationManager.console(key, args));
        this.submit(category, player, playerName, key, args, null);
    }

    public void error(@NotNull LogCategory category, @Nullable UUID player, @Nullable String playerName, @NotNull Throwable cause, @NotNull String key, @NotNull String... args) {
        this.console.error(TranslationManager.console(key, args), cause);
        this.submit(category, player, playerName, key, args, cause);
    }

    public void errorWithFileCause(@NotNull LogCategory category, @Nullable UUID player, @Nullable String playerName, @NotNull Throwable cause, @NotNull String key, @NotNull String... args) {
        this.console.error(TranslationManager.console(key, args));
        this.submit(category, player, playerName, key, args, cause);
    }

    // ===== 仅文件: 控制台裁定为噪音的流水 =====

    public void file(@NotNull LogCategory category, @Nullable UUID player, @Nullable String playerName, @NotNull String key, @NotNull String... args) {
        this.submit(category, player, playerName, key, args, null);
    }

    public void file(@NotNull LogCategory category, @Nullable UUID player, @Nullable String playerName, @NotNull Throwable cause, @NotNull String key, @NotNull String... args) {
        this.submit(category, player, playerName, key, args, cause);
    }

    private void submit(LogCategory category, @Nullable UUID player, @Nullable String playerName,
                        String text, @Nullable String[] args, @Nullable Throwable cause) {
        FileLogWriter writer = this.fileWriter;
        if (writer != null) writer.submit(category, player, playerName, text, args, cause);
    }

    public void close() {
        FileLogWriter writer = this.fileWriter;
        if (writer != null) writer.close();
    }
}
