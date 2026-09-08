package net.momirealms.sparrow.sync.storage.postgresql;

import net.momirealms.sparrow.sync.storage.StorageProvider.SaveResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;

final class PostgresFailureClassifier {
    private PostgresFailureClassifier() {
    }

    @Nullable
    static SQLException sqlCause(@NotNull Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) return sql;
        }
        return null;
    }

    // PostgreSQL 的错误分类以 SQLState 为准, 数值 vendor code 不标识服务端错误.
    @Nullable
    static SaveResult classify(@NotNull SQLException failure) {
        String state = failure.getSQLState();
        if ("54000".equals(state)) return SaveResult.REJECTED_OVERSIZED;
        if (state != null && (state.startsWith("22") || state.startsWith("23"))) {
            return SaveResult.REJECTED_MALFORMED;
        }
        // 认证、库 / schema 配置和授权可由运维修复, 保留原异常参与重试.
        if ("42501".equals(state) || "3D000".equals(state) || "3F000".equals(state)) {
            return SaveResult.RETRY_LATER;
        }
        if (failure instanceof SQLSyntaxErrorException || failure instanceof SQLFeatureNotSupportedException
                || state != null && (state.startsWith("42") || state.startsWith("07") || state.startsWith("0A"))) {
            return null;
        }
        return SaveResult.RETRY_LATER;
    }
}
