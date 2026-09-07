package net.momirealms.sparrow.sync.storage.mysql;

import com.mysql.cj.jdbc.exceptions.PacketTooBigException;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;

// todo 重新设计这几个方法的位置
final class MysqlFailureClassifier {
    private MysqlFailureClassifier() {
    }

    @Nullable
    static SQLException sqlCause(@NotNull Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) return sql;
        }
        return null;
    }

    // null 表示 SQL 用法或实现错误, 交给调用链按异常处理; 运维可修复的 SQL 失败保留重试.
    @Nullable
    static SaveResult classify(@NotNull SQLException failure) {
        int code = failure.getErrorCode();
        String state = failure.getSQLState();
        // 服务端的包超限可能同时带连接中断状态, 优先保留大小判定.
        if (failure instanceof PacketTooBigException || code == 1153) {
            return SaveResult.REJECTED_OVERSIZED;
        }
        if (code == 1366 || code == 3819 || state != null && (state.startsWith("22") || state.startsWith("23"))) {
            return SaveResult.REJECTED_MALFORMED;
        }
        // 认证、权限和数据库配置可在运维修复后重试, 即使驱动将其归入 42 类错误.
        if (code == 1044 || code == 1045 || code == 1049 || code == 1142 || code == 1143 || code == 1227) {
            return SaveResult.RETRY_LATER;
        }
        if (failure instanceof SQLSyntaxErrorException || failure instanceof SQLFeatureNotSupportedException
                || state != null && (state.startsWith("42") || state.startsWith("07") || state.startsWith("0A") || state.equals("S1009"))) {
            return null;
        }
        return SaveResult.RETRY_LATER;
    }
}
