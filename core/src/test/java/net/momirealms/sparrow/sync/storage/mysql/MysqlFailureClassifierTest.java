package net.momirealms.sparrow.sync.storage.mysql;

import com.mysql.cj.jdbc.exceptions.PacketTooBigException;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveResult;
import org.junit.jupiter.api.Test;
import org.mariadb.jdbc.export.MaxAllowedPacketException;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class MysqlFailureClassifierTest {
    @Test
    void connectionAndTransactionFailuresRemainRetryable() {
        List<SQLException> failures = List.of(
                new SQLTransientConnectionException("pool timeout"),
                new SQLException("socket", "08S01", new IOException("disconnected")),
                new SQLException("deadlock", "40001", 1213),
                new SQLException("lock timeout", "HY000", 1205),
                new SQLException("disk full", "HY000", 1021)
        );
        for (int i = 0; i < failures.size(); i++) {
            assertEquals(SaveResult.RETRY_LATER, MysqlFailureClassifier.classify(failures.get(i)));
        }
    }

    @Test
    void authorizationAndDatabaseConfigurationCanBeRepaired() {
        assertEquals(SaveResult.RETRY_LATER, MysqlFailureClassifier.classify(new SQLSyntaxErrorException("database denied", "42000", 1044)));
        assertEquals(SaveResult.RETRY_LATER, MysqlFailureClassifier.classify(new SQLException("authentication", "28000", 1045)));
        assertEquals(SaveResult.RETRY_LATER, MysqlFailureClassifier.classify(new SQLSyntaxErrorException("unknown database", "42000", 1049)));
        assertEquals(SaveResult.RETRY_LATER, MysqlFailureClassifier.classify(new SQLSyntaxErrorException("table privilege", "42000", 1142)));
        assertEquals(SaveResult.RETRY_LATER, MysqlFailureClassifier.classify(new SQLSyntaxErrorException("column privilege", "42000", 1143)));
        assertEquals(SaveResult.RETRY_LATER, MysqlFailureClassifier.classify(new SQLSyntaxErrorException("required privilege", "42000", 1227)));
    }

    @Test
    void packetLimitsTakePriorityOverConnectionState() {
        assertEquals(SaveResult.REJECTED_OVERSIZED, MysqlFailureClassifier.classify(new SQLException("packet too large", "08S01", 1153)));
        assertEquals(SaveResult.REJECTED_OVERSIZED, MysqlFailureClassifier.classify(new PacketTooBigException(100, 50)));
        assertEquals(SaveResult.REJECTED_OVERSIZED, MysqlFailureClassifier.classify(new SQLException("packet too large", "08000", new MaxAllowedPacketException("oversized", true))));
        assertEquals(SaveResult.REJECTED_OVERSIZED, MysqlFailureClassifier.classify(new SQLException("packet too large", "HY000", new MaxAllowedPacketException("oversized", false))));
    }

    @Test
    void dataAndConstraintFailuresAreMalformed() {
        assertEquals(SaveResult.REJECTED_MALFORMED, MysqlFailureClassifier.classify(new SQLException("truncation", "22001", 1406)));
        assertEquals(SaveResult.REJECTED_MALFORMED, MysqlFailureClassifier.classify(new SQLException("duplicate", "23000", 1062)));
        assertEquals(SaveResult.REJECTED_MALFORMED, MysqlFailureClassifier.classify(new SQLException("invalid text", "HY000", 1366)));
        assertEquals(SaveResult.REJECTED_MALFORMED, MysqlFailureClassifier.classify(new SQLException("check", "HY000", 3819)));
    }

    @Test
    void sqlUsageAndProgrammingFailuresStayExceptional() {
        assertNull(MysqlFailureClassifier.classify(new SQLSyntaxErrorException("syntax", "42000", 1064)));
        assertNull(MysqlFailureClassifier.classify(new SQLException("missing table", "42S02", 1146)));
        assertNull(MysqlFailureClassifier.classify(new SQLException("argument", "S1009")));
        assertNull(MysqlFailureClassifier.sqlCause(new IllegalArgumentException("bad binding")));
    }

    @Test
    void findsTheSqlFailureThroughAsyncAndLibraryWrappers() {
        SQLException sql = new SQLException("socket", "08S01", new IOException("network"));
        assertSame(sql, MysqlFailureClassifier.sqlCause(new CompletionException(new IllegalStateException(sql))));
    }
}
