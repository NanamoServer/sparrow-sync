package net.momirealms.sparrow.sync.util;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoSecurityException;
import com.mongodb.MongoTimeoutException;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.storage.StorageType;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;

@ApiStatus.Internal
public final class StorageSetupFailureHint {
    private StorageSetupFailureHint() {
    }

    @Nullable
    public static String key(@NotNull StorageType type, @NotNull Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                String key = sqlKey(type, sql);
                if (key != null) return key;
            }
            if (cause instanceof ConnectException) return LogConstants.STORAGE_CONNECTION_REFUSED;
            if (cause instanceof UnknownHostException) return LogConstants.STORAGE_UNKNOWN_HOST;
            if (cause instanceof SocketTimeoutException) return LogConstants.STORAGE_NETWORK_TIMEOUT;
        }
        if (type == StorageType.MONGODB) return mongoKey(failure);
        return null;
    }

    @Nullable
    private static String mongoKey(@NotNull Throwable failure) {
        boolean selectionTimedOut = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof MongoSecurityException) return LogConstants.STORAGE_MONGODB_LOGIN_DENIED;
            if (cause instanceof MongoCommandException command) {
                if (command.getErrorCode() == 18) return LogConstants.STORAGE_MONGODB_LOGIN_DENIED;
                if (command.getErrorCode() == 13) return LogConstants.STORAGE_MONGODB_PERMISSION_DENIED;
            }
            if (cause instanceof MongoTimeoutException) selectionTimedOut = true;
        }
        return selectionTimedOut ? LogConstants.STORAGE_MONGODB_SELECTION_TIMEOUT : null;
    }

    @Nullable
    private static String sqlKey(@NotNull StorageType type, @NotNull SQLException failure) {
        if (type == StorageType.MYSQL || type == StorageType.MARIADB) {
            return switch (failure.getErrorCode()) {
                case 1045 -> LogConstants.STORAGE_LOGIN_DENIED;
                case 1049 -> LogConstants.STORAGE_DATABASE_MISSING;
                case 1044, 1142, 1143, 1227 -> LogConstants.STORAGE_PERMISSION_DENIED;
                default -> null;
            };
        }
        if (type == StorageType.POSTGRESQL) {
            String state = failure.getSQLState();
            if ("28P01".equals(state)) return LogConstants.STORAGE_LOGIN_DENIED;
            if ("3D000".equals(state)) return LogConstants.STORAGE_DATABASE_MISSING;
            if ("42501".equals(state)) return LogConstants.STORAGE_PERMISSION_DENIED;
        }
        return null;
    }
}
