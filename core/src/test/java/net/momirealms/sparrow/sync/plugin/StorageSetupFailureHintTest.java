package net.momirealms.sparrow.sync.plugin;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoCredential;
import com.mongodb.MongoSecurityException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.ServerAddress;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.storage.StorageType;
import net.momirealms.sparrow.sync.util.StorageSetupFailureHint;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StorageSetupFailureHintTest {
    @Test
    void identifiesNetworkFailuresThroughWrappers() {
        assertEquals(LogConstants.STORAGE_CONNECTION_REFUSED, StorageSetupFailureHint.key(StorageType.MYSQL,
                new IllegalStateException(new SQLException("communications failure", "08S01", new ConnectException("Connection refused")))));
        assertEquals(LogConstants.STORAGE_UNKNOWN_HOST, StorageSetupFailureHint.key(StorageType.MONGODB,
                new IllegalStateException(new UnknownHostException("database.local"))));
        assertEquals(LogConstants.STORAGE_NETWORK_TIMEOUT, StorageSetupFailureHint.key(StorageType.POSTGRESQL,
                new IllegalStateException(new SocketTimeoutException("Read timed out"))));
    }

    @Test
    void identifiesMysqlAndMariaDbServerErrors() {
        for (StorageType type : new StorageType[]{StorageType.MYSQL, StorageType.MARIADB}) {
            assertEquals(LogConstants.STORAGE_LOGIN_DENIED, StorageSetupFailureHint.key(type, new SQLException("denied", "28000", 1045)));
            assertEquals(LogConstants.STORAGE_DATABASE_MISSING, StorageSetupFailureHint.key(type, new SQLException("unknown database", "42000", 1049)));
            assertEquals(LogConstants.STORAGE_PERMISSION_DENIED, StorageSetupFailureHint.key(type, new SQLException("permission", "42000", 1142)));
        }
    }

    @Test
    void identifiesPostgresSqlStates() {
        assertEquals(LogConstants.STORAGE_LOGIN_DENIED, StorageSetupFailureHint.key(StorageType.POSTGRESQL, new SQLException("denied", "28P01")));
        assertEquals(LogConstants.STORAGE_DATABASE_MISSING, StorageSetupFailureHint.key(StorageType.POSTGRESQL, new SQLException("missing", "3D000")));
        assertEquals(LogConstants.STORAGE_PERMISSION_DENIED, StorageSetupFailureHint.key(StorageType.POSTGRESQL, new SQLException("permission", "42501")));
    }

    @Test
    void identifiesMongoFailures() {
        assertEquals(LogConstants.STORAGE_MONGODB_SELECTION_TIMEOUT, StorageSetupFailureHint.key(StorageType.MONGODB,
                new IllegalStateException(new MongoTimeoutException("No server selected"))));
        assertEquals(LogConstants.STORAGE_MONGODB_LOGIN_DENIED, StorageSetupFailureHint.key(StorageType.MONGODB,
                new MongoSecurityException(MongoCredential.createCredential("user", "admin", "secret".toCharArray()), "Authentication failed")));
        assertEquals(LogConstants.STORAGE_MONGODB_LOGIN_DENIED, StorageSetupFailureHint.key(StorageType.MONGODB, mongoCommand(18)));
        assertEquals(LogConstants.STORAGE_MONGODB_PERMISSION_DENIED, StorageSetupFailureHint.key(StorageType.MONGODB, mongoCommand(13)));
        assertNull(StorageSetupFailureHint.key(StorageType.MONGODB, mongoCommand(2)));
    }

    @Test
    void prefersSpecificNetworkCauseOverMongoSelectionTimeout() {
        assertEquals(LogConstants.STORAGE_CONNECTION_REFUSED, StorageSetupFailureHint.key(StorageType.MONGODB,
                new MongoTimeoutException("No server selected", new ConnectException("Connection refused"))));
    }

    private static MongoCommandException mongoCommand(int code) {
        return new MongoCommandException(new BsonDocument("ok", new BsonDouble(0))
                .append("code", new BsonInt32(code)), new ServerAddress("localhost"));
    }

    @Test
    void leavesUnrecognizedFailuresToTheOriginalStackTrace() {
        assertNull(StorageSetupFailureHint.key(StorageType.MYSQL, new SQLException("syntax", "42000", 1064)));
        assertNull(StorageSetupFailureHint.key(StorageType.MONGODB, new IllegalStateException("configuration")));
    }
}
